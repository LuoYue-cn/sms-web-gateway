package com.smswebgateway.android.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsDirectionTest {
    @Test fun inboxIsIncoming() = assertEquals(Direction.INCOMING, directionForType(1))

    @Test fun sentLikeTypesAreOutgoing() {
        for (t in listOf(2, 4, 5, 6)) assertEquals("type=$t", Direction.OUTGOING, directionForType(t))
    }

    @Test fun draftAndUnknownAreSkipped() {
        assertNull(directionForType(3))
        assertNull(directionForType(0))
        assertNull(directionForType(99))
    }

    @Test fun wireNames() {
        assertEquals("incoming", Direction.INCOMING.wire)
        assertEquals("outgoing", Direction.OUTGOING.wire)
    }

    @Test fun slotMapping() {
        val m = mapOf(3 to 0, 7 to 1)
        assertEquals(0, slotForSubscription(3, m))
        assertEquals(1, slotForSubscription(7, m))
        assertNull(slotForSubscription(-1, m))
    }
}
