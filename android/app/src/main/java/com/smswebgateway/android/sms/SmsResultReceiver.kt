package com.smswebgateway.android.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.data.db.SendTaskDao
import com.smswebgateway.android.sync.Scheduler
import com.smswebgateway.android.sync.SyncSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** SmsManager 的 sent / delivery 回调：只更新 Room 里的分段位并汇总，然后触发状态上报。 */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsSender.ACTION_RESULT) return
        Graph.init(context)
        val taskId = intent.getStringExtra(SmsSender.EXTRA_TASK_ID) ?: return
        val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)
        val kind = intent.getStringExtra(SmsSender.EXTRA_KIND) ?: return
        val resultCode = resultCode
        val deliveryFailed = if (kind == SmsSender.KIND_DELIVERY) deliveryStatusIsFailure(intent) else null

        val pending = goAsync()
        Graph.appScope.launch(Dispatchers.IO) {
            try {
                val dao = Graph.db.tasks()
                val now = System.currentTimeMillis()
                val updated = when (kind) {
                    SmsSender.KIND_SENT ->
                        if (resultCode == Activity.RESULT_OK) dao.applyPartEvent(taskId, part, SendTaskDao.PartEvent.SENT_OK, null, now)
                        else dao.applyPartEvent(taskId, part, SendTaskDao.PartEvent.SENT_FAIL, errorCodeForSentResult(resultCode), now)
                    else -> when (deliveryFailed) {
                        true -> dao.applyPartEvent(taskId, part, SendTaskDao.PartEvent.DELIVERY_FAIL, "delivery_failed", now)
                        false -> dao.applyPartEvent(taskId, part, SendTaskDao.PartEvent.DELIVERED, null, now)
                        null -> null // 临时状态，忽略
                    }
                }
                if (updated != null) {
                    AppLog.i("SmsResult", "任务 ${taskId.take(8)} 分段${part + 1}/${updated.partsTotal} $kind code=$resultCode -> ${updated.status}")
                    if (updated.reportPending) {
                        Scheduler.enqueueUpload(context)
                        SyncSignals.kickReports()
                    }
                }
            } catch (e: Exception) {
                AppLog.e("SmsResult", "处理发送回调失败", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** 解析送达回执 PDU 的状态：true=永久失败，false=已送达，null=暂不确定（临时状态）。 */
    private fun deliveryStatusIsFailure(intent: Intent): Boolean? {
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        if (pdu == null) return false // 没有 PDU 时，系统调用 delivery intent 即表示已送达
        return try {
            val msg = if (format != null) SmsMessage.createFromPdu(pdu, format) else @Suppress("DEPRECATION") SmsMessage.createFromPdu(pdu)
            val status = msg?.status ?: return false
            when {
                status == 0 -> false // 成功
                status and 0x60 == 0x40 || status and 0x60 == 0x60 -> true // 永久失败
                else -> null // 0x20..0x3F 临时错误 / 仍在尝试
            }
        } catch (e: Exception) {
            false
        }
    }
}
