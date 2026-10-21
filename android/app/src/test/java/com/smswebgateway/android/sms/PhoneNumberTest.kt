package com.smswebgateway.android.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberTest {
    @Test fun normalizeStripsSeparatorsAndKeepsPlus() {
        assertEquals("+8613800138000", PhoneNumber.normalize("+86 138-0013 8000"))
        assertEquals("13800138000", PhoneNumber.normalize("(138) 0013.8000"))
    }

    @Test fun sendAcceptsDomesticAndInternational() {
        assertEquals("13800138000", PhoneNumber.normalizeForSend("138 0013 8000"))
        assertEquals("+8613800138000", PhoneNumber.normalizeForSend("+86-138-0013-8000"))
        assertEquals("+819012345678", PhoneNumber.normalizeForSend("+81 90 1234 5678"))
        assertEquals("10086", PhoneNumber.normalizeForSend("10086"))
    }

    @Test fun sendRejectsInvalid() {
        assertNull(PhoneNumber.normalizeForSend(""))
        assertNull(PhoneNumber.normalizeForSend("12"))
        assertNull(PhoneNumber.normalizeForSend("ICBC"))
        assertNull(PhoneNumber.normalizeForSend("+"))
        assertNull(PhoneNumber.normalizeForSend("1380013800a"))
        assertNull(PhoneNumber.normalizeForSend("+86+138"))
        assertNull(PhoneNumber.normalizeForSend("123456789012345678901")) // 21 位
        assertTrue(PhoneNumber.isValidForSend("12345678901234567890")) // 20 位
    }

    @Test fun sendRejectsMultipleRecipients() {
        assertNull(PhoneNumber.normalizeForSend("13800138000,13900139000"))
        assertNull(PhoneNumber.normalizeForSend("13800138000;13900139000"))
        assertFalse(PhoneNumber.isValidForSend("13800138000 13900139000x"))
    }

    @Test fun receiveKeepsAlphanumericSender() {
        assertEquals("ICBC", PhoneNumber.normalizeForReceive("ICBC"))
        assertEquals("10086", PhoneNumber.normalizeForReceive(" 10086 "))
        assertEquals("+8613800138000", PhoneNumber.normalizeForReceive("+86 138 0013 8000"))
        assertEquals("unknown", PhoneNumber.normalizeForReceive(null))
        assertEquals("unknown", PhoneNumber.normalizeForReceive("   "))
        assertEquals(32, PhoneNumber.normalizeForReceive("A".repeat(50)).length)
    }
}
