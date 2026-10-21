package com.smswebgateway.android.keepalive

/** 冷却序列：连续失败 1/2/3 次后分别等待 10s/30s/60s，之后 300s（ROOT_INTERFACE.md 第 3 节、规格 10.5）。 */
object Cooldown {
    private val STEPS = intArrayOf(10, 30, 60, 300)
    const val DEFAULT_MAX_FAILURES = 5

    fun delaySeconds(failures: Int): Int = when {
        failures <= 0 -> 0
        else -> STEPS[minOf(failures - 1, STEPS.size - 1)]
    }
}

data class GuardState(val failures: Int = 0, val lastAttemptAtMs: Long = 0L)

sealed interface GuardDecision {
    data object Accept : GuardDecision
    data class InCooldown(val remainingSeconds: Int) : GuardDecision
    data object Protected : GuardDecision
}

/** APK 侧恢复保护：对 WATCHDOG_RECOVER 广播做冷却与保护判定（纯逻辑）。 */
object RecoveryGuard {
    fun evaluate(nowMs: Long, state: GuardState, maxFailures: Int = Cooldown.DEFAULT_MAX_FAILURES): GuardDecision {
        if (state.failures >= maxFailures) return GuardDecision.Protected
        val delayMs = Cooldown.delaySeconds(state.failures) * 1000L
        val elapsed = nowMs - state.lastAttemptAtMs
        if (state.failures > 0 && elapsed in 0 until delayMs) {
            return GuardDecision.InCooldown(((delayMs - elapsed + 999) / 1000).toInt())
        }
        return GuardDecision.Accept
    }

    fun onAccepted(nowMs: Long, state: GuardState) = GuardState(state.failures + 1, nowMs)

    /** 服务成功写出健康状态（前台服务运行且心跳成功）后清零，自动解除保护。 */
    fun onHealthy() = GuardState()

    fun protectionLabel(nowMs: Long, state: GuardState, maxFailures: Int = Cooldown.DEFAULT_MAX_FAILURES): String =
        when (evaluate(nowMs, state, maxFailures)) {
            GuardDecision.Protected -> "protected"
            is GuardDecision.InCooldown -> "cooldown"
            GuardDecision.Accept -> "none"
        }
}
