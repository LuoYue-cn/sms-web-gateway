package com.smswebgateway.android.sms

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** 号码 → 联系人名（需要 READ_CONTACTS；无权限返回 null）。带小型缓存，避免批量同步时反复查询。 */
class ContactResolver(private val ctx: Context, private val enabled: Boolean) {
    private val cache = HashMap<String, String?>()

    fun nameFor(phone: String): String? {
        if (!enabled || !Permissions.hasContacts(ctx)) return null
        if (phone.isEmpty() || phone == "unknown" || phone.any { it.isLetter() }) return null
        return cache.getOrPut(phone) { lookup(phone) }
    }

    private fun lookup(phone: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf { n -> n.isNotBlank() } else null
        }
    } catch (e: Exception) {
        null
    }
}
