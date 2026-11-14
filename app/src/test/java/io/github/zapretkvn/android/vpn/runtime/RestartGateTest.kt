package io.github.zapretkvn.android.vpn.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartGateTest {
    @Test
    fun `a restart for an older generation or during a stop is stale`() {
        assertTrue(RestartGate.ignore(expectedGeneration = 3, currentGeneration = 4, stopInProgress = false, stateIsStopping = false))
        assertTrue(RestartGate.ignore(expectedGeneration = 4, currentGeneration = 4, stopInProgress = true, stateIsStopping = false))
        assertFalse(RestartGate.ignore(expectedGeneration = 4, currentGeneration = 4, stopInProgress = false, stateIsStopping = false))
    }

    @Test
    fun `a restart without a generation queued behind a stop does not bring the VPN back`() {
        assertTrue(RestartGate.ignore(null, currentGeneration = 5, stopInProgress = true, stateIsStopping = true))
    }

    @Test
    fun `after the stop finished a restart without a generation is a deliberate start`() {
        // stopInProgress stays true until the next start, so the state decides.
        assertFalse(RestartGate.ignore(null, currentGeneration = 5, stopInProgress = true, stateIsStopping = false))
        assertFalse(RestartGate.ignore(null, currentGeneration = 5, stopInProgress = false, stateIsStopping = false))
    }
}
