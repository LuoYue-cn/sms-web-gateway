package com.smswebgateway.android.sms

/** 本地发送任务状态（与服务端状态机同名）。 */
object TaskStatus {
    const val CLAIMED = "claimed"
    const val SENDING = "sending"
    const val SENT = "sent"
    const val DELIVERED = "delivered"
    const val FAILED = "failed"
    const val UNKNOWN = "unknown"

    fun isTerminalLocal(s: String) = s == DELIVERED || s == FAILED || s == UNKNOWN
}

enum class PollDecision {
    /** 尝试 claim（本地无记录，或仅停留在 claimed——此时一定没有调用过 SmsManager）。 */
    CLAIM,

    /** 本地已处理过（sending/sent/delivered/failed/unknown）：永不再发。 */
    SKIP_PROCESSED,
}

/**
 * 幂等协议第 5 条：已处理过的 task_id 永不再发。
 * 本地 `claimed` 的任务从未上报过 sending，因此从未调用 SmsManager，服务端 5 分钟回收后重新下发时允许重新 claim。
 */
fun decideOnPoll(localStatus: String?): PollDecision =
    if (localStatus == null || localStatus == TaskStatus.CLAIMED) PollDecision.CLAIM else PollDecision.SKIP_PROCESSED

/** 只有「本地仍是 claimed 且 sending 已成功上报」才允许调用 SmsManager。 */
fun maySendNow(localStatus: String?, sendingReportedOk: Boolean): Boolean =
    localStatus == TaskStatus.CLAIMED && sendingReportedOk

/** 进程重启后发现停留在 sending 的任务：转 unknown，绝不重发。其他状态不动。 */
fun recoveryTarget(localStatus: String): String? =
    if (localStatus == TaskStatus.SENDING) TaskStatus.UNKNOWN else null

/** 服务端允许的转移（docs/API.md：任务 result）。相同状态重复上报视为幂等。 */
fun isValidServerTransition(from: String, to: String): Boolean {
    if (from == to) return true
    return when (from) {
        TaskStatus.CLAIMED -> to == TaskStatus.SENDING || to == TaskStatus.FAILED || to == TaskStatus.UNKNOWN
        TaskStatus.SENDING -> to == TaskStatus.SENT || to == TaskStatus.FAILED || to == TaskStatus.UNKNOWN
        TaskStatus.SENT -> to == TaskStatus.DELIVERED || to == TaskStatus.FAILED
        else -> false
    }
}

/**
 * 给定「服务端已知状态」和「本地状态」，返回下一步应上报的状态；无需上报返回 null。
 * 例如本地 delivered 而服务端仍是 sending，要先报 sent 再报 delivered（保证每一步都是合法转移）。
 */
fun nextReportStep(reported: String, local: String): String? {
    if (reported == local) return null
    return when (local) {
        TaskStatus.FAILED -> TaskStatus.FAILED.takeIf { isValidServerTransition(reported, it) }
        TaskStatus.UNKNOWN -> TaskStatus.UNKNOWN.takeIf { isValidServerTransition(reported, it) }
        TaskStatus.SENT -> TaskStatus.SENT.takeIf { reported == TaskStatus.SENDING }
        TaskStatus.DELIVERED -> when (reported) {
            TaskStatus.SENDING -> TaskStatus.SENT
            TaskStatus.SENT -> TaskStatus.DELIVERED
            else -> null
        }
        else -> null
    }
}

data class Aggregate(val status: String, val partial: Boolean, val errorCode: String? = null)

private fun fullMask(total: Int): Long = if (total >= 63) Long.MAX_VALUE else (1L shl total) - 1

/**
 * 多段短信回调汇总：所有分段的 sent 回调都到齐后才得出结论。
 * - 全部成功：sent；并且全部送达回执到齐：delivered；
 * - 任一分段失败（且全部 sent 回调已到）：failed（partial=true 表示部分分段已发出）；
 * - 送达回执里有永久失败：failed（delivery_failed）。
 * 已处于 failed/unknown/delivered 的任务不再改变。
 */
fun aggregateParts(
    current: String,
    partsTotal: Int,
    sentMask: Long,
    failMask: Long,
    deliveredMask: Long,
    deliveryFailMask: Long,
): Aggregate {
    if (current != TaskStatus.SENDING && current != TaskStatus.SENT) return Aggregate(current, false)
    val total = partsTotal.coerceAtLeast(1)
    val full = fullMask(total)
    val resolved = sentMask or failMask
    if (resolved != full) return Aggregate(current, false)
    if (failMask != 0L) {
        return Aggregate(TaskStatus.FAILED, partial = sentMask != 0L, errorCode = "send_failed")
    }
    if (deliveryFailMask != 0L) {
        return Aggregate(TaskStatus.FAILED, partial = false, errorCode = "delivery_failed")
    }
    return if (deliveredMask and full == full) Aggregate(TaskStatus.DELIVERED, false)
    else Aggregate(TaskStatus.SENT, false)
}

/** `SmsManager.RESULT_*` → 上报给服务端的 error_code。 */
fun errorCodeForSentResult(resultCode: Int): String = when (resultCode) {
    1 -> "generic_failure"
    2 -> "radio_off"
    3 -> "null_pdu"
    4 -> "no_service"
    5 -> "limit_exceeded"
    6 -> "fdn_check_failure"
    7 -> "short_code_not_allowed"
    8 -> "short_code_never_allowed"
    9 -> "radio_not_available"
    10 -> "network_reject"
    11 -> "invalid_arguments"
    12 -> "invalid_state"
    13 -> "no_memory"
    14 -> "invalid_sms_format"
    15 -> "system_error"
    16 -> "modem_error"
    17 -> "network_error"
    18 -> "encoding_error"
    19 -> "invalid_smsc_address"
    20 -> "operation_not_allowed"
    21 -> "internal_error"
    22 -> "no_resources"
    23 -> "cancelled"
    24 -> "request_not_supported"
    else -> "error_$resultCode"
}
