package com.smswebgateway.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 非敏感配置与运行状态（SharedPreferences，CE 存储）。设备令牌不在这里，见 [SecureTokenStore]。
 * 任何写入都会递增 [version]，UI 据此刷新。
 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    private fun bump() = _version.update { it + 1 }

    private fun string(key: String) = object : ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String? = sp.getString(key, null)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
            sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply(); bump()
        }
    }

    private fun long(key: String, def: Long = 0L) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Long = sp.getLong(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
            sp.edit().putLong(key, value).apply(); bump()
        }
    }

    private fun int(key: String, def: Int = 0) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply(); bump()
        }
    }

    private fun bool(key: String, def: Boolean = false) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply(); bump()
        }
    }

    // ---- 绑定 ----
    var serverUrl by string("server_url")
    var deviceId by long("device_id")
    var deviceName by string("device_name")

    // ---- 服务端下发的配置 ----
    var heartbeatIntervalS by int("hb_interval_s", 30)
    var pollWaitS by int("poll_wait_s", 20)
    var sendPaused by bool("send_paused")

    // ---- 历史同步 ----
    var syncCursor by long("sync_cursor")
    var syncTotal by long("sync_total")
    var syncSynced by long("sync_synced")
    /** idle | running | done | error | never */
    var syncStatus by string("sync_status")
    var syncError by string("sync_error")
    var cursorRestoreTried by bool("cursor_restore_tried")
    var pendingBatchId by string("pending_batch_id")
    var pendingBatchFrom by long("pending_batch_from")
    var pendingBatchTo by long("pending_batch_to")

    // ---- 运行状态时间戳（毫秒，0 = 从未） ----
    var lastSyncAt by long("last_sync_at")
    var lastServerOkAt by long("last_server_ok_at")
    var lastHeartbeatAt by long("last_heartbeat_at")
    var lastHeartbeatError by string("last_heartbeat_error")
    var serviceStartedAt by long("service_started_at")

    // ---- 开关 ----
    /** 用户在应用内主动停止服务：true 时任何自动机制（开机 / Worker / Watchdog）都不得拉起。 */
    var userStopped by bool("user_stopped")
    var autoIncrementalSync by bool("auto_incremental_sync", true)
    var useContacts by bool("use_contacts", true)
    /** 前台服务超时（Android 15 FGS 超时）退化为 WorkManager 周期同步后置位，UI 显示「同步延迟」。 */
    var fgsTimedOut by bool("fgs_timed_out")

    // ---- Root 增强 ----
    var rootEnabled by bool("root_enabled")
    var rootGranted by bool("root_granted")
    var rootManagerName by string("root_manager_name")
    var rootManagerVersion by string("root_manager_version")
    var wlOriginalKnown by bool("wl_original_known")
    var wlOriginalPresent by bool("wl_original_present")
    var wlAddedByUs by bool("wl_added_by_us")
    var watchdogInstalled by bool("watchdog_installed")
    var watchdogIntervalS by int("watchdog_interval_s", 60)
    var watchdogMaxFailures by int("watchdog_max_failures", 5)
    /** 缓存的 Watchdog 状态：running | stopped | not_installed（由 Root 管理页 / 周期检查刷新）。 */
    var watchdogStatus by string("watchdog_status_cached")
    var watchdogStatusAt by long("watchdog_status_at")
    var batteryWhitelisted by bool("battery_whitelisted_cached")
    /** 已随心跳上报的 root_audit 最大 id，避免重复上报同一批审计（服务端每条都会写 audit_logs）。 */
    var rootAuditReportedId by long("root_audit_reported_id")
    var lastRootCheckAt by long("last_root_check_at")
    /** 用户点击「重置保护」的时间戳（秒），写入 control 的 reset_protection=。 */
    var resetProtectionTs by long("reset_protection_ts")

    // ---- 恢复 ----
    var recoveryCount by int("recovery_count")
    var lastRecoveryAt by long("last_recovery_at")
    var guardFailures by int("guard_failures")
    var guardLastAttemptAt by long("guard_last_attempt_at")

    // ---- 权限请求记录（用于区分「从未请求」与「被永久拒绝」） ----
    fun permissionRequested(permission: String): Boolean = sp.getBoolean("perm_req_$permission", false)
    fun markPermissionRequested(permission: String) {
        sp.edit().putBoolean("perm_req_$permission", true).apply(); bump()
    }

    val isBound: Boolean get() = serverUrl != null && deviceId > 0

    /** 解绑 / 被吊销后清除所有绑定相关状态，仅保留权限请求记录。 */
    fun clearForUnbind() {
        val keep = sp.all.filterKeys { it.startsWith("perm_req_") }
        val e = sp.edit().clear()
        keep.forEach { (k, v) -> if (v is Boolean) e.putBoolean(k, v) }
        e.commit()
        bump()
    }
}
