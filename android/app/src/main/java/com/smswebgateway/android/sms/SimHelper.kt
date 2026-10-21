package com.smswebgateway.android.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.smswebgateway.android.net.SimInfoDto

/** SIM / 卡槽信息。除 phoneCount / simState 外都需要 READ_PHONE_STATE；无权限时优雅降级。 */
object SimHelper {
    data class SimSlot(val slot: Int, val subId: Int?, val operator: String, val state: String)

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
            )
        }
    }

    fun simDtos(ctx: Context): List<SimInfoDto> = slots(ctx).map { SimInfoDto(it.slot, it.operator, it.state) }

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
