package com.smswebgateway.android.net

import android.os.Build
import com.smswebgateway.android.BuildConfig
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.keepalive.ForegroundServiceStarter
import com.smswebgateway.android.keepalive.KeepaliveStatus
import com.smswebgateway.android.sms.SimHelper
import com.smswebgateway.android.sync.Scheduler
import com.smswebgateway.android.sync.SyncSignals
import com.smswebgateway.android.util.Redact

/** 绑定流程：校验地址 → POST /device/pair → 令牌进 Keystore 加密存储 → 启动服务与周期任务。 */
object Pairing {
    data class Result(val ok: Boolean, val message: String)

    suspend fun pair(serverInput: String, pairingToken: String): Result {
        val ctx = Graph.context
        val token = pairingToken.trim()
        if (token.isEmpty()) return Result(false, "请输入配对令牌")
        val base = when (val u = ServerUrl.parse(serverInput, allowCleartextLocal = BuildConfig.DEBUG)) {
            is ServerUrlResult.Ok -> u.baseUrl
            is ServerUrlResult.Error -> return Result(false, u.message)
        }
        val req = PairRequest(
            pairingToken = token,
            deviceName = (Build.MODEL ?: "Android").take(60),
            androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            appVersion = BuildConfig.VERSION_NAME,
            simCount = SimHelper.simCount(ctx),
        )
        AppLog.i("Pair", "开始绑定 $base 令牌 ${Redact.token(token)}")
        return when (val r = Graph.api.pair(base, req)) {
            is ApiResult.Ok -> {
                val prefs = Graph.prefs
                Graph.tokens.save(r.data.deviceToken)
                prefs.clearForUnbind()
                prefs.serverUrl = base
                prefs.deviceId = r.data.deviceId
                prefs.deviceName = req.deviceName
                r.data.config?.let { c ->
                    c.heartbeatIntervalS?.let { prefs.heartbeatIntervalS = it.coerceIn(10, 300) }
                    c.pollWaitS?.let { prefs.pollWaitS = it.coerceIn(0, 25) }
                }
                prefs.syncStatus = "never"
                prefs.userStopped = false
                prefs.lastServerOkAt = System.currentTimeMillis()
                AppLog.i("Pair", "绑定成功 device_id=${r.data.deviceId}")
                Scheduler.schedulePeriodic(ctx)
                if (!ForegroundServiceStarter.start(ctx, userInitiated = true)) Scheduler.enqueueRecovery(ctx)
                SyncSignals.kickAll()
                KeepaliveStatus.write(ctx)
                Result(true, "绑定成功")
            }
            is ApiResult.Err -> Result(
                false,
                when (r.code) {
                    "invalid_pairing_token" -> "配对令牌无效、已过期或已被使用，请在网页重新生成"
                    else -> "服务器拒绝：${r.message}"
                },
            )
            is ApiResult.NetworkError -> Result(false, "无法连接服务器：${r.message}")
        }
    }
}
