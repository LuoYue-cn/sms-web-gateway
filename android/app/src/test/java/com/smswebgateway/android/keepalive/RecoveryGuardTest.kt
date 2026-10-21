package com.smswebgateway.android.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryGuardTest {
    @Test fun cooldownSequence() {
        assertEquals(0, Cooldown.delaySeconds(0))
        assertEquals(10, Cooldown.delaySeconds(1))
        assertEquals(30, Cooldown.delaySeconds(2))
        assertEquals(60, Cooldown.delaySeconds(3))
        assertEquals(300, Cooldown.delaySeconds(4))
        assertEquals(300, Cooldown.delaySeconds(50))
    }

    @Test fun firstAttemptAccepted() {
        assertEquals(GuardDecision.Accept, RecoveryGuard.evaluate(1_000_000, GuardState()))
    }

    @Test fun cooldownBlocksUntilElapsed() {
        val t0 = 1_000_000L
        val s1 = RecoveryGuard.onAccepted(t0, GuardState())
        val inside = RecoveryGuard.evaluate(t0 + 4_000, s1)
        assertTrue(inside is GuardDecision.InCooldown)
        assertEquals(6, (inside as GuardDecision.InCooldown).remainingSeconds)
        assertEquals(GuardDecision.Accept, RecoveryGuard.evaluate(t0 + 10_000, s1))
    }

    @Test fun cooldownGrowsWithFailures() {
        var s = GuardState()
        var t = 0L
        val expected = listOf(10, 30, 60, 300)
        for (e in expected) {
            s = RecoveryGuard.onAccepted(t, s)
            assertTrue(RecoveryGuard.evaluate(t + e * 1000L - 1, s) is GuardDecision.InCooldown)
            assertEquals(GuardDecision.Accept, RecoveryGuard.evaluate(t + e * 1000L, s))
            t += e * 1000L
        }
    }

    @Test fun protectedAfterMaxFailures() {
        var s = GuardState()
        repeat(Cooldown.DEFAULT_MAX_FAILURES) { s = RecoveryGuard.onAccepted(it * 1_000_000L, s) }
        assertEquals(GuardDecision.Protected, RecoveryGuard.evaluate(Long.MAX_VALUE / 2, s))
        assertEquals("protected", RecoveryGuard.protectionLabel(Long.MAX_VALUE / 2, s))
    }

    @Test fun healthyResetsProtection() {
        val reset = RecoveryGuard.onHealthy()
        assertEquals(GuardDecision.Accept, RecoveryGuard.evaluate(0, reset))
        assertEquals("none", RecoveryGuard.protectionLabel(0, reset))
    }

    @Test fun protectionLabelCooldown() {
        val s = RecoveryGuard.onAccepted(1000, GuardState())
        assertEquals("cooldown", RecoveryGuard.protectionLabel(2000, s))
    }
}
