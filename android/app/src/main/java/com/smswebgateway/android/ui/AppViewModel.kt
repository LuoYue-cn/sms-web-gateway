package com.smswebgateway.android.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.keepalive.GuardState
import com.smswebgateway.android.keepalive.RecoverCoordinator
import com.smswebgateway.android.keepalive.RecoveryGuard
import com.smswebgateway.android.keepalive.ForegroundServiceStarter
import com.smswebgateway.android.net.Pairing
import com.smswebgateway.android.root.OpResult
import com.smswebgateway.android.root.RootManager
import com.smswebgateway.android.service.GatewayService
import com.smswebgateway.android.service.Unbind
import com.smswebgateway.android.sms.AppPermission
import com.smswebgateway.android.sms.Permissions
import com.smswebgateway.android.sync.Scheduler
import com.smswebgateway.android.sync.SyncEngine
import com.smswebgateway.android.sync.SyncSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val bound: Boolean = false,
    val serverUrl: String = "",
    val deviceName: String = "",
    val deviceId: Long = 0,
    val serviceRunning: Boolean = false,
    val userStopped: Boolean = false,
    val fgsTimedOut: Boolean = false,
    val lastHeartbeatAt: Long = 0,
    val lastHeartbeatError: String? = null,
    val lastServerOkAt: Long = 0,
    val lastSyncAt: Long = 0,
    val syncStatus: String = "never",
    val syncError: String? = null,
    val syncTotal: Long = 0,
    val syncSynced: Long = 0,
    val sendPaused: Boolean = false,
    val autoIncrementalSync: Boolean = true,
    val useContacts: Boolean = true,
    val missingRequired: List<AppPermission> = emptyList(),
    val permissions: Map<AppPermission, Boolean> = emptyMap(),
    val batteryWhitelisted: Boolean = false,
    val rootEnabled: Boolean = false,
    val rootGranted: Boolean = false,
    val rootManager: String? = null,
    val watchdogStatus: String = "not_installed",
    val recoveryCount: Int = 0,
    val lastRecoveryAt: Long = 0,
    val protectionState: String = "none",
    val moduleBundled: Boolean = false,
    val managerHint: String? = null,
    val now: Long = 0,
) {
    /** 同步延迟：前台服务不在运行（用户停止 / 超时 / 被回收），或心跳停止超过 3 个周期。 */
    val syncDelayed: Boolean
        get() = bound && (fgsTimedOut || !serviceRunning || (lastHeartbeatAt > 0 && now - lastHeartbeatAt > 120_000))
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val tick = MutableStateFlow(0L)
    private val refresh = MutableStateFlow(0L)

    val ui: StateFlow<UiState> = combine(Graph.prefs.version, GatewayService.running, tick, refresh) { _, _, _, _ -> snapshot() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            while (true) {
                delay(3_000); tick.update { it + 1 }
            }
        }
    }

    fun refreshNow() = refresh.update { it + 1 }

    private fun snapshot(): UiState {
        val p = Graph.prefs
        val now = System.currentTimeMillis()
        val perms = AppPermission.values().associateWith { it.isGranted(ctx) }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return UiState(
            bound = p.isBound, serverUrl = p.serverUrl.orEmpty(), deviceName = p.deviceName.orEmpty(), deviceId = p.deviceId,
            serviceRunning = GatewayService.running.value, userStopped = p.userStopped, fgsTimedOut = p.fgsTimedOut,
            lastHeartbeatAt = p.lastHeartbeatAt, lastHeartbeatError = p.lastHeartbeatError,
            lastServerOkAt = p.lastServerOkAt, lastSyncAt = p.lastSyncAt,
            syncStatus = p.syncStatus ?: "never", syncError = p.syncError, syncTotal = p.syncTotal, syncSynced = p.syncSynced,
            sendPaused = p.sendPaused, autoIncrementalSync = p.autoIncrementalSync, useContacts = p.useContacts,
            missingRequired = Permissions.missingRequired(ctx), permissions = perms,
            batteryWhitelisted = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            rootEnabled = p.rootEnabled, rootGranted = p.rootGranted, rootManager = p.rootManagerName,
            watchdogStatus = if (!p.watchdogInstalled) "not_installed" else (p.watchdogStatus ?: "stopped"),
            recoveryCount = p.recoveryCount, lastRecoveryAt = p.lastRecoveryAt,
            protectionState = RecoveryGuard.protectionLabel(now, GuardState(p.guardFailures, p.guardLastAttemptAt), p.watchdogMaxFailures),
            moduleBundled = RootManager.bundledModuleAvailable(ctx),
            managerHint = RootManager.managerNameByPackages(ctx),
            now = now,
        )
    }

    fun toast(msg: String?) { message.value = msg }

    // ---- 绑定 ----
    fun pair(server: String, token: String, onDone: (Boolean) -> Unit = {}) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            val r = withContext(Dispatchers.IO) { Pairing.pair(server, token) }
            busy.value = false
            message.value = r.message
            refreshNow()
            onDone(r.ok)
        }
    }

    fun unbind() = viewModelScope.launch {
        busy.value = true
        Unbind.byUser()
        busy.value = false
        message.value = "已解绑，本地数据已清除"
        refreshNow()
    }

    // ---- 服务 / 同步 ----
    fun startService() {
        val ok = ForegroundServiceStarter.start(ctx, userInitiated = true)
        if (!ok) { Scheduler.enqueueRecovery(ctx); message.value = "系统暂不允许启动前台服务，已改用后台任务" }
        refreshNow()
    }

    fun stopService() { GatewayService.stopByUser(ctx); refreshNow() }

    fun syncNow() {
        Graph.appScope.launch(Dispatchers.IO) { SyncEngine.run() }
        SyncSignals.kickAll()
        Scheduler.enqueueUpload(ctx)
        message.value = "已触发同步"
    }

    fun fullResync() {
        SyncEngine.resetCursor()
        syncNow()
    }

    fun setAutoSync(v: Boolean) { Graph.prefs.autoIncrementalSync = v; if (v) SyncSignals.kickHistory() }
    fun setUseContacts(v: Boolean) { Graph.prefs.useContacts = v }

    // ---- Root ----
    private fun rootOp(label: String, block: suspend () -> OpResult) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            val r = try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) {
                AppLog.e("Root", "$label 异常", e); OpResult(false, "$label 失败")
            }
            busy.value = false
            message.value = r.message
            refreshNow()
        }
    }

    fun enableRoot() = rootOp("启用 Root 增强") { RootManager.enableEnhanced(ctx) }
    fun disableRoot() = rootOp("关闭 Root 增强") { RootManager.disableEnhanced(ctx) }
    fun restoreOriginal() = rootOp("恢复原始设置") { RootManager.restoreOriginalSettings(ctx) }
    fun installWatchdog() = rootOp("安装 Watchdog") { RootManager.installWatchdog(ctx) }
    fun setWatchdog(enabled: Boolean) = rootOp("切换 Watchdog") { RootManager.setWatchdogModuleEnabled(enabled) }
    fun resetProtection() = rootOp("重置保护") { RootManager.resetProtection() }
    fun checkBackground() = rootOp("检查后台运行状态") {
        val wd = if (Graph.prefs.rootGranted) RootManager.refreshWatchdog(ctx) else "未授予 Root（跳过）"
        val s = snapshot()
        OpResult(true, "前台服务：${if (s.serviceRunning) "运行中" else "已停止"}；电池白名单：${if (s.batteryWhitelisted) "已加入" else "未加入"}；Watchdog：$wd")
    }

    fun testRecovery() = rootOp("测试自动恢复") {
        val r = RecoverCoordinator.run(ctx, "manual_test", 0, isTest = true)
        OpResult(true, "测试恢复结果：$r（不会重发任何短信；sending 任务会转为 unknown）")
    }
}
