package io.github.zapretkvn.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** REAL core lines (emulator run with real WARP, owner's phone diagnostic of 2026-11-26). */
class WarpHopFactsCoreLinesTest {
    private val retry0 = "WARN[0003] endpoint/warp[kvn-warp-entry]: WARP handshake did not answer at •••:500, trying the next address"
    private val answered = "INFO[0003] endpoint/warp[kvn-warp-entry]: WARP tunnel answered at •••:2408 (attempt 2)"
    private val first = "DEBUG[0001] endpoint/warp[kvn-warp-entry]: peer(bmXO…fgyo) - received handshake response"
    private val rekey = "DEBUG[0136] endpoint/warp[kvn-warp-entry]: peer(bmXO…fgyo) - received handshake response"

    @Test
    fun theTunnelThatAnswersIsAnsweredWithTheCoresTimeAndTheRetriesBeforeIt() {
        WarpHopFacts.record(true, null)
        WarpHopFacts.noteCoreLines(listOf(retry0, answered, rekey))
        val facts = WarpHopFacts.current()
        assertEquals(1, facts.retries)
        assertEquals(WarpHopFacts.Probe("answered", 3000), facts.tunnel)
    }

    @Test
    fun aPlainFirstHandshakeIsAnAnswerWithNoRetries() {
        WarpHopFacts.record(true, null)
        WarpHopFacts.noteCoreLines(listOf(first))
        assertEquals(0, WarpHopFacts.current().retries)
        assertEquals(WarpHopFacts.Probe("answered", 1000), WarpHopFacts.current().tunnel)
    }

    @Test
    fun fourUnansweredHandshakesAreATimeoutAndNothingIsSaidBefore() {
        WarpHopFacts.record(true, null)
        WarpHopFacts.noteCoreLines(listOf(retry0, retry0, retry0))
        assertNull(WarpHopFacts.current().tunnel)
        WarpHopFacts.noteCoreLines(listOf(retry0))
        assertEquals("timeout", WarpHopFacts.current().tunnel?.result)
        assertEquals(4, WarpHopFacts.current().retries)
    }

    @Test
    fun aRekeyMinutesLaterAndAnOffSettingChangeNothing() {
        WarpHopFacts.record(true, null)
        WarpHopFacts.noteCoreLines(listOf(rekey))
        assertNull(WarpHopFacts.current().tunnel)
        WarpHopFacts.record(false, null)
        WarpHopFacts.noteCoreLines(listOf(first))
        assertNull(WarpHopFacts.current().retries)
        assertNull(WarpHopFacts.current().tunnel)
    }
}
