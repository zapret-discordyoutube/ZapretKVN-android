package io.github.zapretkvn.android.vpn.runtime

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateDnsRoutesTest {
    @Test
    fun `strict resolver name and addresses are normalized`() = runBlocking {
        val route = PrivateDnsRoutes.forStrict(" Dns.Example. ") { host ->
            assertEquals("dns.example", host)
            listOf("203.0.113.53", "203.0.113.53", "", "2001:db8::53")
        }
        assertEquals("dns.example", route?.hostname)
        assertEquals(listOf("203.0.113.53", "2001:db8::53"), route?.addresses)
    }

    @Test
    fun `no strict name means no route`() = runBlocking {
        assertNull(PrivateDnsRoutes.forStrict(null) { error("must not resolve") })
        assertNull(PrivateDnsRoutes.forStrict("  ") { error("must not resolve") })
    }

    @Test
    fun `failed or hanging lookup keeps the route by name and does not delay start`() = runBlocking {
        val failed = PrivateDnsRoutes.forStrict("dns.example") { throw IOException("no answer") }
        assertEquals(emptyList<String>(), failed?.addresses)

        val started = System.nanoTime()
        val hanging = PrivateDnsRoutes.forStrict("dns.example", timeoutMillis = 200) {
            delay(10_000)
            listOf("203.0.113.53")
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals("dns.example", hanging?.hostname)
        assertEquals(emptyList<String>(), hanging?.addresses)
        assertTrue("lookup must be bounded, took $elapsedMillis ms", elapsedMillis < 2_000)
    }
}
