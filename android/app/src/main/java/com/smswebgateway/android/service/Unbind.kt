package com.smswebgateway.android.service

import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.keepalive.KeepaliveStatus
import com.smswebgateway.android.root.RootManager
import com.smswebgateway.android.sync.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** 解绑 / 令牌被吊销：清除凭据与本地数据、停止服务和任务、停止 Root 守护并恢复设置，回到「未绑定」页。 */
object Unbind {
    private val running = AtomicBoolean(false)

    /** 服务端返回 401 device_revoked。 */
    fun onRevoked() {
        Graph.appScope.launch(Dispatchers.IO) { clearEverything("服务器已吊销本设备") }
    }

    /** 用户在设置里点击「解绑」。 */
    suspend fun byUser() = withContext(Dispatchers.IO) { clearEverything("用户主动解绑") }

    private suspend fun clearEverything(reason: String) {
        if (!running.compareAndSet(false, true)) return
        try {
            val ctx = Graph.context
            val prefs = Graph.prefs
            AppLog.w("Unbind", "清除绑定：$reason")
            // 先让 Watchdog 停手：写 bound=false 的状态文件，并停止服务 / 周期任务
            KeepaliveStatus.writeUnbound(ctx)
            GatewayService.stop(ctx)
            Scheduler.cancelAll(ctx)
            // 关闭 Root 增强：写 enabled=0 并还原电池白名单（需要原始状态，必须在清 prefs 之前）
            if (prefs.rootEnabled || prefs.watchdogInstalled || prefs.wlAddedByUs) {
                withTimeoutOrNull(40_000) { RootManager.disableEnhanced(ctx, reason = "unbind") }
            }
            Graph.tokens.clear()
            val db = Graph.db
            db.outbox().clear()
            db.tasks().clear()
            db.rootAudit().clear()
            db.logs().clear()
            prefs.clearForUnbind()
            KeepaliveStatus.writeUnbound(ctx)
            AppLog.i("Unbind", "已回到未绑定状态")
        } catch (e: Exception) {
            AppLog.e("Unbind", "清理未完全成功", e)
            // 无论如何，凭据必须清掉
            Graph.tokens.clear()
            Graph.prefs.clearForUnbind()
        } finally {
            running.set(false)
        }
    }
}
