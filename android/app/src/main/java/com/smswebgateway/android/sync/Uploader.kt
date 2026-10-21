package com.smswebgateway.android.sync

import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.net.ApiResult
import com.smswebgateway.android.net.SyncMessageDto
import com.smswebgateway.android.net.SyncRequest
import com.smswebgateway.android.net.describe
import com.smswebgateway.android.sms.ContactResolver
import com.smswebgateway.android.sms.SmsProviderReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** 把实时接收队列（Room outbox）上传到服务器。成功后删除本地记录。 */
object Uploader {
    private val mutex = Mutex()
    private const val BATCH = 100

    /** @return true=队列已清空（或无事可做）；false=失败，需要稍后重试。 */
    suspend fun flushOutbox(): Boolean = mutex.withLock {
        val ctx = Graph.context
        val prefs = Graph.prefs
        if (!prefs.isBound) return true
        val dao = Graph.db.outbox()
        val contacts = ContactResolver(ctx, prefs.useContacts)
        while (true) {
            val rows = dao.pending(BATCH)
            if (rows.isEmpty()) return true
            val dtos = rows.map {
                SyncMessageDto(
                    sourceType = SmsProviderReader.SOURCE_RECEIVER,
                    sourceKey = it.sourceKey,
                    phoneNumber = it.phone,
                    contactName = it.contactName ?: contacts.nameFor(it.phone),
                    content = it.content,
                    direction = it.direction,
                    timestamp = it.timestamp,
                    simSlot = it.simSlot,
                    read = it.read,
                )
            }
            // source_key 是服务端幂等键，批次 id 每次重新生成即可（响应丢失后的重放会被 source_key UPSERT 吸收）。
            when (val r = Graph.api.syncMessages(SyncRequest(UUID.randomUUID().toString(), dtos))) {
                is ApiResult.Ok -> {
                    dao.delete(rows.map { it.id })
                    prefs.lastSyncAt = System.currentTimeMillis()
                    AppLog.i("Uploader", "已上传 ${rows.size} 条实时短信 (inserted=${r.data.inserted} merged=${r.data.merged})")
                }
                else -> {
                    dao.markFailed(rows.map { it.id }, r.describe().take(120))
                    AppLog.w("Uploader", "上传实时短信失败，保留在本地队列：${r.describe()}")
                    return false
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        true
    }
}
