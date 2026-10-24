package com.smswebgateway.android.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.smswebgateway.android.net.SimInfoDto

/** SIM / 卡槽信息。除 phoneCount / simState 外都需要 READ_PHONE_STATE；无权限时优雅降级。 */
object SimHelper {
    data class SimSlot(val slot: Int, val subId: Int?, val operator: String, val state: String, val number: String? = null)

    private fun tm(ctx: Context) = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private fun sm(ctx: Context) = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

    fun slotCount(ctx: Context): Int {
        val t = tm(ctx) ?: return 0
        return try {
            if (Build.VERSION.SDK_INT >= 30) t.activeModemCount.coerceAtLeast(1) else t.phoneCount.coerceAtLeast(1)
        } catch (e: Exception) {
            1
        }
    }

    @Suppress("DEPRECATION")
    private fun simState(ctx: Context, slot: Int): String {
        val t = tm(ctx) ?: return "unknown"
        return try {
            when (t.getSimState(slot)) {
                TelephonyManager.SIM_STATE_READY -> "ready"
                TelephonyManager.SIM_STATE_ABSENT -> "absent"
                TelephonyManager.SIM_STATE_PIN_REQUIRED,
                TelephonyManager.SIM_STATE_PUK_REQUIRED,
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "locked"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /** subId → slot 映射（需 READ_PHONE_STATE，否则空）。 */
    fun subscriptionToSlot(ctx: Context): Map<Int, Int> {
        if (!Permissions.hasPhoneState(ctx)) return emptyMap()
        return try {
            sm(ctx)?.activeSubscriptionInfoList.orEmpty()
                .filter { it.simSlotIndex >= 0 }
                .associate { it.subscriptionId to it.simSlotIndex }
        } catch (e: SecurityException) {
            emptyMap()
        }
    }

    fun slots(ctx: Context): List<SimSlot> {
        val count = slotCount(ctx)
        val infos = if (Permissions.hasPhoneState(ctx)) {
            try { sm(ctx)?.activeSubscriptionInfoList.orEmpty() } catch (e: SecurityException) { emptyList() }
        } else emptyList()
        return (0 until count).map { slot ->
            val info = infos.firstOrNull { it.simSlotIndex == slot }
            val state = simState(ctx, slot).let { if (info != null && it == "unknown") "ready" else it }
            SimSlot(
                slot = slot,
                subId = info?.subscriptionId,
                operator = (info?.carrierName ?: info?.displayName)?.toString().orEmpty(),
                state = state,
                number = if (state == "absent") null else lineNumber(ctx, info),
            )
        }
    }

    /**
     * 本机号码。依次尝试（任何一步读不到、抛 SecurityException 都只是换下一个，不会影响其他字段）：
     *  1. Android 13+ `SubscriptionManager.getPhoneNumber(subId, source)`：UICC（SIM）→ 运营商下发 → IMS 注册。
     *     官方说明这几个来源互为备份：有的运营商不往 SIM 里写号码，但会通过 IMS / 运营商渠道提供。需要 READ_PHONE_NUMBERS。
     *  2. 旧 API `SubscriptionInfo.number` / `TelephonyManager.getLine1Number()`：需要 READ_SMS 或 READ_PHONE_NUMBERS。
     * 全都读不到就返回 null（不上报，网页端可以手动补填）。号码属于个人信息：不写日志。
     */
    @Suppress("DEPRECATION")
    private fun lineNumber(ctx: Context, info: android.telephony.SubscriptionInfo?): String? {
        if (info == null) return null
        val hasNumbers = Permissions.hasPhoneNumbers(ctx)
        val hasSms = Permissions.hasReadSms(ctx)
        if (!hasNumbers && !hasSms) {
            logOnce(info.simSlotIndex, "未授予读取号码所需的权限（READ_PHONE_NUMBERS / READ_SMS）")
            return null
        }
        val subId = info.subscriptionId
        val attempts = ArrayList<Pair<String, () -> String?>>()
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = sm(ctx)
            if (manager != null && hasNumbers) {
                attempts += "SIM卡(UICC)" to { manager.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_UICC) }
                attempts += "运营商下发" to { manager.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER) }
                attempts += "IMS注册" to { manager.getPhoneNumber(subId, SubscriptionManager.PHONE_NUMBER_SOURCE_IMS) }
            }
        }
        attempts += "SubscriptionInfo.number" to { info.number }
        attempts += "getLine1Number" to { tm(ctx)?.createForSubscriptionId(subId)?.line1Number }

        val trace = StringBuilder()
        for ((name, read) in attempts) {
            val raw = try { read() } catch (e: Exception) {
                trace.append(name).append("=异常:").append(e.javaClass.simpleName).append("; ")
                continue
            }
            val n = normalizeNumber(raw)
            // 只记录「是否有值 / 位数」，绝不记录号码本身
            trace.append(name).append('=').append(if (raw.isNullOrBlank()) "空" else if (n == null) "格式无效" else "${n.length}位").append("; ")
            if (n != null) {
                logOnce(info.simSlotIndex, "已读到号码，来源 $name")
                return n
            }
        }
        logOnce(info.simSlotIndex, "所有来源都没有号码：$trace")
        return null
    }

    private val lastLogged = HashMap<Int, String>()

    /** 同一个卡槽同样的结论只记一次，避免心跳（每 30 秒）刷屏。 */
    private fun logOnce(slot: Int, msg: String) {
        synchronized(lastLogged) {
            if (lastLogged[slot] == msg) return
            lastLogged[slot] = msg
        }
        com.smswebgateway.android.data.AppLog.i("SimNumber", "卡槽${slot + 1}：$msg")
    }

    /** 读不到、空串或格式不对（不是 3–20 位数字，可带开头 +）都视为没有号码。复用发送校验，规则只有一份。 */
    fun normalizeNumber(raw: String?): String? = if (raw.isNullOrBlank()) null else PhoneNumber.normalizeForSend(raw)

    fun simDtos(ctx: Context): List<SimInfoDto> = slots(ctx).map { SimInfoDto(it.slot, it.operator, it.state, it.number) }

    /** 已插卡的数量（state != absent）。 */
    fun simCount(ctx: Context): Int = slots(ctx).count { it.state != "absent" }

    fun primaryOperator(ctx: Context): String =
        slots(ctx).firstOrNull { it.state != "absent" && it.operator.isNotEmpty() }?.operator.orEmpty()

    /** 指定槽位是否可用于发送。 */
    fun slotUsable(ctx: Context, slot: Int): Boolean {
        val s = slots(ctx).firstOrNull { it.slot == slot } ?: return false
        return s.state == "ready" && s.subId != null
    }

    fun anySimReady(ctx: Context): Boolean = slots(ctx).any { it.state == "ready" }

    /** 取指定槽位的 SmsManager；slot 为 null 用系统默认 SMS 订阅。无权限 / 不可用返回 null。 */
    fun smsManager(ctx: Context, slot: Int?): SmsManager? {
        return try {
            if (slot == null) {
                if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            } else {
                val subId = slots(ctx).firstOrNull { it.slot == slot }?.subId ?: return null
                if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subId)
            }
        } catch (e: Exception) {
            null
        }
    }
}
