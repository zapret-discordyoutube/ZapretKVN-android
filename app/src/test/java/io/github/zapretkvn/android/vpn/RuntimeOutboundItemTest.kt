package io.github.zapretkvn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeOutboundItemTest {
    @Test
    fun `relay result is stored separately from ICMP measurement`() {
        val item = RuntimeOutboundItem(
            tag = "proxy",
            type = "vless",
            endpoint = "vpn.example:443",
            pingMillis = 42,
            pingMeasuredAtEpochSeconds = 10,
        ).withRelayTestResult(
            testedAtEpochSeconds = 20,
            delayMillis = 84,
        )

        assertEquals(42, item.pingMillis)
        assertEquals(10L, item.pingMeasuredAtEpochSeconds)
        assertEquals(84, item.relayDelayMillis)
        assertEquals(20L, item.relayTestedAtEpochSeconds)
    }

    @Test
    fun `missing sing box history clears only relay result`() {
        val item = RuntimeOutboundItem(
            tag = "proxy",
            type = "vless",
            endpoint = "vpn.example:443",
            pingMillis = 42,
            pingMeasuredAtEpochSeconds = 10,
            relayDelayMillis = 84,
            relayTestedAtEpochSeconds = 20,
        ).withRelayTestResult(
            testedAtEpochSeconds = 0,
            delayMillis = 0,
        )

        assertEquals(42, item.pingMillis)
        assertEquals(10L, item.pingMeasuredAtEpochSeconds)
        assertNull(item.relayDelayMillis)
        assertNull(item.relayTestedAtEpochSeconds)
    }
}
