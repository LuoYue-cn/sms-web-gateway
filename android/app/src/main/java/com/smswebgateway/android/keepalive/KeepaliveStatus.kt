package com.smswebgateway.android.keepalive

import android.content.Context
import com.smswebgateway.android.BuildConfig
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.service.GatewayService
import com.smswebgateway.android.util.DeviceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 写 Watchdog 读取的 status.json（ROOT_INTERFACE.md 第 1 节）。
 * 路径：设备加密存储（DE）`files/keepalive/status.json` —— 开机未解锁也可写，Watchdog 以 root 读取。
 * 原子写：先写 .tmp、fsync、再 rename 替换。
 */
object KeepaliveStatus {
    private val lock = Any()

    fun statusFile(ctx: Context): File {
        val de = ctx.createDeviceProtectedStorageContext()
        return File(File(de.filesDir, "keepalive"), "status.json")
    }

    /** 构造快照（需要 CE 存储可用，即用户已解锁）。 */
    suspend fun snapshot(ctx: Context): JSONObject {
        val p = Graph.prefs
        val sending = try { Graph.db.tasks().sendingIds() } catch (e: Exception) { emptyList() }
        val now = System.currentTimeMillis()
        val guard = GuardState(p.guardFailures, p.guardLastAttemptAt)
        return JSONObject().apply {
            put("pkg", BuildConfig.APPLICATION_ID)
            put("ts", now / 1000)
            put("service_running", GatewayService.running.value)
            put("service_started_at", p.serviceStartedAt / 1000)
            put("last_sync_ok_at", p.lastSyncAt / 1000)
            put("last_server_ok_at", p.lastServerOkAt / 1000)
            put("network_ok", DeviceInfo.networkOk(ctx))
            put("bound", p.isBound)
            put("user_stopped", p.userStopped)
            put("sending_task_ids", JSONArray(sending))
            put("protection_state", RecoveryGuard.protectionLabel(now, guard, p.watchdogMaxFailures))
        }
    }

    suspend fun write(ctx: Context) {
        try {
            writeJson(ctx, snapshot(ctx))
        } catch (e: Exception) {
            AppLog.w("Status", "写 status.json 失败", e)
        }
    }

    /** 未绑定 / 刚解绑时写一份最小快照：bound=false，Watchdog 据此不再恢复。不依赖 CE 存储。 */
    fun writeUnbound(ctx: Context) {
        try {
            writeJson(
                ctx,
                JSONObject().apply {
                    put("pkg", BuildConfig.APPLICATION_ID)
                    put("ts", System.currentTimeMillis() / 1000)
                    put("service_running", false)
                    put("service_started_at", 0)
                    put("last_sync_ok_at", 0)
                    put("last_server_ok_at", 0)
                    put("network_ok", DeviceInfo.networkOk(ctx))
                    put("bound", false)
                    put("user_stopped", false)
                    put("sending_task_ids", JSONArray())
                    put("protection_state", "none")
                },
            )
        } catch (e: Exception) {
            AppLog.w("Status", "写 status.json 失败", e)
        }
    }

    private fun writeJson(ctx: Context, json: JSONObject) = synchronized(lock) {
        val target = statusFile(ctx)
        val dir = target.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建 ${dir.name}")
        val tmp = File(dir, "status.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("rename status.json 失败")
        }
    }
}
