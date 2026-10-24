package com.smswebgateway.android.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

@Serializable
data class ServerConfig(
    @SerialName("heartbeat_interval_s") val heartbeatIntervalS: Int? = null,
    @SerialName("poll_wait_s") val pollWaitS: Int? = null,
    @SerialName("online_threshold_seconds") val onlineThresholdSeconds: Int? = null,
)

@Serializable
data class PairRequest(
    @SerialName("pairing_token") val pairingToken: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("android_version") val androidVersion: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("sim_count") val simCount: Int,
)

@Serializable
data class PairResponse(
    @SerialName("device_id") val deviceId: Long,
    @SerialName("device_token") val deviceToken: String,
    @SerialName("server_time") val serverTime: Long? = null,
    val config: ServerConfig? = null,
)

@Serializable
data class SimInfoDto(val slot: Int, val operator: String = "", val state: String = "unknown", val number: String? = null)

@Serializable
data class RootAuditDto(val ts: Long, val action: String, val result: String, @SerialName("exit_code") val exitCode: Int? = null)

@Serializable
data class HeartbeatRequest(
    @SerialName("battery_level") val batteryLevel: Int? = null,
    val charging: Boolean? = null,
    @SerialName("network_type") val networkType: String? = null,
    @SerialName("sim_count") val simCount: Int? = null,
    @SerialName("sim_operator") val simOperator: String? = null,
    val sims: List<SimInfoDto>? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("android_version") val androidVersion: String? = null,
    @SerialName("sync_status") val syncStatus: String? = null,
    @SerialName("keepalive_mode") val keepaliveMode: String? = null,
    @SerialName("watchdog_status") val watchdogStatus: String? = null,
    @SerialName("root_granted") val rootGranted: Boolean? = null,
    @SerialName("battery_whitelisted") val batteryWhitelisted: Boolean? = null,
    @SerialName("last_sync_at") val lastSyncAt: Long? = null,
    @SerialName("last_recovery_at") val lastRecoveryAt: Long? = null,
    @SerialName("recovery_count") val recoveryCount: Int? = null,
    @SerialName("protection_state") val protectionState: String? = null,
    @SerialName("root_audit") val rootAudit: List<RootAuditDto>? = null,
)

@Serializable
data class HeartbeatResponse(
    @SerialName("server_time") val serverTime: Long? = null,
    @SerialName("send_paused") val sendPaused: Boolean? = null,
    val config: ServerConfig? = null,
)

@Serializable
data class SyncMessageDto(
    @SerialName("source_type") val sourceType: String,
    @SerialName("android_sms_id") val androidSmsId: Long? = null,
    @SerialName("source_key") val sourceKey: String? = null,
    @SerialName("phone_number") val phoneNumber: String,
    @SerialName("contact_name") val contactName: String? = null,
    val content: String,
    val direction: String,
    val timestamp: Long,
    @SerialName("sim_slot") val simSlot: Int? = null,
    val read: Boolean = true,
)

@Serializable
data class SyncRequest(@SerialName("batch_id") val batchId: String, val messages: List<SyncMessageDto>)

@Serializable
data class SyncResult(
    val inserted: Int = 0,
    val updated: Int = 0,
    val merged: Int = 0,
    val total: Int = 0,
    @SerialName("last_android_sms_id") val lastAndroidSmsId: Long? = null,
)

@Serializable
data class SyncProgressDto(
    val state: String,
    val total: Long = 0,
    val synced: Long = 0,
    @SerialName("last_android_sms_id") val lastAndroidSmsId: Long = 0,
)

@Serializable
data class PollTask(
    @SerialName("task_id") val taskId: String,
    val recipient: String,
    val content: String,
    @SerialName("sim_slot") val simSlot: Int? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
data class PollResponse(
    val tasks: List<PollTask> = emptyList(),
    @SerialName("send_paused") val sendPaused: Boolean = false,
    @SerialName("server_time") val serverTime: Long? = null,
)

@Serializable
data class ClaimRequest(@SerialName("task_id") val taskId: String)

@Serializable
data class TaskDto(
    @SerialName("task_id") val taskId: String,
    val recipient: String? = null,
    val content: String? = null,
    @SerialName("sim_slot") val simSlot: Int? = null,
    val status: String? = null,
)

@Serializable
data class ClaimResponse(val task: TaskDto? = null)

@Serializable
data class TaskResultRequest(
    @SerialName("task_id") val taskId: String,
    val status: String,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("parts_total") val partsTotal: Int? = null,
    @SerialName("parts_sent") val partsSent: Int? = null,
    val timestamp: Long? = null,
)

@Serializable
data class TaskResultResponse(val task: TaskDto? = null)
