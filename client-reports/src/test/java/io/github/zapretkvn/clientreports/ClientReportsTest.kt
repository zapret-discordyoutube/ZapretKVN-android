package io.github.zapretkvn.clientreports

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientReportsTest {
    private val now = 1_795_000_000_000L
    private val app = AppIdentity("zapret-kvn", "0.6.33", "android")
    private val installId = "0123456789abcdef0123456789abcdef"

    private fun record(
        outcome: ReportOutcome = ReportOutcome.Failed,
        step: String = "handshake",
        detail: String = "udp_no_answer",
        at: Long = now - 1_000,
        token: String = "",
    ) = AttemptRecord("hiphopgen", "hysteria2", outcome, step, detail, 4_200, at, token)

    private fun reports(body: String): List<JsonObject> =
        ((Json.parseToJsonElement(body) as JsonObject)["reports"] as JsonArray).map { it as JsonObject }

    @Test
    fun batchCarriesExactlyTheContractFields() {
        val body = ClientReportJson.batch(installId, app, listOf(record(token = "v1.abc.def")), "tunnel")
        val report = reports(body).single()
        assertEquals(
            setOf(
                "schema", "install_id", "app", "server_id", "product", "outcome", "failed_step", "detail",
                "elapsed_ms", "attempted_at", "sent_via", "network_token",
            ),
            report.keys,
        )
        assertEquals(setOf("name", "version", "platform"), (report["app"] as JsonObject).keys)
        assertEquals("1", report["schema"]!!.jsonPrimitive.content)
        assertEquals("handshake", report["failed_step"]!!.jsonPrimitive.content)
        assertEquals("tunnel", report["sent_via"]!!.jsonPrimitive.content)
        assertTrue(report["attempted_at"]!!.jsonPrimitive.content.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""")))
    }

    @Test
    fun connectedReportHasNoFailedStepNoTokenNoDetail() {
        val body = ClientReportJson.batch(
            installId,
            app,
            listOf(record(outcome = ReportOutcome.Connected, step = "", detail = "")),
            "direct",
        )
        val report = reports(body).single()
        assertFalse("failed_step" in report)
        assertFalse("network_token" in report)
        assertFalse("detail" in report)
        assertEquals("connected", report["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun invalidRecordsAreRejectedBeforeTheQueue() {
        assertFalse(ClientReportContract.isValid(record(step = "")))
        assertFalse(ClientReportContract.isValid(record(step = "dns")))
        assertFalse(ClientReportContract.isValid(record(outcome = ReportOutcome.Connected)))
        assertFalse(ClientReportContract.isValid(record().copy(serverId = "Hiphopgen")))
        assertFalse(ClientReportContract.isValid(record().copy(product = "vless_fragment")))
        assertFalse(ClientReportContract.isValid(record().copy(elapsedMs = 600_001)))
        assertFalse(ClientReportContract.isValid(record(detail = "Udp-No-Answer")))
        assertFalse(ClientReportContract.isValid(record(token = "v1.a b")))
        assertTrue(ClientReportContract.isValid(record()))
    }

    @Test
    fun freshnessFollowsTheIngestWindow() {
        assertTrue(ClientReportContract.isFresh(record(at = now - 6L * 24 * 3600 * 1000), now))
        assertFalse(ClientReportContract.isFresh(record(at = now - 7L * 24 * 3600 * 1000), now))
        assertFalse(ClientReportContract.isFresh(record(at = now + 11L * 60 * 1000), now))
    }

    @Test
    fun headerMapKeepsOnlyContractEntries() {
        val json = """
            {"🇳🇱 Velnor": {"server_id": "hipnl", "product": "vless"},
             "Hiphopgen": {"server_id": "hiphopgen", "product": "hysteria2"},
             "Bad id": {"server_id": "Hip NL", "product": "vless"},
             "Bad product": {"server_id": "x1", "product": "vless_fragment"},
             "Extra": "plain"}
        """.trimIndent()
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val map = ServerIdHeader.parse(value)
        assertEquals(
            mapOf("🇳🇱 Velnor" to ServerRef("hipnl", "vless"), "Hiphopgen" to ServerRef("hiphopgen", "hysteria2")),
            map,
        )
        assertTrue(ServerIdHeader.parse("not base64 !!").isEmpty())
        assertTrue(ServerIdHeader.parse(null).isEmpty())
        assertTrue(ServerIdHeader.parse("A".repeat(ServerIdHeader.MAX_VALUE_CHARS + 1)).isEmpty())
    }

    @Test
    fun outboundTypeLimitsTheProduct() {
        assertEquals(setOf("vless"), ServerIdHeader.productsForOutboundType("vless"))
        assertEquals(setOf("hysteria2"), ServerIdHeader.productsForOutboundType("hysteria2"))
        assertTrue(ServerIdHeader.productsForOutboundType("selector").isEmpty())
    }

    @Test
    fun classifierMapsCoreCodesOntoTheLadder() {
        fun step(facts: AttemptFacts) = AttemptClassifier.classify(facts)?.let { it.failedStep to it.detail }
        assertEquals("auth" to "auth_rejected", step(AttemptFacts(coreCode = "TARGET_AUTH_REJECTED")))
        assertEquals("handshake" to "tls_failed", step(AttemptFacts(coreCode = "TARGET_PIN_MISMATCH")))
        assertEquals("connect" to "connection_refused", step(AttemptFacts(coreCode = "TARGET_CONNECTION_REFUSED")))
        assertEquals(
            "handshake" to "udp_no_answer",
            step(AttemptFacts(coreCode = "TARGET_NETWORK_TIMEOUT", udpTransport = true, tcpPreflightFailed = true)),
        )
        assertEquals(
            "connect" to "tcp_timeout",
            step(AttemptFacts(coreCode = "TARGET_NETWORK_TIMEOUT", tcpPreflightFailed = true)),
        )
        // The owner's case 18–19.11: TCP connects, the TLS handshake gets no answer.
        assertEquals("handshake" to "handshake_timeout", step(AttemptFacts(coreCode = "TARGET_NETWORK_TIMEOUT")))
        assertEquals("handshake" to "connection_closed", step(AttemptFacts(coreCode = "TARGET_CONNECTION_CLOSED")))
        assertEquals("request" to "no_response", step(AttemptFacts(trafficFailed = true)))
        assertEquals("connect" to "tcp_timeout", step(AttemptFacts(trafficFailed = true, tcpPreflightFailed = true)))
    }

    @Test
    fun failuresThatAreNotAboutTheServerAreNotReported() {
        assertNull(AttemptClassifier.classify(AttemptFacts(coreCode = "LOCAL_BIND_COLLISION", trafficFailed = true)))
        assertNull(AttemptClassifier.classify(AttemptFacts(networkLost = true, trafficFailed = true)))
        // DNS through the tunnel failed with no core evidence: may be the DNS mode, not the server.
        assertNull(AttemptClassifier.classify(AttemptFacts()))
        assertEquals(ReportOutcome.Connected, AttemptClassifier.classify(AttemptFacts(succeeded = true))?.outcome)
        assertEquals(ReportOutcome.Cancelled, AttemptClassifier.classify(AttemptFacts(cancelled = true))?.outcome)
    }

    @Test
    fun queueKeepsOrderPrunesAndSurvivesRestart() {
        val dir = Files.createTempDirectory("reports").toFile()
        try {
            val file = File(dir, "queue.jsonl")
            val queue = ReportQueue(file, capacity = 3)
            val old = record(at = now - 8L * 24 * 3600 * 1000)
            val a = record(at = now - 3_000)
            val b = record(outcome = ReportOutcome.Connected, step = "", detail = "", at = now - 2_000)
            val c = record(at = now - 1_000, token = "v1.x.y")
            queue.add(old)
            queue.add(a)
            queue.add(record(step = "bogus"))
            queue.add(b)
            assertEquals(listOf(a, b), queue.pending(now))
            queue.add(c)
            queue.add(c.copy(elapsedMs = 1))
            assertEquals(listOf(b, c, c.copy(elapsedMs = 1)), ReportQueue(file, capacity = 3).pending(now))
            queue.remove(listOf(b, c))
            assertEquals(listOf(c.copy(elapsedMs = 1)), ReportQueue(file).pending(now))
            queue.remove(listOf(c.copy(elapsedMs = 1)))
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun tokenBelongsToItsNetworkAndAges() {
        val dir = Files.createTempDirectory("token").toFile()
        try {
            val file = File(dir, "network-token")
            val cache = NetworkTokenCache(file)
            assertTrue(cache.needsRefresh("wifi-1", now))
            assertFalse(cache.needsRefresh(null, now))
            cache.store(NetworkToken("v1.payload.mac", now, "wifi-1"))
            assertEquals("v1.payload.mac", NetworkTokenCache(file).current("wifi-1", now + 1_000))
            assertEquals("", cache.current("cell-2", now))
            assertTrue(cache.needsRefresh("cell-2", now))
            assertFalse(cache.needsRefresh("wifi-1", now + 3_600_000))
            assertTrue(cache.needsRefresh("wifi-1", now + NetworkTokenCache.REFRESH_AFTER_MILLIS))
            assertEquals("", cache.current("wifi-1", now + NetworkTokenCache.USABLE_MILLIS))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sendPolicyFollowsTheIngestAnswers() {
        assertEquals(SendAction.Remove, ReportSendPolicy.afterResponse(202))
        assertEquals(SendAction.Drop, ReportSendPolicy.afterResponse(400))
        assertEquals(SendAction.Drop, ReportSendPolicy.afterResponse(413))
        assertEquals(SendAction.KeepAndStop, ReportSendPolicy.afterResponse(429))
        assertEquals(SendAction.KeepAndStop, ReportSendPolicy.afterResponse(503))
        assertEquals(SendAction.TryOtherPath, ReportSendPolicy.afterResponse(null))
    }
}
