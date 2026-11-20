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

    private fun compressed(json: String): String {
        val deflater = java.util.zip.Deflater()
        deflater.setInput(json.toByteArray())
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun reports(body: String): List<JsonObject> =
        ((Json.parseToJsonElement(body) as JsonObject)["reports"] as JsonArray).map { it as JsonObject }

    @Test
    fun attemptedAtGoesInTheIngestsTime() {
        val at = 1_763_640_000_000L                                         // 2025-11-20T12:00:00Z
        val ahead = record(at = at)
        val plain = reports(ClientReportJson.batch(installId, app, listOf(ahead), "direct")).single()
        val shifted = reports(ClientReportJson.batch(installId, app, listOf(ahead), "direct", clockOffsetMillis = -3 * 3_600_000L)).single()
        assertEquals("2025-11-20T12:00:00Z", plain["attempted_at"]!!.jsonPrimitive.content)
        assertEquals("2025-11-20T09:00:00Z", shifted["attempted_at"]!!.jsonPrimitive.content)
    }

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
        assertEquals("2", report["schema"]!!.jsonPrimitive.content)
        assertEquals("handshake", report["failed_step"]!!.jsonPrimitive.content)
        assertEquals("tunnel", report["sent_via"]!!.jsonPrimitive.content)
        assertTrue(report["attempted_at"]!!.jsonPrimitive.content.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""")))
    }

    private val details = AttemptDetails(
        serverIp = "185.1.2.3",
        port = 443,
        transport = "tcp",
        security = "reality",
        networkType = "cellular",
        settings = ReportSettings(mode = "tun", dns = "fromjson", masking = "off", quicBlocked = true, ipv6 = false),
        versions = mapOf("os" to "16", "xray" to "1.260327.1-676eaf66f8f0", "hysteria" to "2.14.0"),
    )

    @Test
    fun schemaTwoCarriesTheDetailsOfTheAttempt() {
        val body = ClientReportJson.batch(installId, app, listOf(record().copy(details = details)), "direct")
        val report = reports(body).single()
        assertEquals("185.1.2.3", report["server_ip"]!!.jsonPrimitive.content)
        assertEquals("443", report["port"]!!.jsonPrimitive.content)
        assertEquals("reality", report["security"]!!.jsonPrimitive.content)
        assertEquals("cellular", report["network_type"]!!.jsonPrimitive.content)
        assertEquals(setOf("mode", "dns", "masking", "quic_blocked", "ipv6"), (report["settings"] as JsonObject).keys)
        assertEquals("true", (report["settings"] as JsonObject)["quic_blocked"]!!.jsonPrimitive.content)
        assertEquals(setOf("os", "xray", "hysteria"), (report["versions"] as JsonObject).keys)
        // Неизвестное не пишется вовсе: приёмник принимает отсутствие, а не пустые значения.
        val bare = reports(ClientReportJson.batch(installId, app, listOf(record()), "direct")).single()
        assertFalse("settings" in bare.keys || "versions" in bare.keys || "server_ip" in bare.keys)
    }

    @Test
    fun aForeignServerReplacesTheCatalogueIdAndCleansItsName() {
        val foreign = ForeignServer.of("NL.Example.COM", "vless", "Мой\u200B сервер\u0007 🇳🇱")!!
        assertEquals("nl.example.com", foreign.host)
        assertEquals("Мой сервер 🇳🇱", foreign.name)
        val record = AttemptRecord("", "", ReportOutcome.Failed, "handshake", "sni_blocked", 900, now - 1_000, "", foreign, details)
        assertTrue(ClientReportContract.isValid(record))
        val report = reports(ClientReportJson.batch(installId, app, listOf(record), "direct")).single()
        assertFalse("server_id" in report.keys || "product" in report.keys)
        assertEquals(setOf("host", "protocol", "name"), (report["foreign"] as JsonObject).keys)
        // Оба сразу или ни одного — приёмник отклонит: не ставится в очередь.
        assertFalse(ClientReportContract.isValid(record.copy(serverId = "hipnl", product = "vless")))
        assertFalse(ClientReportContract.isValid(record.copy(foreign = null)))
        assertNull(ForeignServer.of("fe80::1%eth0", "vless", "x"))
        assertNull(ForeignServer.of("bad host", "vless", "x"))
        assertEquals("2001:db8::7", ForeignServer.of("[2001:db8::7]", "hysteria2", "")?.host)
        assertEquals(64, ForeignServer.of("a.example", "vless", "x".repeat(100))!!.name.length)
        // 64 кодовые точки, а не 64 половинки UTF-16: флаги не рвутся.
        val flags = ForeignServer.of("a.example", "vless", "🇳🇱".repeat(40))!!.name
        assertEquals(64, flags.codePointCount(0, flags.length))
    }

    @Test
    fun badDetailValuesDropOutInsteadOfLosingTheReport() {
        val dirty = AttemptDetails(
            serverIp = "fe80::1%wlan0", port = 70_000, transport = "Web Socket", security = "TLS",
            networkType = "satellite", settings = ReportSettings(mode = "vpn", dns = "Свой"),
            versions = mapOf("os" to "16", "kernel" to "6.1", "xray" to "x".repeat(40)),
        ).cleaned()
        assertEquals(AttemptDetails(security = "tls", versions = mapOf("os" to "16")), dirty)
        assertEquals("1.260327.1-676eaf66f8f0", ReportVersions.short("github.com/xtls/xray-core@v1.260327.1-0.20261109052709-676eaf66f8f0"))
        assertEquals("2.14.0", ReportVersions.short("app/v2.14.0"))
        assertEquals("3.1.20261116", ReportVersions.short("github.com/amnezia-vpn/amneziawg-go/v3@v3.1.20261116"))
        assertEquals("1.14.3-extended-2.7.3", ReportVersions.short("v1.14.3-extended-2.7.3"))
    }

    @Test
    fun queueKeepsSchemaTwoAndReadsOldLines() {
        val dir = Files.createTempDirectory("queue2").toFile()
        val file = File(dir, "queue.jsonl")
        // Строка очереди версии 1 (до 19.11.2026) остаётся отчётом, теперь schema 2 без подробностей.
        file.writeText("""{"v":1,"s":"hipnl","p":"vless","o":"connected","f":"","d":"","e":900,"t":${now - 5_000},"n":""}""" + "\n")
        val queue = ReportQueue(file)
        val foreign = AttemptRecord("", "", ReportOutcome.Failed, "connect", "ip_blocked", 900, now - 1_000, "", ForeignServer.of("x.example", "vless", "Сервер")!!, details)
        queue.add(record().copy(details = details))
        queue.add(foreign)
        val pending = ReportQueue(file).pending(now)
        assertEquals(3, pending.size)
        assertEquals("hipnl", pending[0].serverId)
        assertEquals(details, pending[1].details)
        assertEquals(foreign, pending[2])
        dir.deleteRecursively()
    }

    @Test
    fun reportIdIsMadeOnceAndSurvivesTheQueue() {
        // vpnbot «report_id live» 2026-11-19: one attempt, one id, the same in every re-send.
        val first = ClientReportContract.newReportId()
        assertTrue(Regex("^[0-9a-f]{32}$").matches(first))
        assertFalse(first == ClientReportContract.newReportId())
        val dir = Files.createTempDirectory("queue-rid").toFile()
        val file = File(dir, "queue.jsonl")
        ReportQueue(file).add(record().copy(reportId = first))
        val again = ReportQueue(file).pending(now)
        assertEquals(first, again.single().reportId)
        val sent = reports(ClientReportJson.batch(installId, app, again, "direct")).single()
        assertEquals(first, sent["report_id"]!!.jsonPrimitive.content)
        dir.deleteRecursively()
        // A line written before report_id goes out without one; a malformed id never reaches the queue.
        assertFalse("report_id" in reports(ClientReportJson.batch(installId, app, listOf(record()), "direct")).single())
        listOf("ABCDEF".repeat(6), "0".repeat(31), "g".repeat(32)).forEach {
            assertFalse(ClientReportContract.isValid(record().copy(reportId = it)))
        }
    }

    @Test
    fun relayKeyTravelsWithTheTargetOnly() {
        val relayed = record().copy(via = "3f9a0c21be")
        assertEquals("3f9a0c21be", reports(ClientReportJson.batch(installId, app, listOf(relayed), "direct")).single()["via"]!!.jsonPrimitive.content)
        assertFalse("via" in reports(ClientReportJson.batch(installId, app, listOf(record()), "direct")).single())
        val dir = Files.createTempDirectory("queue-via").toFile()
        val file = File(dir, "queue.jsonl")
        ReportQueue(file).add(relayed)
        assertEquals("3f9a0c21be", ReportQueue(file).pending(now).single().via)
        dir.deleteRecursively()
        val foreign = AttemptRecord("", "", ReportOutcome.Failed, "connect", "ip_blocked", 900, now - 1_000, "", ForeignServer.of("x.example", "vless", "Сервер")!!, via = "3f9a0c21be")
        assertFalse(ClientReportContract.isValid(foreign))
        assertFalse(ClientReportContract.isValid(record().copy(via = "3F9A0C21BE")))
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
        val map = ServerIdHeader.parse(compressed(json))
        assertEquals(
            mapOf("🇳🇱 Velnor" to ServerRef("hipnl", "vless"), "Hiphopgen" to ServerRef("hiphopgen", "hysteria2")),
            map,
        )
        // Uncompressed JSON is not the agreed encoding (zlib since 19.11.2026).
        assertTrue(ServerIdHeader.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())).isEmpty())
        // A zip bomb is refused by the inflated-size ceiling.
        assertTrue(ServerIdHeader.parse(compressed("{\"a\": \"" + "x".repeat(ServerIdHeader.MAX_INFLATED_BYTES) + "\"}")).isEmpty())
        assertTrue(ServerIdHeader.parse("not base64 !!").isEmpty())
        assertTrue(ServerIdHeader.parse(null).isEmpty())
        assertTrue(ServerIdHeader.parse("A".repeat(ServerIdHeader.MAX_VALUE_CHARS + 1)).isEmpty())
    }

    @Test
    fun outboundTypeLimitsTheProduct() {
        assertEquals(setOf("vless"), ServerIdHeader.productsForOutboundType("vless"))
        assertEquals(setOf("hysteria2"), ServerIdHeader.productsForOutboundType("hysteria2"))
        assertEquals(setOf("vless"), ServerIdHeader.productsForOutboundType("trojan"))
        assertTrue(ServerIdHeader.productsForOutboundType("selector").isEmpty())
    }

    @Test
    fun theAttemptIsAFactNotAVerdict() {
        // Schema 3: no app verdict; a lost network is a code the server knows, others only in the contract's shape.
        assertEquals(ReportOutcome.Failed, AttemptOutcome.of(AttemptFacts(coreCode = "TARGET_AUTH_REJECTED")))
        assertEquals(ReportOutcome.Cancelled, AttemptOutcome.of(AttemptFacts(cancelled = true)))
        assertEquals(ReportOutcome.Connected, AttemptOutcome.of(AttemptFacts(succeeded = true)))
        assertEquals("NETWORK_LOST", AttemptOutcome.coreCode(AttemptFacts(networkLost = true, coreCode = "TARGET_X")))
        assertEquals("LOCAL_BIND_COLLISION", AttemptOutcome.coreCode(AttemptFacts(coreCode = "LOCAL_BIND_COLLISION")))
        assertEquals("", AttemptOutcome.coreCode(AttemptFacts(coreCode = "CORE_UNCLASSIFIED")))
    }

    @Test
    fun schema3ReportCarriesFactsAndNoAppVerdict() {
        val checks = mapOf("tcp_target" to ReportCheck("refused", 12), "control_front" to ReportCheck("answered", 41))
        val record3 = AttemptRecord(
            "hiphopgen", "hysteria2", ReportOutcome.Failed, "", "", 4200, now - 1000, "",
            reportId = "ab".repeat(16), carrier = "udp", coreCode = "TARGET_NETWORK_TIMEOUT", checks = checks,
        )
        val report = reports(ClientReportJson.batch(installId, app, listOf(record3), "direct")).single()
        assertEquals("3", report["schema"]!!.jsonPrimitive.content)
        assertFalse("failed_step" in report)
        assertFalse("detail" in report)
        assertEquals("udp", report["carrier"]!!.jsonPrimitive.content)
        assertEquals("TARGET_NETWORK_TIMEOUT", report["core_code"]!!.jsonPrimitive.content)
        val sent = report["checks"] as JsonObject
        assertEquals(listOf("control_front", "tcp_target"), sent.keys.toList())
        assertEquals("refused", (sent["tcp_target"] as JsonObject)["r"]!!.jsonPrimitive.content)
        // A schema 2 line still in the queue goes as schema 2.
        assertEquals("2", reports(ClientReportJson.batch(installId, app, listOf(record()), "direct")).single()["schema"]!!.jsonPrimitive.content)
        // Schema 3 never carries an app verdict; schema 2 never carries facts; complete needs a report_id.
        assertFalse(ClientReportContract.isValid(record3.copy(failedStep = "handshake", detail = "x")))
        assertFalse(ClientReportContract.isValid(record().copy(coreCode = "TARGET_NETWORK_TIMEOUT")))
        assertFalse(ClientReportContract.isValid(record3.copy(reportId = "", complete = true)))
        assertFalse(ClientReportContract.isValid(record3.copy(checks = mapOf("tls_real" to ReportCheck("answered", 10, bytes = 5)))))
    }

    @Test
    fun checkRulesFollowTheContract() {
        assertEquals(if (ReportChecks.HEALTH_LIVE) 18 else 17, ReportChecks.PROBES.size)
        assertTrue(ReportChecks.isValid("dns_doh", ReportCheck("nxdomain", 300)))
        assertFalse(ReportChecks.isValid("tcp_target", ReportCheck("nxdomain", 300)))
        assertFalse(ReportChecks.isValid("tcp_target", ReportCheck("answered", 60_001)))
        assertTrue(ReportChecks.isValid("flow", ReportCheck("timeout", 5000, bytes = 16_000)))
        assertTrue(ReportChecks.isValid("dns_system", ReportCheck("answered", 30, same = false)))
        assertFalse(ReportChecks.isValid("dns_doh", ReportCheck("answered", 30, same = true)))
        assertEquals("udp", ReportChecks.carrierOf("hysteria2"))
        assertEquals("udp", ReportChecks.carrierOf("vless", "xhttp", listOf("h3")))
        assertEquals("tcp", ReportChecks.carrierOf("vless", "xhttp", listOf("h2", "http/1.1")))
        assertEquals("udp", ReportChecks.carrierOf("vless", "kcp"))
        assertEquals("tcp", ReportChecks.carrierOf("trojan", "grpc"))
        assertFalse(ReportChecks.needsPathCheck("TARGET_AUTH_REJECTED"))
        assertFalse(ReportChecks.needsPathCheck("LOCAL_BIND_COLLISION"))
        assertFalse(ReportChecks.needsPathCheck("NETWORK_LOST"))
        assertTrue(ReportChecks.needsPathCheck("TARGET_NETWORK_TIMEOUT"))
        assertTrue(ReportChecks.needsPathCheck(""))
    }

    @Test
    fun anUnsentFirstCopyGivesWayToItsCompletion() {
        val dir = Files.createTempDirectory("queue3").toFile()
        val queue = ReportQueue(File(dir, "queue.jsonl"))
        val first = AttemptRecord(
            "hiphopgen", "hysteria2", ReportOutcome.Failed, "", "", 900, now - 1000, "",
            reportId = "cd".repeat(16), carrier = "udp", complete = false,
        )
        val whole = first.copy(complete = true, checks = mapOf("control_front" to ReportCheck("answered", 40)))
        queue.add(first)
        queue.add(whole)
        assertEquals(listOf(whole), ReportQueue(File(dir, "queue.jsonl")).pending(now))
        val report = reports(ClientReportJson.batch(installId, app, listOf(whole), "direct")).single()
        assertEquals("true", report["complete"]!!.jsonPrimitive.content)
        dir.deleteRecursively()
    }

    @Test
    fun replyVerdictsInBatchOrderAndAnythingOddIsNull() {
        val good = """{"detail":"ip_blocked","level":"ip","confidence":"proven","text":"Адрес закрыт.\u0007"}"""
        val got = ClientReportJson.parseReply("""{"ok":true,"verdicts":[$good,null,{"detail":5}]}""", 3)
        assertEquals(ServerVerdict("ip_blocked", "ip", "proven", "Адрес закрыт."), got[0])
        assertEquals(listOf(null, null), got.drop(1))
        assertEquals(listOf(null, null), ClientReportJson.parseReply("""{"ok":true}""", 2))
        assertEquals(listOf<ServerVerdict?>(null), ClientReportJson.parseReply("not json", 1))
        assertEquals(listOf<ServerVerdict?>(null), ClientReportJson.parseReply("""{"verdicts":[$good,$good]}""", 1))
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
