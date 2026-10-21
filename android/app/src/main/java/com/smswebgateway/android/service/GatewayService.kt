package com.smswebgateway.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import android.provider.Telephony
import androidx.core.app.ServiceCompat
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.keepalive.KeepaliveStatus
import com.smswebgateway.android.net.ApiResult
import com.smswebgateway.android.net.Backoff
import com.smswebgateway.android.net.describe
import com.smswebgateway.android.sms.Permissions
import com.smswebgateway.android.sync.Reporter
import com.smswebgateway.android.sync.Scheduler
import com.smswebgateway.android.sync.SyncEngine
import com.smswebgateway.android.sync.SyncOutcome
import com.smswebgateway.android.sync.SyncSignals
import com.smswebgateway.android.sync.TaskRunner
import com.smswebgateway.android.sync.Uploader
import com.smswebgateway.android.util.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 前台服务（foregroundServiceType=specialUse）：持续长轮询任务、心跳、上传队列补偿、增量历史同步、状态文件。
 * 断线用指数退避自动重连；服务端重启后恢复；Android 15 的 FGS 超时回调 onTimeout 里退化为 WorkManager 周期同步。
 */
class GatewayService : Service() {
    private var scope: CoroutineScope? = null
    private val netKick = Channel<Unit>(Channel.CONFLATED)
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var smsObserver: ContentObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotificationText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Graph.init(this)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (getSystemService(UserManager::class.java)?.isUserUnlocked == false || !Graph.prefs.isBound) {
            // 先满足 startForegroundService 的承诺再退出
            promote("等待解锁")
            stopSelf()
            return START_NOT_STICKY
        }
        if (Graph.prefs.userStopped && intent?.getBooleanExtra(EXTRA_USER, false) != true) {
            promote("已停止")
            stopSelf()
            return START_NOT_STICKY
        }
        promote("正在启动…")
        if (scope == null) startLoops()
        return START_STICKY
    }

    private fun promote(text: String) {
        lastNotificationText = text
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_SERVICE, Notifications.serviceNotification(this, text),
                if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            AppLog.e("Service", "无法进入前台", e)
            stopSelf()
        }
    }

    private fun startLoops() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        val prefs = Graph.prefs
        prefs.serviceStartedAt = System.currentTimeMillis()
        prefs.fgsTimedOut = false
        _running.value = true
        AppLog.i("Service", "前台服务已启动")
        registerNetwork()
        registerSmsObserver()
        Scheduler.schedulePeriodic(this)

        s.launch { TaskRunner.recoverAfterProcessStart(); TaskRunner.recoverStale() }
        s.launch { heartbeatLoop() }
        s.launch { pollLoop() }
        s.launch { uploadLoop() }
        s.launch { historyLoop() }
        s.launch { statusLoop() }
    }

    // ---------------- 循环 ----------------

    private suspend fun waitOrNetwork(ms: Long) {
        withTimeoutOrNull(ms) { netKick.receive() }
    }

    private suspend fun heartbeatLoop() {
        var failures = 0
        while (scope?.isActive == true && Graph.prefs.isBound) {
            val ok = com.smswebgateway.android.service.HeartbeatSender.send()
            if (ok) {
                failures = 0
                Graph.prefs.lastServerOkAt = System.currentTimeMillis()
                delay(Graph.prefs.heartbeatIntervalS * 1000L)
            } else {
                failures++
                // 失败后比常规间隔更快重试一次，持续失败再退避，上限与心跳间隔相同
                val wait = minOf(Backoff.delayMs(failures), Graph.prefs.heartbeatIntervalS * 1000L)
                waitOrNetwork(Backoff.withJitter(wait))
            }
        }
    }

    private suspend fun pollLoop() {
        var attempt = 0
        val prefs = Graph.prefs
        while (scope?.isActive == true && prefs.isBound) {
            if (!Permissions.hasSendSms(this)) {
                delay(15_000) // 没有发送权限时不领取任务，避免把任务白白置为失败
                continue
            }
            if (!DeviceInfo.networkOk(this)) {
                waitOrNetwork(15_000)
                continue
            }
            TaskRunner.resumeClaimed()
            when (val r = Graph.api.pollTasks(prefs.pollWaitS)) {
                is ApiResult.Ok -> {
                    attempt = 0
                    prefs.sendPaused = r.data.sendPaused
                    prefs.lastServerOkAt = System.currentTimeMillis()
                    if (r.data.sendPaused || r.data.tasks.isEmpty()) {
                        if (prefs.pollWaitS == 0) delay(3_000)
                    } else {
                        val progressed = TaskRunner.handlePolled(r.data.tasks)
                        if (progressed == 0) delay(2_000) // 领取 / 上报失败时不要空转
                    }
                }
                is ApiResult.Err -> {
                    if (r.isAuth) {
                        if (!prefs.isBound) break
                        delay(10_000)
                    } else {
                        AppLog.w("Poll", "轮询失败：${r.describe()}")
                        waitOrNetwork(Backoff.withJitter(Backoff.delayMs(attempt++)))
                    }
                }
                is ApiResult.NetworkError -> {
                    if (attempt == 0) AppLog.w("Poll", "连接服务器失败，将指数退避重连：${r.message}")
                    waitOrNetwork(Backoff.withJitter(Backoff.delayMs(attempt++)))
                }
            }
        }
    }

    private suspend fun uploadLoop() {
        var attempt = 0
        while (scope?.isActive == true && Graph.prefs.isBound) {
            val ok = Uploader.flushOutbox() && Reporter.flush()
            if (ok) {
                attempt = 0
                withTimeoutOrNull(60_000) { SyncSignals.uploadKicks.first() }
            } else {
                waitOrNetwork(Backoff.withJitter(Backoff.delayMs(attempt++)))
            }
        }
    }

    private suspend fun historyLoop() {
        var attempt = 0
        val prefs = Graph.prefs
        while (scope?.isActive == true && prefs.isBound) {
            val shouldRun = prefs.autoIncrementalSync || prefs.syncStatus != "done"
            if (shouldRun && Permissions.hasReadSms(this)) {
                when (SyncEngine.run()) {
                    SyncOutcome.RETRY -> {
                        waitOrNetwork(Backoff.withJitter(Backoff.delayMs(attempt++)))
                        continue
                    }
                    else -> attempt = 0
                }
            }
            withTimeoutOrNull(10 * 60_000L) { SyncSignals.historyKicks.first() }
            delay(2_000) // 去抖：短信库一次变化常伴随多次通知
        }
    }

    /** 每 15 秒：写 status.json 给 Watchdog，并刷新常驻通知文案。 */
    private suspend fun statusLoop() {
        while (scope?.isActive == true) {
            KeepaliveStatus.write(this)
            com.smswebgateway.android.root.RootManager.maintain(applicationContext)
            val text = notificationText()
            if (text != lastNotificationText) {
                lastNotificationText = text
                try {
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifications.ID_SERVICE, Notifications.serviceNotification(this, text))
                } catch (_: SecurityException) {
                }
            }
            delay(15_000)
        }
    }

    private fun notificationText(): String {
        val p = Graph.prefs
        val now = System.currentTimeMillis()
        val hb = p.lastHeartbeatAt
        return when {
            p.sendPaused -> "发送已被服务器暂停"
            hb == 0L -> "正在连接服务器…"
            now - hb > 90_000 -> "与服务器连接中断，重连中…"
            p.syncStatus == "running" -> "同步中 ${p.syncSynced}/${p.syncTotal}"
            else -> "已连接服务器"
        }
    }

    // ---------------- 系统回调 ----------------

    private fun registerNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                AppLog.i("Service", "网络恢复，触发补偿上传与同步")
                netKick.trySend(Unit)
                SyncSignals.kickAll()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            AppLog.w("Service", "注册网络回调失败", e)
        }
    }

    private fun registerSmsObserver() {
        if (!Permissions.hasReadSms(this)) return
        val obs = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                SyncSignals.kickHistory()
            }
        }
        try {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, obs)
            smsObserver = obs
        } catch (e: Exception) {
            AppLog.w("Service", "注册短信库监听失败", e)
        }
    }

    /** Android 15：前台服务类型超时。立即停止并退化为 WorkManager 周期同步，UI 显示「同步延迟」。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w("Service", "前台服务超时 (type=$fgsType)，退化为 WorkManager 周期同步")
        Graph.prefs.fgsTimedOut = true
        Scheduler.schedulePeriodic(applicationContext)
        Scheduler.enqueueUpload(applicationContext)
        Notifications.alert(this, "同步延迟", "系统限制了前台服务的运行时长，已退化为每 15 分钟的周期同步。打开应用可重新启动服务。")
        stopSelf()
    }

    override fun onTimeout(startId: Int) = onTimeout(startId, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

    override fun onDestroy() {
        _running.value = false
        netCallback?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        smsObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        scope?.cancel()
        scope = null
        AppLog.i("Service", "前台服务已停止")
        Graph.appScope.launch(Dispatchers.IO) { KeepaliveStatus.write(applicationContext) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.smswebgateway.android.action.START"
        const val ACTION_STOP = "com.smswebgateway.android.action.STOP"
        const val EXTRA_USER = "user_initiated"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        /** 停止服务（不改变 user_stopped）。 */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GatewayService::class.java))
        }

        /** 用户在应用内主动停止：置 user_stopped=true，任何自动机制（开机 / Worker / Watchdog）都不得再拉起。 */
        fun stopByUser(ctx: Context) {
            Graph.prefs.userStopped = true
            AppLog.i("Service", "用户主动停止服务，不会被自动拉起")
            stop(ctx)
            Graph.appScope.launch(Dispatchers.IO) {
                // 服务 onDestroy 也会写一次；这里确保 user_stopped 已落盘到 status.json
                kotlinx.coroutines.delay(300)
                KeepaliveStatus.write(ctx.applicationContext)
            }
        }
    }
}
