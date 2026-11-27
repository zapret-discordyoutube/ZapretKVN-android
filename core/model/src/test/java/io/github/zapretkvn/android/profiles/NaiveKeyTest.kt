package io.github.zapretkvn.android.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract vectors of the bot (`vpnbot_contracts/tests/test_naive_link.py`, sent by its session 2026-11-27): the
 * producer's own accepted and refused lines. The live run with a real key is `NaiveRealKeyTest` in :app.
 */
class NaiveKeyTest {
    private val secret = "Q2xpZW50U2VjcmV0MDEyMzQ1Njc4OQ"
    private val contract = "naive+https://k-7f3a:$secret@q417.ru1.example.com:443?insecure-concurrency=2" +
        "#%F0%9F%87%A9%F0%9F%87%AA%20Germany"

    @Test
    fun `the contract line parses`() {
        val key = NaiveKey.parse(contract)
        assertEquals("k-7f3a", key.clientId)
        assertEquals(secret, key.secret)
        assertEquals("q417.ru1.example.com", key.host)
        assertEquals(443, key.port)
        assertEquals(2, key.concurrency)
        assertEquals("🇩🇪 Germany", key.name)
        assertEquals("https://k-7f3a:$secret@q417.ru1.example.com:443", key.proxyUrl)
    }

    @Test
    fun `port and concurrency default`() {
        val key = NaiveKey.parse("naive+https://a:$secret@h.example.com#n")
        assertEquals(443, key.port)
        assertEquals(1, key.concurrency)
        assertEquals(1, NaiveKey.parse("naive+https://a:$secret@h.example.com:443?insecure-concurrency=1#n").concurrency)
    }

    @Test
    fun `what the bot refuses is refused`() {
        val refused = listOf(
            "naive+https://a:$secret@192.0.2.7:443#n",
            "naive+https://a:$secret@localhost:443#n",
            "naive+https://a:$secret@Q417.example.com:443#n",
            "naive+https://a:$secret@[2001:db8::1]:443#n",
            "naive+https://a:short@h.example.com#n",
            "naive+https://k%207f3a:$secret@h.example.com#n",
            "naive+https://:$secret@h.example.com#n",
            "naive+https://a:$secret@h.example.com?padding=true#n",
            "naive+https://a:$secret@h.example.com?insecure-concurrency=2&insecure-concurrency=3#n",
            "naive+https://a:$secret@h.example.com?insecure-concurrency=0#n",
            "naive+https://a:$secret@h.example.com?insecure-concurrency=100#n",
            "naive+https://a:$secret@h.example.com/path#n",
            "naive+https://a:$secret@h.example.com#",
            "https://a:$secret@h.example.com#n",
            "naive+https://a:$secret@h.example.com:99999#n",
            "naive+https://a:$secret@h.example.com #n",
        )
        for (line in refused) {
            assertThrows(line.replace(secret, "<secret>"), NaiveKeyException::class.java) { NaiveKey.parse(line) }
        }
    }

    @Test
    fun `errors never carry the secret`() {
        val error = assertThrows(NaiveKeyException::class.java) { NaiveKey.parse("naive+https://a:$secret@192.0.2.7:443#n") }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test
    fun `a concurrency above the upstream advice is honoured with a warning`() {
        val key = NaiveKey.parse("naive+https://a:$secret@h.example.com?insecure-concurrency=9#n")
        assertEquals(9, key.concurrency)
        assertNotNull(key.concurrencyWarning)
        assertNull(NaiveKey.parse("naive+https://a:$secret@h.example.com?insecure-concurrency=4#n").concurrencyWarning)
    }

    @Test
    fun `the outbound carries the key's credentials and concurrency, never the name`() {
        val json = OutboundSpec.Naive("h.example.com", 443, "a", secret, 3).toSingBoxJson().toString()
        assertTrue(json.contains("\"type\":\"naive\""))
        assertTrue(json.contains("\"insecure_concurrency\":3"))
        assertFalse(OutboundSpec.Naive("h.example.com", 443, "a", secret, 1).toSingBoxJson().toString().contains("insecure_concurrency"))
    }
}
