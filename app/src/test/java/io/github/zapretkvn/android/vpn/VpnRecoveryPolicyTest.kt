package io.github.zapretkvn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRecoveryPolicyTest {
    @Test
    fun `missing physical network waits for a network event instead of failing`() {
        assertEquals(
            VpnRecoveryDecision.AwaitNetwork,
            VpnRecoveryPolicy.decide(failureCode = "NET-101", attempt = 0),
        )
        assertEquals(
            VpnRecoveryDecision.AwaitNetwork,
            VpnRecoveryPolicy.decide(failureCode = "NET-101", attempt = 2),
        )
    }

    @Test
    fun `captive portal stays terminal because only the user can clear it`() {
        assertEquals(
            VpnRecoveryDecision.Terminal,
            VpnRecoveryPolicy.decide(failureCode = "NET-110", attempt = 0),
        )
    }

    @Test
    fun `transient bootstrap failures retry with growing backoff`() {
        assertEquals(
            VpnRecoveryDecision.RetryAfter(1_000L),
            VpnRecoveryPolicy.decide(failureCode = "NET-102", attempt = 0),
        )
        assertEquals(
            VpnRecoveryDecision.RetryAfter(2_000L),
            VpnRecoveryPolicy.decide(failureCode = "DNS-101", attempt = 1),
        )
        assertEquals(
            VpnRecoveryDecision.RetryAfter(4_000L),
            VpnRecoveryPolicy.decide(failureCode = "DNS-105", attempt = 2),
        )
    }

    @Test
    fun `backoff never exceeds the ceiling`() {
        assertEquals(VpnRecoveryPolicy.MAX_DELAY_MILLIS, VpnRecoveryPolicy.backoffMillis(10))
        assertEquals(VpnRecoveryPolicy.MAX_DELAY_MILLIS, VpnRecoveryPolicy.backoffMillis(64))
        assertTrue(VpnRecoveryPolicy.backoffMillis(0) >= VpnRecoveryPolicy.BASE_DELAY_MILLIS)
    }

    @Test
    fun `failures that need the user stay terminal`() {
        listOf("DNS-102", "DNS-110", "DNS-200", "VPN-120", "VPN-200", "NET-110", "").forEach { code ->
            assertEquals(
                "код $code не должен переподключаться сам",
                VpnRecoveryDecision.Terminal,
                VpnRecoveryPolicy.decide(failureCode = code, attempt = 0),
            )
        }
    }

    @Test
    fun `network change under a running attempt makes an uncoded failure transient`() {
        assertEquals(
            VpnRecoveryDecision.Terminal,
            VpnRecoveryPolicy.decide(
                failureCode = "",
                attempt = 0,
                networkChangedDuringAttempt = false,
            ),
        )
        assertEquals(
            VpnRecoveryDecision.RetryAfter(1_000L),
            VpnRecoveryPolicy.decide(
                failureCode = "",
                attempt = 0,
                networkChangedDuringAttempt = true,
            ),
        )
    }

    @Test
    fun `consecutive attempts on one network are bounded`() {
        assertEquals(
            VpnRecoveryDecision.Terminal,
            VpnRecoveryPolicy.decide(
                failureCode = "NET-101",
                attempt = VpnRecoveryPolicy.MAX_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `flapping networks cannot restart forever`() {
        // Счётчик подряд идущих попыток обнуляется на каждой новой сети,
        // поэтому серию ограничивает только общий потолок.
        assertEquals(
            VpnRecoveryDecision.Terminal,
            VpnRecoveryPolicy.decide(
                failureCode = "NET-101",
                attempt = 0,
                totalAttempts = VpnRecoveryPolicy.MAX_TOTAL_ATTEMPTS,
            ),
        )
    }
}
