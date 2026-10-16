package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.vpn.LatencyFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class FailoverHintTtlTest {
    private val now = 2_000_000_000L

    private fun failedAt(at: Long) = PersistedServerLatency(
        fingerprint = "f",
        relay = PersistedProbeResult(millis = null, failure = LatencyFailure.NoResponse, measuredAtEpochMillis = at),
    )

    @Test
    fun `recent failure excludes the server from failover`() {
        assertEquals(true, failedAt(now - 60_000).hint(now)?.failed)
    }

    @Test
    fun `old failure no longer excludes the server forever`() {
        assertEquals(false, failedAt(now - FAILOVER_FAILURE_TTL_MILLIS - 1).hint(now)?.failed)
    }
}
