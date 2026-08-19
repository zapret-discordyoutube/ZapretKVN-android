package io.github.zapretkvn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuntimeOutboundItemTest {
    @Test
    fun `relay result is stored separately from ICMP measurement`() {
        val icmpSample = LatencySample(42, 10_000, "wifi")
        val item = RuntimeOutboundItem(
            tag = "proxy",
            type = "vless",
            endpoint = "vpn.example:443",
            icmp = LatencyProbeState.Success(icmpSample),
        ).withRelayHistory(
            testedAtEpochSeconds = 20,
            delayMillis = 84,
        )

        assertEquals(LatencyProbeState.Success(icmpSample), item.icmp)
        assertEquals(
            LatencyProbeState.Success(LatencySample(84, 20_000, null)),
            item.relay,
        )
    }

    @Test
    fun `missing sing box history clears only relay result`() {
        val item = RuntimeOutboundItem(
            tag = "proxy",
            type = "vless",
            endpoint = "vpn.example:443",
            icmp = LatencyProbeState.Success(LatencySample(42, 10_000, "wifi")),
            relay = LatencyProbeState.Success(LatencySample(84, 20_000, null)),
        ).withRelayHistory(
            testedAtEpochSeconds = 0,
            delayMillis = 0,
        )

        assertEquals(
            LatencyProbeState.Success(LatencySample(42, 10_000, "wifi")),
            item.icmp,
        )
        assertEquals(LatencyProbeState.NotTested, item.relay)
    }

    @Test
    fun `successful value becomes stale after five minutes or network change`() {
        val now = 1_000_000L
        val sample = LatencySample(42, now - LATENCY_FRESHNESS_MILLIS + 1, "wifi")
        val success = LatencyProbeState.Success(sample)

        assertSame(success, success.withFreshness(now, "wifi"))
        assertEquals(
            LatencyProbeState.Stale(sample),
            success.withFreshness(now + 1, "wifi"),
        )
        assertEquals(
            LatencyProbeState.Stale(sample),
            success.withFreshness(now, "cellular"),
        )
    }

    @Test
    fun `cancelling a running probe restores the previous sample`() {
        val sample = LatencySample(84, 20_000, "wifi")

        assertEquals(
            LatencyProbeState.Success(sample),
            LatencyProbeState.Running(sample).restoreAfterCancellation(),
        )
        assertEquals(
            LatencyProbeState.NotTested,
            LatencyProbeState.Running(null).restoreAfterCancellation(),
        )
    }
}
