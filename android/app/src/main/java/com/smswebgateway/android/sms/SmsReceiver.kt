package com.smswebgateway.android.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.provider.Telephony
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.data.db.OutboxMessage
import com.smswebgateway.android.sync.Scheduler
import com.smswebgateway.android.sync.SyncSignals
import com.smswebgateway.android.util.Redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 实时短信：只解析并写入 Room 待上传队列，然后交给 WorkManager（以及运行中的前台服务）上传。
 * receiver 内不做任何网络。多段短信由系统合并为同一 intent 内的多个 PDU，这里按发件人拼接。
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Graph.init(context)
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return
        if (!Graph.prefs.isBound) return

        val pdus = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            null
        }
        if (pdus.isNullOrEmpty()) return

        val subId = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))
        val slotFromExtra = intent.getIntExtra("slot", intent.getIntExtra("android.telephony.extra.SLOT_INDEX", -1)).takeIf { it >= 0 }
        val now = System.currentTimeMillis()

        // 按发件人归并分段（保持到达顺序）
        val grouped = LinkedHashMap<String, StringBuilder>()
        for (m in pdus) {
            val addr = m.originatingAddress ?: m.displayOriginatingAddress ?: ""
            grouped.getOrPut(addr) { StringBuilder() }.append(m.messageBody ?: "")
        }

        val pending = goAsync()
        Graph.appScope.launch(Dispatchers.IO) {
            try {
                val slot = slotForSubscription(subId, SimHelper.subscriptionToSlot(context)) ?: slotFromExtra
                for ((addr, body) in grouped) {
                    val phone = PhoneNumber.normalizeForReceive(addr)
                    val msg = OutboxMessage(
                        sourceKey = UUID.randomUUID().toString(),
                        phone = phone,
                        contactName = null,
                        content = body.toString(),
                        direction = Direction.INCOMING.wire,
                        timestamp = now,
                        simSlot = slot,
                        read = false,
                        createdAt = now,
                    )
                    Graph.db.outbox().insert(msg)
                    AppLog.i("SmsReceiver", "收到短信 ${Redact.phone(phone)} ${Redact.content(msg.content)} 卡槽=${slot ?: "?"} 已入队")
                }
                Scheduler.enqueueUpload(context)
                SyncSignals.kickUpload()
            } catch (e: Exception) {
                AppLog.e("SmsReceiver", "写入待上传队列失败", e)
            } finally {
                pending.finish()
            }
        }
    }
}
