package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.clientreports.ReportChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoDataProbeTest {
    @Test
    fun aSilentNameLookupIsATimeoutAndAQuickOneProvesNothing() {
        assertEquals(ReportChecks.TIMEOUT, NoDataProbe.lookupResult(NoDataProbe.Stage.LOOKUP, 20_892))
        assertEquals(ReportChecks.TIMEOUT, NoDataProbe.lookupResult(NoDataProbe.Stage.LOOKUP, NoDataProbe.LOOKUP_SILENT_MILLIS))
        assertEquals(ReportChecks.UNKNOWN, NoDataProbe.lookupResult(NoDataProbe.Stage.LOOKUP, 40))
        assertNull(NoDataProbe.lookupResult(NoDataProbe.Stage.CONNECT, 20_892))
        assertNull(NoDataProbe.lookupResult(NoDataProbe.Stage.EXCHANGE, 20_892))
    }

    @Test
    fun throughTheTunnelADropIsNeverARefusal() {
        // The TUN completes the connect locally: a RST there is the core dropping the flow.
        assertTrue(NoDataProbe.connectedFor(NoDataProbe.Stage.CONNECT, tunnel = true))
        assertTrue(NoDataProbe.connectedFor(NoDataProbe.Stage.EXCHANGE, tunnel = true))
        // Directly a RST to the SYN is a refusal, after the connect a reset.
        assertFalse(NoDataProbe.connectedFor(NoDataProbe.Stage.CONNECT, tunnel = false))
        assertTrue(NoDataProbe.connectedFor(NoDataProbe.Stage.EXCHANGE, tunnel = false))
    }
}
