package com.smswebgateway.android.sync

import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.net.ApiResult
import com.smswebgateway.android.net.SyncMessageDto
import com.smswebgateway.android.net.SyncProgressDto
import com.smswebgateway.android.net.SyncRequest
import com.smswebgateway.android.net.describe
import com.smswebgateway.android.sms.ContactResolver
import com.smswebgateway.android.sms.Permissions
import com.smswebgateway.android.sms.SmsProviderReader
import com.smswebgateway.android.util.WakeLocks
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

enum class SyncOutcome { DONE, NOTHING_NEW, RETRY, BLOCKED, ALREADY_RUNNING }

/**
 * 历史短信同步：按 `_id` 游标每批 100 条读取系统短信库，POST /device/messages/sync（带 batch_id）。
 * 每批成功后才推进游标并上报 /device/sync/progress；失败后从游标续传；批次 id 在重试时复用（服务端批次幂等）。
 * 同一个引擎同时用于首次全量同步和之后的增量同步（游标之后没有新行即是「无新增」）。
 */
object SyncEngine {
    const val BATCH_SIZE = 100
    private val mutex = Mutex()

    suspend fun run(): SyncOutcome {
        if (!mutex.tryLock()) return SyncOutcome.ALREADY_RUNNING
        try {
            return WakeLocks.withLock(Graph.context, "history-sync", 5 * 60_000L) { doRun() }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun doRun(): SyncOutcome {
        val ctx = Graph.context
        val prefs = Graph.prefs
        val api = Graph.api
        if (!prefs.isBound) return SyncOutcome.BLOCKED
        if (!Permissions.hasReadSms(ctx)) {
            prefs.syncStatus = "error"
            prefs.syncError = "未授予「读取短信」权限，无法同步历史短信"
            return SyncOutcome.BLOCKED
        }
        val reader = SmsProviderReader(ctx)
        val contacts = ContactResolver(ctx, prefs.useContacts)

        // 重装 / 清数据后：本地游标为 0 时，先尝试从服务端恢复游标。
        if (prefs.syncCursor == 0L && !prefs.cursorRestoreTried) {
            when (val r = api.getSyncProgress()) {
                is ApiResult.Ok -> {
                    prefs.cursorRestoreTried = true
                    if (r.data.lastAndroidSmsId > 0) {
                        prefs.syncCursor = r.data.lastAndroidSmsId
                        prefs.syncSynced = r.data.synced
                        AppLog.i("Sync", "已从服务端恢复同步游标 ${r.data.lastAndroidSmsId}")
                    }
                }
                is ApiResult.Err -> {
                    // 4xx 其它错误：不阻塞同步，视为没有可恢复的进度
                    if (r.httpStatus in 400..499 && !r.isAuth) prefs.cursorRestoreTried = true
                    else if (r.isAuth) return SyncOutcome.BLOCKED
                }
                is ApiResult.NetworkError -> return failRetry("无法连接服务器：${r.message}")
            }
        }

        var cursor = prefs.syncCursor
        val total = try { reader.total() } catch (e: SecurityException) {
            prefs.syncStatus = "error"; prefs.syncError = "读取短信库被拒绝：权限不足"; return SyncOutcome.BLOCKED
        }
        prefs.syncTotal = total
        var synced = if (cursor > 0) reader.countUpTo(cursor) else 0L
        prefs.syncSynced = synced

        var sentAnything = false
        var firstBatch = true
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = try {
                reader.readBatch(cursor, BATCH_SIZE, contacts)
            } catch (e: SecurityException) {
                prefs.syncStatus = "error"; prefs.syncError = "读取短信库被拒绝：权限不足"; return SyncOutcome.BLOCKED
            }
            if (batch.rowsRead == 0) break

            if (!sentAnything && firstBatch) {
                prefs.syncStatus = "running"
                prefs.syncError = null
                postProgress("running", prefs.syncTotal, synced, cursor)
            }
            firstBatch = false
            sentAnything = true

            if (batch.messages.isNotEmpty()) {
                val batchId = reuseOrNewBatchId(cursor, batch.lastId)
                when (val r = api.syncMessages(SyncRequest(batchId, batch.messages))) {
                    is ApiResult.Ok -> Unit
                    else -> {
                        val msg = r.describe()
                        AppLog.w("Sync", "批次上传失败（游标 $cursor），稍后从此处续传：$msg")
                        postProgress("error", prefs.syncTotal, synced, cursor)
                        return failRetry(msg)
                    }
                }
            }
            // 批次成功 → 推进游标、清除待定批次
            cursor = batch.lastId
            synced += batch.rowsRead
            prefs.pendingBatchId = null
            prefs.syncCursor = cursor
            prefs.syncSynced = synced.coerceAtMost(maxOf(prefs.syncTotal, synced))
            if (synced > prefs.syncTotal) prefs.syncTotal = synced
            prefs.lastSyncAt = System.currentTimeMillis()
            postProgress("running", prefs.syncTotal, synced, cursor)
        }

        if (!sentAnything) {
            // 游标之后没有新行：增量同步无新增。仅在状态不是 done 时修正状态并上报。
            if (prefs.syncStatus != "done") {
                prefs.syncStatus = "done"; prefs.syncError = null
                prefs.lastSyncAt = System.currentTimeMillis()
                postProgress("done", prefs.syncTotal, maxOf(synced, prefs.syncSynced), cursor)
            }
            return SyncOutcome.NOTHING_NEW
        }
        prefs.syncStatus = "done"
        prefs.syncError = null
        prefs.syncTotal = maxOf(reader.total(), synced)
        prefs.syncSynced = minOf(synced, prefs.syncTotal)
        prefs.lastSyncAt = System.currentTimeMillis()
        postProgress("done", prefs.syncTotal, prefs.syncSynced, cursor)
        AppLog.i("Sync", "短信同步完成：已同步 ${prefs.syncSynced}/${prefs.syncTotal}，游标 $cursor")
        return SyncOutcome.DONE
    }

    /** 同一游标位置 + 同一批终点时复用上次未确认的 batch_id（服务端对重放返回首次结果）。 */
    private fun reuseOrNewBatchId(from: Long, to: Long): String {
        val prefs = Graph.prefs
        val existing = prefs.pendingBatchId
        if (existing != null && prefs.pendingBatchFrom == from && prefs.pendingBatchTo == to) return existing
        val id = UUID.randomUUID().toString()
        prefs.pendingBatchId = id
        prefs.pendingBatchFrom = from
        prefs.pendingBatchTo = to
        return id
    }

    private fun failRetry(message: String): SyncOutcome {
        Graph.prefs.syncStatus = "error"
        Graph.prefs.syncError = message.take(160)
        return SyncOutcome.RETRY
    }

    private suspend fun postProgress(state: String, total: Long, synced: Long, cursor: Long) {
        // 进度上报失败不影响同步本身（游标以批次成功为准）
        val r = Graph.api.postSyncProgress(SyncProgressDto(state, total, synced, cursor))
        if (r !is ApiResult.Ok) AppLog.d("Sync", "进度上报失败：${r.describe()}")
    }

    /** 用户点击「重新完整同步」：游标归零后重新跑（服务端按 android_sms_id UPSERT，不会产生重复）。 */
    fun resetCursor() {
        val p = Graph.prefs
        p.syncCursor = 0
        p.syncSynced = 0
        p.syncStatus = "never"
        p.syncError = null
        p.pendingBatchId = null
        p.cursorRestoreTried = true // 用户明确要求重头开始，不再从服务端恢复
    }
}
