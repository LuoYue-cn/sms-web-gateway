package com.smswebgateway.android.net

import kotlin.random.Random

/** 断线重连的指数退避：1s,2s,4s,…，上限 60s；jitter 为 0~jitterRatio 的随机增量。 */
object Backoff {
    fun delayMs(attempt: Int, baseMs: Long = 1_000, capMs: Long = 60_000): Long {
        if (attempt <= 0) return baseMs.coerceAtMost(capMs)
        val shift = attempt.coerceAtMost(20)
        val raw = baseMs shl shift
        return if (raw <= 0 || raw > capMs) capMs else raw
    }

    fun withJitter(delayMs: Long, jitterRatio: Double = 0.2, random: Random = Random.Default): Long =
        delayMs + (delayMs * jitterRatio * random.nextDouble()).toLong()
}
