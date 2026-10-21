package com.smswebgateway.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.smswebgateway.android.sms.TaskStatus
import com.smswebgateway.android.sms.aggregateParts
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: OutboxMessage): Long

    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<OutboxMessage>

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :err WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<Long>, err: String?)

    @Query("SELECT COUNT(*) FROM outbox")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
abstract class SendTaskDao {
    @Query("SELECT * FROM send_task WHERE taskId = :id")
    abstract suspend fun get(id: String): SendTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(t: SendTaskEntity)

    @Query("SELECT * FROM send_task WHERE status = 'sending'")
    abstract suspend fun sendingTasks(): List<SendTaskEntity>

    @Query("SELECT * FROM send_task WHERE status = 'claimed' ORDER BY claimedAt ASC")
    abstract suspend fun claimedTasks(): List<SendTaskEntity>

    @Query("SELECT * FROM send_task WHERE reportPending = 1 ORDER BY updatedAt ASC")
    abstract suspend fun pendingReports(): List<SendTaskEntity>

    @Query("SELECT taskId FROM send_task WHERE status = 'sending'")
    abstract suspend fun sendingIds(): List<String>

    /** 未结束的任务数量（已领取 / 发送中 / 有状态待上报），用于主页「待发送任务」。 */
    @Query("SELECT COUNT(*) FROM send_task WHERE status IN ('claimed','sending') OR reportPending = 1")
    abstract fun activeCountFlow(): Flow<Int>

    @Query("SELECT * FROM send_task ORDER BY updatedAt DESC LIMIT :limit")
    abstract fun recentFlow(limit: Int): Flow<List<SendTaskEntity>>

    @Query("DELETE FROM send_task WHERE taskId = :id")
    abstract suspend fun delete(id: String)

    @Query("DELETE FROM send_task")
    abstract suspend fun clear()

    @Query("UPDATE send_task SET content = '' WHERE taskId = :id")
    abstract suspend fun clearContent(id: String)

    /** 记录服务端已确认的状态（只改 reportedStatus，不碰本地 status，避免与回调并发覆盖）。 */
    @Query("UPDATE send_task SET reportedStatus = :reported WHERE taskId = :id")
    abstract suspend fun setReported(id: String, reported: String)

    /** 仅当服务端已确认的状态追上本地状态时才清除待上报标记；否则保持待上报。 */
    @Query("UPDATE send_task SET reportPending = 0 WHERE taskId = :id AND status = :status AND reportedStatus = :status")
    abstract suspend fun finishReportIfCaughtUp(id: String, status: String): Int

    /** 放弃上报（服务端拒绝非法转移等不可恢复情况），清除待上报标记。 */
    @Query("UPDATE send_task SET reportPending = 0 WHERE taskId = :id")
    abstract suspend fun dropReport(id: String)

    /** 清理已完结且已上报的旧记录里的短信正文，减少本地敏感数据留存（task_id 保留用于去重）。 */
    @Query("UPDATE send_task SET content = '' WHERE reportPending = 0 AND status IN ('sent','delivered','failed','unknown') AND updatedAt < :before AND content != ''")
    abstract suspend fun scrubOldContent(before: Long): Int

    /** 删除很久以前的记录（30 天以上）。 */
    @Query("DELETE FROM send_task WHERE reportPending = 0 AND updatedAt < :before")
    abstract suspend fun deleteOld(before: Long): Int

    /**
     * 在「sending 已成功上报给服务端」之后、调用 SmsManager 之前，原子地把本地状态由 claimed 置为 sending。
     * 返回 false 表示本地状态已不是 claimed（例如并发恢复已处理），此时绝不能发送。
     */
    @Transaction
    open suspend fun beginSending(id: String, partsTotal: Int, processId: String, now: Long): Boolean {
        val t = get(id) ?: return false
        if (t.status != TaskStatus.CLAIMED) return false
        upsert(
            t.copy(
                status = TaskStatus.SENDING,
                reportedStatus = TaskStatus.SENDING,
                reportPending = false,
                partsTotal = partsTotal,
                sendingAt = now,
                sendProcessId = processId,
                updatedAt = now,
            ),
        )
        return true
    }

    /**
     * 把遗留在 sending 的任务转 unknown 并标记待上报——绝不重发。
     * [keepProcessId] 非空时，本进程发出且未超过 [staleBeforeMs] 的任务仍在等待回调，保持不动。
     */
    @Transaction
    open suspend fun recoverSending(now: Long, keepProcessId: String?, staleBeforeMs: Long): Int {
        var n = 0
        for (t in sendingTasks()) {
            val liveInThisProcess = keepProcessId != null && t.sendProcessId == keepProcessId &&
                (t.sendingAt ?: 0L) > staleBeforeMs
            if (liveInThisProcess) continue
            upsert(
                t.copy(
                    status = TaskStatus.UNKNOWN,
                    reportPending = true,
                    errorCode = t.errorCode ?: "process_restarted",
                    errorMessage = t.errorMessage ?: "发送期间应用进程重启，结果无法确定，未重发",
                    updatedAt = now,
                ),
            )
            n++
        }
        return n
    }

    enum class PartEvent { SENT_OK, SENT_FAIL, DELIVERED, DELIVERY_FAIL }

    /** 应用一个分段回调并汇总（一个事务内完成，避免并发回调丢失位）。返回更新后的任务；任务不存在返回 null。 */
    @Transaction
    open suspend fun applyPartEvent(
        taskId: String,
        part: Int,
        event: PartEvent,
        errorCode: String?,
        now: Long,
    ): SendTaskEntity? {
        val t = get(taskId) ?: return null
        val bit = if (part in 0..62) 1L shl part else 0L
        var sent = t.sentMask
        var fail = t.failMask
        var deliv = t.deliveredMask
        var delivFail = t.deliveryFailMask
        when (event) {
            PartEvent.SENT_OK -> { sent = sent or bit; fail = fail and bit.inv() }
            PartEvent.SENT_FAIL -> if (sent and bit == 0L) fail = fail or bit
            PartEvent.DELIVERED -> deliv = deliv or bit
            PartEvent.DELIVERY_FAIL -> delivFail = delivFail or bit
        }
        val agg = aggregateParts(t.status, t.partsTotal, sent, fail, deliv, delivFail)
        val changed = agg.status != t.status
        val code = when {
            !changed -> t.errorCode
            agg.status == TaskStatus.FAILED -> if (agg.partial) "partial_failure" else (errorCode ?: agg.errorCode)
            else -> t.errorCode
        }
        val msg = when {
            changed && agg.status == TaskStatus.FAILED && agg.partial -> "部分分段发送失败，部分分段可能已发出"
            else -> t.errorMessage
        }
        val updated = t.copy(
            status = agg.status,
            sentMask = sent,
            failMask = fail,
            deliveredMask = deliv,
            deliveryFailMask = delivFail,
            errorCode = code,
            errorMessage = msg,
            reportPending = t.reportPending || changed,
            updatedAt = now,
        )
        upsert(updated)
        return updated
    }
}

@Dao
interface RootAuditDao {
    @Insert
    suspend fun insert(a: RootAuditEntity): Long

    @Query("SELECT * FROM root_audit ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RootAuditEntity>

    @Query("SELECT * FROM root_audit ORDER BY id DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<RootAuditEntity>>

    @Query("DELETE FROM root_audit WHERE id NOT IN (SELECT id FROM root_audit ORDER BY id DESC LIMIT 500)")
    suspend fun prune()

    @Query("DELETE FROM root_audit")
    suspend fun clear()
}

@Dao
interface AppLogDao {
    @Insert
    suspend fun insert(l: AppLogEntity): Long

    @Query("SELECT * FROM app_log ORDER BY id DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<AppLogEntity>>

    @Query("DELETE FROM app_log WHERE id NOT IN (SELECT id FROM app_log ORDER BY id DESC LIMIT 1500)")
    suspend fun prune()

    @Query("DELETE FROM app_log")
    suspend fun clear()
}
