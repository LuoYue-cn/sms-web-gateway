package com.smswebgateway.android.util

import android.content.Context
import android.os.PowerManager

/** WakeLock 只在短时同步 / 发送操作里持有：带超时，try/finally 必定释放，绝不常驻。 */
object WakeLocks {
    suspend fun <T> withLock(ctx: Context, tag: String, timeoutMs: Long = 120_000L, block: suspend () -> T): T {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsgate:$tag").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
        try {
            return block()
        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}
