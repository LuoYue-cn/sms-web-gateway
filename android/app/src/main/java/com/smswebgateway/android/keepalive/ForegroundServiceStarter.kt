package com.smswebgateway.android.keepalive

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.service.GatewayService

/**
 * 统一的前台服务启动入口。Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
 * Android 15 还限制 BOOT_COMPLETED 启动部分类型（specialUse 不在受限之列）。失败时返回 false，由调用方降级为 WorkManager。
 */
object ForegroundServiceStarter {
    /**
     * @param userInitiated 用户在界面上点击「启动服务」：会清除 user_stopped。其他来源（开机、Worker、Watchdog）遇到 user_stopped 一律不启动。
     */
    fun start(ctx: Context, fromWorker: Boolean = false, userInitiated: Boolean = false): Boolean {
        Graph.init(ctx)
        val prefs = Graph.prefs
        if (!prefs.isBound) return false
        if (userInitiated) {
            prefs.userStopped = false
            prefs.fgsTimedOut = false
        } else if (prefs.userStopped) {
            AppLog.i("FgsStarter", "用户已主动停止服务，不自动拉起")
            return false
        }
        if (GatewayService.running.value) return true
        return try {
            val i = Intent(ctx, GatewayService::class.java).setAction(GatewayService.ACTION_START)
                .putExtra(GatewayService.EXTRA_USER, userInitiated)
            ContextCompat.startForegroundService(ctx, i)
            true
        } catch (e: Exception) {
            AppLog.w("FgsStarter", "系统不允许现在启动前台服务（${e.javaClass.simpleName}）${if (fromWorker) "[worker]" else ""}")
            false
        }
    }
}
