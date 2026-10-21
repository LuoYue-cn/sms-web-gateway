package com.smswebgateway.android.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 实时接收的待上传队列（source_type 固定为 android_receiver，source_key 为 UUID）。
 * 上传成功后删除，减少本地敏感数据留存。
 */
@Entity(tableName = "outbox", indices = [Index(value = ["sourceKey"], unique = true)])
data class OutboxMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val phone: String,
    val contactName: String?,
    val content: String,
    val direction: String,
    val timestamp: Long,
    val simSlot: Int?,
    val read: Boolean,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

/**
 * 发送任务的本地持久化记录。任何出现在这里的 task_id 都视为「已处理过」，除 claimed 外永不再发。
 * status 为本地状态；reportedStatus 为服务端已确认的状态；reportPending 表示还有状态待上报。
 */
@Entity(tableName = "send_task")
data class SendTaskEntity(
    @PrimaryKey val taskId: String,
    val recipient: String,
    val content: String,
    val simSlot: Int?,
    val status: String,
    val reportedStatus: String?,
    val reportPending: Boolean,
    val partsTotal: Int,
    val sentMask: Long,
    val failMask: Long,
    val deliveredMask: Long,
    val deliveryFailMask: Long,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val claimedAt: Long,
    val sendingAt: Long?,
    /** 调用 SmsManager 的进程标识，用于区分「本进程内仍在等待回调」与「进程重启后遗留」。 */
    val sendProcessId: String?,
    val updatedAt: Long,
)

/** Root 操作审计（心跳里仅上报 ts/action/result/exit_code 摘要）。 */
@Entity(tableName = "root_audit")
data class RootAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val action: String,
    val result: String,
    val exitCode: Int?,
    val detail: String?,
)

@Entity(tableName = "app_log")
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val level: String,
    val tag: String,
    val message: String,
)
