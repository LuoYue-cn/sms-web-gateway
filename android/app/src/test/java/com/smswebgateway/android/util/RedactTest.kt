package com.smswebgateway.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {
    @Test fun phone() {
        assertEquals("138****8000", Redact.phone("13800138000"))
        assertEquals("+86****8000", Redact.phone("+8613800138000"))
        assertEquals("****", Redact.phone("1234"))
        assertEquals("-", Redact.phone(null))
    }

    @Test fun contentOnlyShowsLength() {
        assertEquals("<7字>", Redact.content("验证码1234"))
        assertFalse(Redact.content("secret").contains("secret"))
    }

    @Test fun tokenAndScrub() {
        assertEquals("****WXYZ", Redact.token("abcdefghWXYZ"))
        assertFalse(Redact.scrub("Authorization: Bearer abcdef123456789").contains("abcdef123456789"))
        assertFalse(Redact.scrub("Authorization: abcdef123456789").contains("abcdef123456789"))
        assertEquals("token=****", Redact.scrub("token=abcdef123456"))
        assertFalse(Redact.scrub("""{"pairing_token":"ABCDEF123456"}""").contains("ABCDEF123456"))
        assertEquals("plain text", Redact.scrub("plain text"))
        assertTrue(Redact.scrub("Bearer abcdef123456").startsWith("Bearer ****"))
    }
}
