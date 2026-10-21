package com.smswebgateway.android.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** 封装 SmsManager 调用与 PendingIntent 构造。是否允许调用由 [TaskRunner] 按幂等协议决定。 */
object SmsSender {
    const val ACTION_RESULT = "com.smswebgateway.android.action.SMS_RESULT"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_PART = "part"
    const val EXTRA_KIND = "kind"
    const val KIND_SENT = "sent"
    const val KIND_DELIVERY = "delivery"

    sealed interface SendOutcome {
        /** 已提交给系统，结果由回调汇总。 */
        data object Submitted : SendOutcome

        /** 调用在提交前就被拒绝（参数 / 权限异常），确定没有发出任何短信。 */
        data class Rejected(val errorCode: String, val message: String) : SendOutcome

        /** 出现无法判断是否已发出的异常——必须上报 unknown。 */
        data class Uncertain(val message: String) : SendOutcome
    }

    fun split(ctx: Context, slot: Int?, text: String): List<String>? =
        SimHelper.smsManager(ctx, slot)?.let {
            try { it.divideMessage(text) } catch (e: Exception) { null }
        }

    private fun pending(ctx: Context, taskId: String, part: Int, kind: String): PendingIntent {
        val intent = Intent(ACTION_RESULT)
            .setClass(ctx, SmsResultReceiver::class.java)
            // data 不同才会生成不同的 PendingIntent（extras 不参与 filterEquals）
            .setData(Uri.parse("smsgate://result/$taskId/$part/$kind"))
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_PART, part)
            .putExtra(EXTRA_KIND, kind)
        // 回调需要系统往 intent 里填充 pdu / errorCode，所以必须 MUTABLE；目标是显式组件，满足 Android 14+ 要求。
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, (taskId.hashCode() * 31 + part) * 2 + (if (kind == KIND_SENT) 0 else 1), intent, flags)
    }

    /** 真正调用 SmsManager。调用方必须已完成：claim → 本地持久化 → 成功上报 sending → 本地置 sending。 */
    fun send(ctx: Context, taskId: String, recipient: String, slot: Int?, parts: List<String>): SendOutcome {
        val mgr = SimHelper.smsManager(ctx, slot) ?: return SendOutcome.Rejected("sim_unavailable", "无法获取该 SIM 的短信服务")
        val sent = ArrayList<PendingIntent?>(parts.size)
        val delivery = ArrayList<PendingIntent?>(parts.size)
        for (i in parts.indices) {
            sent += pending(ctx, taskId, i, KIND_SENT)
            delivery += pending(ctx, taskId, i, KIND_DELIVERY)
        }
        return try {
            if (parts.size == 1) {
                mgr.sendTextMessage(recipient, null, parts[0], sent[0], delivery[0])
            } else {
                mgr.sendMultipartTextMessage(recipient, null, ArrayList(parts), sent, delivery)
            }
            SendOutcome.Submitted
        } catch (e: SecurityException) {
            SendOutcome.Rejected("send_sms_permission_denied", "没有发送短信权限")
        } catch (e: IllegalArgumentException) {
            SendOutcome.Rejected("invalid_arguments", "号码或内容无效")
        } catch (e: Exception) {
            SendOutcome.Uncertain("调用发送接口时出现异常：${e.javaClass.simpleName}")
        }
    }
}
