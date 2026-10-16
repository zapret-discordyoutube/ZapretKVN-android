package io.github.zapretkvn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyVerdictTest {
    private val now = 1_000_000_000L
    private val previous = LatencySample(84, now - 3_600_000, networkIdentity = null)

    @Test
    fun `fresh failure after connect stays a verdict`() {
        val failed = LatencyProbeState.Failed(LatencyFailure.NoResponse, previous, now - 60_000)
        assertEquals(failed, failed.currentVerdict(now, notBeforeEpochMillis = now - 120_000))
    }

    @Test
    fun `failure older than the current connection is superseded`() {
        val failed = LatencyProbeState.Failed(LatencyFailure.NoResponse, previous, now - 60_000)
        assertEquals(LatencyProbeState.Stale(previous), failed.currentVerdict(now, notBeforeEpochMillis = now - 30_000))
    }

    @Test
    fun `old failure without previous sample becomes not tested`() {
        val failed = LatencyProbeState.Failed(LatencyFailure.Failed, null, now - LATENCY_FRESHNESS_MILLIS - 1)
        assertEquals(LatencyProbeState.NotTested, failed.currentVerdict(now))
    }

    @Test
    fun `failure without time is not trusted as a verdict`() {
        val failed = LatencyProbeState.Failed(LatencyFailure.Failed, previous)
        assertEquals(LatencyProbeState.Stale(previous), failed.currentVerdict(now))
    }

    @Test
    fun `success is unchanged`() {
        val ok = LatencyProbeState.Success(previous)
        assertEquals(ok, ok.currentVerdict(now, now))
    }
}
