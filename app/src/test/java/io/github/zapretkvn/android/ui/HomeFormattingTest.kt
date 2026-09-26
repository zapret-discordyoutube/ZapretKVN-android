package io.github.zapretkvn.android.ui

import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFormattingTest {
    @Test
    fun `bytes and session duration remain compact`() {
        assertEquals("0 Б", formatBytes(0))
        assertEquals("1.0 КБ", formatBytes(1_024))
        assertEquals("1.5 МБ", formatBytes(1_572_864))
        assertEquals("00:00", formatDuration(0))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun `ping switches to compact seconds above one second`() {
        assertEquals("—", formatPing(null))
        assertEquals("999 мс", formatPing(999))
        assertEquals("1 с", formatPing(1_000))
        assertEquals("1,98 с", formatPing(1_978))
        assertEquals("2 с", formatPing(1_999))
    }

    @Test
    fun `saved ping shows its age and turns muted after a day`() {
        val now = 10L * 24 * 3_600_000
        fun saved(ageMillis: Long) = LatencyProbeState.Success(
            LatencySample(42, now - ageMillis, networkIdentity = null),
        )
        assertEquals("42 мс", formatLatency(saved(30_000), now))
        assertEquals("42 мс · 12 мин назад", formatLatency(saved(12 * 60_000), now))
        assertEquals("42 мс · 3 ч назад", formatLatency(saved(3 * 3_600_000), now))
        assertEquals("42 мс · 2 д назад", formatLatency(saved(49 * 3_600_000), now))
        assertFalse(isLatencyOld(saved(23 * 3_600_000), now))
        assertTrue(isLatencyOld(saved(24 * 3_600_000), now))

        // Fresh sample from another Android network keeps the old wording.
        val otherNetwork = LatencyProbeState.Stale(LatencySample(42, now - 1_000, "wifi:1"))
        assertEquals("Устарело · 42 мс", formatLatency(otherNetwork, now))

        val savedFailure = LatencyProbeState.Failed(
            LatencyFailure.NoResponse,
            previous = LatencySample(42, now - 4 * 3_600_000, null),
            failedAtEpochMillis = now - 3 * 3_600_000,
        )
        assertEquals("Нет ответа · 3 ч назад", formatLatency(savedFailure, now))
        assertEquals(
            "Нет ответа · было 42 мс",
            formatLatency(LatencyProbeState.Failed(LatencyFailure.NoResponse, LatencySample(42, now, null)), now),
        )
        assertEquals("только что", formatAge(59_000))
    }
}
