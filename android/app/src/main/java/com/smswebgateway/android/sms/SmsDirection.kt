package com.smswebgateway.android.sms

enum class Direction(val wire: String) {
    INCOMING("incoming"),
    OUTGOING("outgoing"),
}

/** `Telephony.Sms.TYPE_*` 常量（写成常量以便纯 JVM 单测）。 */
object SmsTypes {
    const val INBOX = 1
    const val SENT = 2
    const val DRAFT = 3
    const val OUTBOX = 4
    const val FAILED = 5
    const val QUEUED = 6
}

/**
 * 由系统短信库 `type` 列推导方向：
 * 1(inbox)=incoming；2(sent)/4(outbox)/5(failed)/6(queued)=outgoing；
 * 3(draft) 及未知类型返回 null —— 草稿不是已收发的短信，不上传（游标仍然越过它）。
 */
fun directionForType(type: Int): Direction? = when (type) {
    SmsTypes.INBOX -> Direction.INCOMING
    SmsTypes.SENT, SmsTypes.OUTBOX, SmsTypes.FAILED, SmsTypes.QUEUED -> Direction.OUTGOING
    else -> null
}

/** sub_id → 卡槽映射。找不到返回 null。 */
fun slotForSubscription(subId: Int, mapping: Map<Int, Int>): Int? = mapping[subId]
