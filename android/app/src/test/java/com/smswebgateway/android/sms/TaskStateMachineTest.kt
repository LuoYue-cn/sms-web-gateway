package com.smswebgateway.android.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {
    @Test fun pollDecisionNeverResendsProcessedTasks() {
        assertEquals(PollDecision.CLAIM, decideOnPoll(null))
        assertEquals(PollDecision.CLAIM, decideOnPoll(TaskStatus.CLAIMED))
        for (s in listOf("sending", "sent", "delivered", "failed", "unknown")) {
            assertEquals(s, PollDecision.SKIP_PROCESSED, decideOnPoll(s))
        }
    }

    @Test fun mayOnlySendWhenClaimedAndSendingReported() {
        assertTrue(maySendNow(TaskStatus.CLAIMED, true))
        assertFalse(maySendNow(TaskStatus.CLAIMED, false))
        assertFalse(maySendNow(TaskStatus.SENDING, true))
        assertFalse(maySendNow(TaskStatus.SENT, true))
        assertFalse(maySendNow(null, true))
    }

    @Test fun recoveryTurnsSendingIntoUnknownOnly() {
        assertEquals(TaskStatus.UNKNOWN, recoveryTarget(TaskStatus.SENDING))
        for (s in listOf("claimed", "sent", "delivered", "failed", "unknown")) assertNull(s, recoveryTarget(s))
    }

    @Test fun serverTransitions() {
        assertTrue(isValidServerTransition("claimed", "sending"))
        assertTrue(isValidServerTransition("claimed", "failed"))
        assertTrue(isValidServerTransition("claimed", "unknown"))
        assertTrue(isValidServerTransition("sending", "sent"))
        assertTrue(isValidServerTransition("sending", "failed"))
        assertTrue(isValidServerTransition("sending", "unknown"))
        assertTrue(isValidServerTransition("sent", "delivered"))
        assertTrue(isValidServerTransition("sent", "failed"))
        assertTrue(isValidServerTransition("sent", "sent")) // 重复上报幂等
        assertFalse(isValidServerTransition("claimed", "sent"))
        assertFalse(isValidServerTransition("claimed", "delivered"))
        assertFalse(isValidServerTransition("sending", "delivered"))
        assertFalse(isValidServerTransition("unknown", "sent"))
        assertFalse(isValidServerTransition("failed", "sent"))
        assertFalse(isValidServerTransition("delivered", "failed"))
    }

    @Test fun reportStepsAlwaysLegal() {
        assertEquals("sent", nextReportStep("sending", "sent"))
        assertEquals("sent", nextReportStep("sending", "delivered"))
        assertEquals("delivered", nextReportStep("sent", "delivered"))
        assertEquals("failed", nextReportStep("sending", "failed"))
        assertEquals("failed", nextReportStep("claimed", "failed"))
        assertEquals("failed", nextReportStep("sent", "failed"))
        assertEquals("unknown", nextReportStep("sending", "unknown"))
        assertEquals("unknown", nextReportStep("claimed", "unknown"))
        assertNull(nextReportStep("sent", "sent"))
        assertNull(nextReportStep("unknown", "sent"))
        // 不会从 claimed 直接跳到 sent
        assertNull(nextReportStep("claimed", "sent"))
    }

    @Test fun aggregateMultipart() {
        // 3 段：只回来 2 个 sent，不下结论
        assertEquals(TaskStatus.SENDING, aggregateParts("sending", 3, 0b011, 0, 0, 0).status)
        // 全部 sent
        assertEquals(TaskStatus.SENT, aggregateParts("sending", 3, 0b111, 0, 0, 0).status)
        // 全部 sent + 全部 delivered
        assertEquals(TaskStatus.DELIVERED, aggregateParts("sent", 3, 0b111, 0, 0b111, 0).status)
        // 部分 delivered 保持 sent
        assertEquals(TaskStatus.SENT, aggregateParts("sent", 3, 0b111, 0, 0b011, 0).status)
        // 有失败段，且全部 sent 回调已到齐 -> failed(partial)
        val f = aggregateParts("sending", 3, 0b011, 0b100, 0, 0)
        assertEquals(TaskStatus.FAILED, f.status)
        assertTrue(f.partial)
        assertEquals("send_failed", f.errorCode)
        // 全部失败 -> failed 非 partial
        val all = aggregateParts("sending", 2, 0, 0b11, 0, 0)
        assertEquals(TaskStatus.FAILED, all.status)
        assertFalse(all.partial)
        // 回执失败
        val d = aggregateParts("sent", 1, 0b1, 0, 0, 0b1)
        assertEquals(TaskStatus.FAILED, d.status)
        assertEquals("delivery_failed", d.errorCode)
        // 已终态不再变化
        assertEquals(TaskStatus.UNKNOWN, aggregateParts("unknown", 1, 0b1, 0, 0, 0).status)
        assertEquals(TaskStatus.FAILED, aggregateParts("failed", 1, 0b1, 0, 0, 0).status)
    }

    @Test fun singlePartAggregate() {
        assertEquals(TaskStatus.SENT, aggregateParts("sending", 1, 0b1, 0, 0, 0).status)
        assertEquals(TaskStatus.FAILED, aggregateParts("sending", 1, 0, 0b1, 0, 0).status)
        assertEquals(TaskStatus.SENDING, aggregateParts("sending", 1, 0, 0, 0, 0).status)
    }

    @Test fun errorCodes() {
        assertEquals("generic_failure", errorCodeForSentResult(1))
        assertEquals("radio_off", errorCodeForSentResult(2))
        assertEquals("no_service", errorCodeForSentResult(4))
        assertEquals("error_999", errorCodeForSentResult(999))
    }
}
