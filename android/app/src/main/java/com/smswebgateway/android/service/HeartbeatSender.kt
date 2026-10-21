package com.smswebgateway.android.service

import com.smswebgateway.android.BuildConfig
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.keepalive.GuardState
import com.smswebgateway.android.keepalive.RecoveryGuard
import com.smswebgateway.android.net.ApiResult
import com.smswebgateway.android.net.HeartbeatRequest
import com.smswebgateway.android.net.RootAuditDto
import com.smswebgateway.android.net.describe
import com.smswebgateway.android.sms.SimHelper
import com.smswebgateway.android.util.DeviceInfo
import android.os.Build
import android.os.PowerManager
import android.content.Context

/** 构造并发送心跳（含电量、充电、网络、SIM、同步状态、保活字段、Root 审计摘要）。 */
object HeartbeatSender {
    fun isBatteryWhitelisted(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    suspend fun send(): Boolean {
        val ctx = Graph.context
        val prefs = Graph.prefs
        if (!prefs.isBound) return false
        val battery = DeviceInfo.battery(ctx)
        val now = System.currentTimeMillis()
        val rootMode = prefs.rootEnabled && prefs.rootGranted
        val whitelisted = isBatteryWhitelisted(ctx)
        prefs.batteryWhitelisted = whitelisted
        val auditDao = Graph.db.rootAudit()
        val unreported = auditDao.recent(60).filter { it.id > prefs.rootAuditReportedId }.take(20)
        val maxAuditId = unreported.maxOfOrNull { it.id }

        val watchdog = when {
            !prefs.watchdogInstalled -> "not_installed"
            else -> prefs.watchdogStatus?.takeIf { it == "running" || it == "stopped" } ?: "stopped"
        }
        val sims = SimHelper.simDtos(ctx)
        val req = HeartbeatRequest(
            batteryLevel = battery.level,
            charging = battery.charging,
            networkType = DeviceInfo.networkType(ctx),
            simCount = SimHelper.simCount(ctx),
            simOperator = SimHelper.primaryOperator(ctx),
            sims = sims,
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = Build.VERSION.RELEASE,
            syncStatus = prefs.syncStatus ?: "never",
            keepaliveMode = if (rootMode) "root_enhanced" else "normal",
            watchdogStatus = watchdog,
            rootGranted = prefs.rootGranted,
            batteryWhitelisted = whitelisted,
            lastSyncAt = prefs.lastSyncAt.takeIf { it > 0 },
            lastRecoveryAt = prefs.lastRecoveryAt.takeIf { it > 0 },
            recoveryCount = prefs.recoveryCount,
            protectionState = RecoveryGuard.protectionLabel(
                now, GuardState(prefs.guardFailures, prefs.guardLastAttemptAt), prefs.watchdogMaxFailures,
            ),
            rootAudit = unreported.takeIf { it.isNotEmpty() }?.map {
                RootAuditDto(it.ts, it.action, it.result, it.exitCode)
            },
        )
        return when (val r = Graph.api.heartbeat(req)) {
            is ApiResult.Ok -> {
                val d = r.data
                prefs.lastHeartbeatAt = now
                prefs.lastHeartbeatError = null
                d.sendPaused?.let { prefs.sendPaused = it }
                d.config?.let { c ->
                    c.heartbeatIntervalS?.let { prefs.heartbeatIntervalS = it.coerceIn(10, 300) }
                    c.pollWaitS?.let { prefs.pollWaitS = it.coerceIn(0, 25) }
                }
                if (maxAuditId != null) prefs.rootAuditReportedId = maxAuditId
                // 服务健康（心跳成功）→ 清零恢复失败计数，自动解除保护
                if (prefs.guardFailures != 0) {
                    val h = RecoveryGuard.onHealthy()
                    prefs.guardFailures = h.failures
                    prefs.guardLastAttemptAt = h.lastAttemptAtMs
                }
                true
            }
            else -> {
                prefs.lastHeartbeatError = r.describe().take(160)
                AppLog.w("Heartbeat", "心跳失败：${r.describe()}")
                false
            }
        }
    }
}
