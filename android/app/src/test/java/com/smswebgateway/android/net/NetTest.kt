package com.smswebgateway.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetTest {
    @Test fun backoffSequenceCapsAt60s() {
        assertEquals(1_000, Backoff.delayMs(0))
        assertEquals(2_000, Backoff.delayMs(1))
        assertEquals(4_000, Backoff.delayMs(2))
        assertEquals(8_000, Backoff.delayMs(3))
        assertEquals(32_000, Backoff.delayMs(5))
        assertEquals(60_000, Backoff.delayMs(6))
        assertEquals(60_000, Backoff.delayMs(100))
    }

    @Test fun serverUrlHttpsAlwaysOk() {
        val r = ServerUrl.parse("https://sms.example.com/", allowCleartextLocal = false)
        assertEquals("https://sms.example.com", (r as ServerUrlResult.Ok).baseUrl)
        val r2 = ServerUrl.parse("https://sms.example.com:8443/app/api/v1/", false)
        assertEquals("https://sms.example.com:8443/app", (r2 as ServerUrlResult.Ok).baseUrl)
    }

    @Test fun releaseRejectsHttp() {
        assertTrue(ServerUrl.parse("http://127.0.0.1:8080", false) is ServerUrlResult.Error)
        assertTrue(ServerUrl.parse("http://sms.example.com", false) is ServerUrlResult.Error)
    }

    @Test fun debugAllowsLocalHttpOnly() {
        val ok = ServerUrl.parse("http://127.0.0.1:8080", true)
        assertEquals("http://127.0.0.1:8080", (ok as ServerUrlResult.Ok).baseUrl)
        assertTrue(ServerUrl.parse("http://192.168.1.20:8080", true) is ServerUrlResult.Ok)
        assertTrue(ServerUrl.parse("http://localhost:8080", true) is ServerUrlResult.Ok)
        assertTrue(ServerUrl.parse("http://sms.example.com", true) is ServerUrlResult.Error)
        assertTrue(ServerUrl.parse("http://8.8.8.8", true) is ServerUrlResult.Error)
    }

    @Test fun schemeLessInputDefaultsSafely() {
        assertEquals("https://sms.example.com", (ServerUrl.parse("sms.example.com", false) as ServerUrlResult.Ok).baseUrl)
        assertEquals("http://127.0.0.1:8080", (ServerUrl.parse("127.0.0.1:8080", true) as ServerUrlResult.Ok).baseUrl)
        assertEquals("https://127.0.0.1:8080", (ServerUrl.parse("127.0.0.1:8080", false) as ServerUrlResult.Ok).baseUrl)
    }

    @Test fun rejectsBadInput() {
        assertTrue(ServerUrl.parse("", true) is ServerUrlResult.Error)
        assertTrue(ServerUrl.parse("ftp://x.com", true) is ServerUrlResult.Error)
        assertTrue(ServerUrl.parse("https://user:pw@x.com", true) is ServerUrlResult.Error)
    }

    @Test fun localHostClassification() {
        assertTrue(ServerUrl.isLocalHost("10.1.2.3"))
        assertTrue(ServerUrl.isLocalHost("172.16.0.1"))
        assertTrue(ServerUrl.isLocalHost("172.31.255.255"))
        assertFalse(ServerUrl.isLocalHost("172.32.0.1"))
        assertFalse(ServerUrl.isLocalHost("1.1.1.1"))
        assertTrue(ServerUrl.isLocalHost("[::1]"))
        assertTrue(ServerUrl.isLocalHost("nas.local"))
    }

    @Test fun pairingPayload() {
        val p = PairingPayloadParser.parse("""{"server_url":"https://a.b","pairing_token":"TOK"}""")
        assertNotNull(p)
        assertEquals("https://a.b", p!!.serverUrl)
        assertEquals("TOK", p.pairingToken)
        assertNull(PairingPayloadParser.parse("not json"))
        assertNull(PairingPayloadParser.parse("""{"server_url":"https://a.b"}"""))
        assertNull(PairingPayloadParser.parse("""{"server_url":"","pairing_token":"x"}"""))
    }
}
