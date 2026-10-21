package com.smswebgateway.android.sms

import android.content.Context
import android.os.Bundle
import android.provider.BaseColumns
import android.content.ContentResolver
import android.provider.Telephony
import com.smswebgateway.android.net.SyncMessageDto

/**
 * 读取系统短信库（`Telephony.Sms.CONTENT_URI`）。按 `_id` 游标分批，每批使用 LIMIT，绝不一次载入全部。
 */
class SmsProviderReader(private val ctx: Context) {
    data class Batch(
        val messages: List<SyncMessageDto>,
        /** 本批读到的最大 `_id`（包含被跳过的草稿 / 空内容行），用于推进游标。0 表示没有更多行。 */
        val lastId: Long,
        val rowsRead: Int,
    )

    private val resolver: ContentResolver get() = ctx.contentResolver

    /** 系统库中全部短信条数（只取 _id 列，cursor.count 不会把全部行载入内存）。 */
    fun total(): Long = countWhere(null, null)

    /** `_id <= cursor` 的条数，即已处理条数。 */
    fun countUpTo(cursor: Long): Long = if (cursor <= 0) 0 else countWhere("${BaseColumns._ID} <= ?", arrayOf(cursor.toString()))

    private fun countWhere(selection: String?, args: Array<String>?): Long =
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(BaseColumns._ID), selection, args, null)?.use { it.count.toLong() } ?: 0L

    fun readBatch(afterId: Long, limit: Int, contacts: ContactResolver): Batch {
        val slotMap = SimHelper.subscriptionToSlot(ctx)
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${BaseColumns._ID} > ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(afterId.toString()))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${BaseColumns._ID} ASC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = ArrayList<SyncMessageDto>(limit)
        var lastId = 0L
        var rows = 0
        resolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(BaseColumns._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
            val iSub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            while (c.moveToNext()) {
                rows++
                val id = c.getLong(iId)
                lastId = id
                val dir = directionForType(c.getInt(iType)) ?: continue
                val body = c.getString(iBody)
                if (body.isNullOrEmpty()) continue // 空内容（如纯彩信通知占位）不上传，游标照常越过
                val phone = PhoneNumber.normalizeForReceive(c.getString(iAddr))
                val sub = if (iSub >= 0 && !c.isNull(iSub)) c.getInt(iSub) else -1
                out += SyncMessageDto(
                    sourceType = SOURCE_PROVIDER,
                    androidSmsId = id,
                    phoneNumber = phone,
                    contactName = contacts.nameFor(phone),
                    content = body,
                    direction = dir.wire,
                    timestamp = c.getLong(iDate),
                    simSlot = slotForSubscription(sub, slotMap),
                    read = c.getInt(iRead) != 0,
                )
            }
        }
        return Batch(out, lastId, rows)
    }

    companion object {
        const val SOURCE_PROVIDER = "android_provider"
        const val SOURCE_RECEIVER = "android_receiver"
        private val PROJECTION = arrayOf(
            BaseColumns._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.SUBSCRIPTION_ID,
        )
    }
}
