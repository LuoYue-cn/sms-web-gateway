package com.smswebgateway.android.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimNumberTest {
    @Test fun normalizesCommonFormats() {
        assertEquals("+8613800138000", SimHelper.normalizeNumber("+86 138-0013-8000"))
        assertEquals("13800138000", SimHelper.normalizeNumber("(138) 0013.8000"))
        assertEquals("+819012345678", SimHelper.normalizeNumber("+81 90 1234 5678"))
    }

    @Test fun missingOrGarbageMeansNoNumber() {
        assertNull(SimHelper.normalizeNumber(null))
        assertNull(SimHelper.normalizeNumber(""))
        assertNull(SimHelper.normalizeNumber("   "))
        assertNull(SimHelper.normalizeNumber("12"))
        assertNull(SimHelper.normalizeNumber("unknown"))
        assertNull(SimHelper.normalizeNumber("13800138000,13900139000"))
    }
}
