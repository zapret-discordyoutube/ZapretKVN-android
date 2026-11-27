package io.github.zapretkvn.android.config

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The pause of the WARP entry after a start with it failed (owner 2026-11-27): when it ends and what ends it early. */
class WarpHopSuspensionTest {
    private var now = 1_000_000L

    @Before
    fun clock() = WarpHopSuspension.useClock { now }

    @After
    fun reset() = WarpHopSuspension.clear()

    @Test
    fun `no pause until one is set`() {
        assertFalse(WarpHopSuspension.active("wifi"))
        assertEquals(0L, WarpHopSuspension.remainingMillis())
    }

    @Test
    fun `the pause lasts ten minutes on the same network and ends by itself`() {
        WarpHopSuspension.suspend("mobile")
        assertTrue(WarpHopSuspension.active("mobile"))
        now += WarpHopSuspension.PAUSE_MILLIS - 1
        assertTrue(WarpHopSuspension.active("mobile"))
        now += 2
        assertFalse(WarpHopSuspension.active("mobile"))
        assertEquals(0L, WarpHopSuspension.remainingMillis())
    }

    @Test
    fun `another network ends the pause for good`() {
        WarpHopSuspension.suspend("mobile")
        assertFalse(WarpHopSuspension.active("wifi"))
        assertFalse("the pause must not come back when the first network returns", WarpHopSuspension.active("mobile"))
    }

    @Test
    fun `an unknown network keeps the pause and a new registration clears it`() {
        WarpHopSuspension.suspend("mobile")
        assertTrue(WarpHopSuspension.active(null))
        WarpHopSuspension.clear()
        assertFalse(WarpHopSuspension.active("mobile"))
    }
}
