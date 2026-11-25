package io.github.zapretkvn.clientreports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WARP entry hop's facts in reports (owner 2026-11-25). Same shape as Windows `tests/test_client_report_warp.py`:
 * settings.warp_entry (bool), probes warp_register and warp_tunnel at the end of the order, details warp_retries.
 * Everything stays out until vpnbot admits it ([ReportChecks.warpLive]): a report with an unknown probe is refused whole.
 * The facts themselves (real registration texts) are tested next to their producer, `WarpAccountRegistrationTest`.
 */
class WarpReportFactsTest {
    private val now = 1_795_000_000_000L
    private val app = AppIdentity("zapret-kvn", "0.6.55", "android")
    private val installId = "0123456789abcdef0123456789abcdef"

    @After
    fun restoreTheGate() {
        ReportChecks.warpLive = false
    }

    private fun failed(details: AttemptDetails, checks: Map<String, ReportCheck>) = AttemptRecord(
        "hipnl", "vless", ReportOutcome.Failed, "", "", 4_200, now - 1_000, "tok", details = details.cleaned(),
        reportId = "ab".repeat(16), carrier = "tcp", coreCode = "TARGET_NETWORK_TIMEOUT", checks = ReportChecks.ordered(checks),
    )

    private fun body(record: AttemptRecord): JsonObject =
        ((Json.parseToJsonElement(ClientReportJson.batch(installId, app, listOf(record), "direct")) as JsonObject)["reports"] as JsonArray)
            .single() as JsonObject

    private val asked = AttemptDetails(settings = ReportSettings(mode = "tun", warpEntry = true), warpRetries = 2)
    private val probes = mapOf(
        "warp_tunnel" to ReportCheck("timeout", 2_300),
        "warp_register" to ReportCheck("reset", 640),
    )

    @Test
    fun nothingOfTheHopIsSentWhileVpnbotHasNotAdmittedIt() {
        assertFalse(ReportChecks.warpLive)
        assertTrue(ReportChecks.warpChecks(true, ReportCheck("reset", 640), ReportCheck("timeout", 2_300)).isEmpty())
        assertFalse("warp_tunnel" in ReportChecks.PROBES)
        val sent = body(failed(asked, probes))
        assertFalse("checks" in sent)
        assertFalse("warp_retries" in sent)
        assertEquals(setOf("mode"), (sent["settings"] as JsonObject).keys)
    }

    @Test
    fun theProbesStandLastInTheAgreedOrderOnceLive() {
        ReportChecks.warpLive = true
        assertEquals(listOf("warp_register", "warp_tunnel"), ReportChecks.PROBES.takeLast(2))
        val sent = body(failed(asked, probes))
        assertEquals(listOf("warp_register", "warp_tunnel"), (sent["checks"] as JsonObject).keys.toList())
        assertEquals("reset", ((sent["checks"] as JsonObject)["warp_register"] as JsonObject)["r"]!!.jsonPrimitive.content)
        assertEquals("2", sent["warp_retries"]!!.jsonPrimitive.content)
        assertEquals("true", ((sent["settings"] as JsonObject)["warp_entry"])!!.jsonPrimitive.content)
    }

    @Test
    fun aStepThatDidNotRunSendsNoProbeAndAnOffHopSendsNothing() {
        ReportChecks.warpLive = true
        // The account file already existed: no registration ran, so no warp_register at all (not «unknown»).
        assertEquals(listOf("warp_tunnel"), ReportChecks.warpChecks(true, null, ReportCheck("answered", 1_900)).keys.toList())
        assertTrue(ReportChecks.warpChecks(false, ReportCheck("reset", 640), ReportCheck("timeout", 2_300)).isEmpty())
        assertEquals(null, AttemptDetails(warpRetries = 101).cleaned().warpRetries)
        assertEquals(100, AttemptDetails(warpRetries = 100).cleaned().warpRetries)
    }

    @Test
    fun theQueueLineKeepsTheHopFields() {
        ReportChecks.warpLive = true
        val record = failed(asked, probes)
        val again = ClientReportJson.parseQueueLine(ClientReportJson.queueLine(record))
        assertEquals(record, again)
        assertNull(AttemptDetails().warpRetries)
    }
}
