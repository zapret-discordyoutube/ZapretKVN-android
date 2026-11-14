package io.github.zapretkvn.android.vpn.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreSelectionGateTest {
    private var now = 1_000_000L
    private val gate = PreSelectionGate { now }

    @Test
    fun `a server nobody refused is tested first`() {
        assertTrue(gate.shouldCheck("a"))
    }

    @Test
    fun `only a timeout refuses the pick, an unfinished test lets it go on`() {
        assertFalse(gate.refuses("a", RelayVerdict.Alive))
        assertFalse(gate.refuses("a", RelayVerdict.Unknown))
        assertTrue(gate.refuses("a", RelayVerdict.NoResponse))
    }

    @Test
    fun `a refused server picked again within two minutes skips the test, later it is tested again`() {
        assertTrue(gate.refuses("a", RelayVerdict.NoResponse))
        now += 30_000
        assertFalse(gate.shouldCheck("a"))
        assertTrue(gate.shouldCheck("b"))
        now += PreSelectionGate.OVERRIDE_MILLIS
        assertTrue(gate.shouldCheck("a"))
    }

    @Test
    fun `an answer after a refusal clears it`() {
        gate.refuses("a", RelayVerdict.NoResponse)
        gate.refuses("a", RelayVerdict.Alive)
        assertTrue(gate.shouldCheck("a"))
    }
}
