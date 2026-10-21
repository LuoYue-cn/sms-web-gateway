package com.smswebgateway.android.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.sync.Scheduler

/**
 * 开机 / 升级后恢复。
 * - LOCKED_BOOT_COMPLETED：用户尚未首次解锁，CE 存储（Room、SharedPreferences、Keystore 令牌）不可用，什么都不读，等 BOOT_COMPLETED。
 * - BOOT_COMPLETED / MY_PACKAGE_REPLACED：已绑定且用户没有主动停止 → 排周期任务并尝试拉起前台服务（specialUse 允许从
 *   BOOT_COMPLETED 启动）；系统不允许时改排 WorkManager 恢复任务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Graph.init(context)
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED || !unlocked) {
            Log.i("SmsGate/Boot", "设备尚未解锁，推迟到 BOOT_COMPLETED（不访问 CE 存储）")
            return
        }
        val prefs = Graph.prefs
        if (!prefs.isBound) return
        if (prefs.userStopped) {
            AppLog.i("Boot", "开机恢复：用户曾主动停止服务，不自动启动")
            return
        }
        AppLog.i("Boot", "开机/升级恢复（$action）")
        Scheduler.schedulePeriodic(context)
        if (!ForegroundServiceStarter.start(context)) {
            Scheduler.enqueueRecovery(context)
        }
    }
}
