package io.github.zapretkvn.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticModelsTest {
    @Test
    fun `failure classifier exposes a short stable type`() {
        assertEquals(
            DiagnosticErrorType.PrivateDns,
            DiagnosticFailureClassifier.classify("Strict Private DNS не отвечает"),
        )
        assertEquals(
            DiagnosticErrorType.VpnDns,
            DiagnosticFailureClassifier.classify("DNS через VPN заблокирован"),
        )
        assertEquals(
            DiagnosticErrorType.CaptivePortal,
            DiagnosticFailureClassifier.classify("Интернет требует авторизации в Wi-Fi"),
        )
        assertEquals(
            DiagnosticErrorType.Permission,
            DiagnosticFailureClassifier.classify("Android Always-on/Lockdown не поддерживается"),
        )
        assertEquals(
            DiagnosticErrorType.SystemDns,
            DiagnosticFailureClassifier.classify("Системный DNS не ответил вовремя"),
        )
        assertEquals(
            DiagnosticErrorType.VpnTraffic,
            DiagnosticFailureClassifier.classify(
                "HTTPS-проверка через VPN не прошла: истёк тайм-аут 5000 мс.",
            ),
        )
        assertEquals(
            DiagnosticErrorType.VpnTraffic,
            DiagnosticFailureClassifier.classify(
                "WireGuard data-plane не прошёл проверку TCP/TLS.",
            ),
        )
        assertEquals(
            DiagnosticErrorType.VpnAccess,
            DiagnosticFailureClassifier.classify(
                "VPN-сервер явно отклонил ключ или учётные данные.",
            ),
        )
        assertEquals("VPN-200", DiagnosticErrorType.VpnTraffic.supportCode)
        assertEquals("AUTH-100", DiagnosticErrorType.VpnAccess.supportCode)
        assertEquals("DNS-100", DiagnosticErrorType.SystemDns.supportCode)
        assertEquals(
            DiagnosticErrorType.entries.size,
            DiagnosticErrorType.entries.map(DiagnosticErrorType::supportCode).distinct().size,
        )
    }

    @Test
    fun `visible log view is bounded to the newest lines`() {
        val half = MAX_DIAGNOSTIC_LOG_LINES / 2 + 20
        val application = (0 until half).map { DiagnosticLogLine(5, "app-$it", it.toLong()) }
        val core = (half until 2 * half).map { DiagnosticLogLine(3, "core-$it", it.toLong()) }

        val logs = DiagnosticState(applicationLogs = application, coreLogs = core).logs
        val firstKept = 2 * half - MAX_DIAGNOSTIC_LOG_LINES

        assertEquals(MAX_DIAGNOSTIC_LOG_LINES, logs.size)
        assertFalse(logs.any { it.message == "app-${firstKept - 1}" })
        assertTrue(logs.any { it.message == "app-$firstKept" })
        assertEquals("core-${2 * half - 1}", logs.last().message)
    }

    @Test
    fun `individual ring also drops its oldest lines`() {
        val total = MAX_DIAGNOSTIC_LOG_LINES + 20
        val result = (0 until total).fold(emptyList<DiagnosticLogLine>()) { lines, index ->
            lines.appendBounded(DiagnosticLogLine(5, "line-$index", index.toLong()))
        }

        assertEquals(MAX_DIAGNOSTIC_LOG_LINES, result.size)
        assertEquals("line-20", result.first().message)
        assertEquals("line-${total - 1}", result.last().message)
    }

    @Test
    fun `startup priority ring preserves handshake evidence while history stays bounded`() {
        var startup = emptyList<DiagnosticLogLine>()
        var stats = DiagnosticLogStats()
        (0 until 80).forEach { index ->
            val line = if (index == 30) {
                CoreDiagnosticClassifier.classify(5, "wireguard received handshake response")
                    .copy(receivedAtEpochMillis = index.toLong(), lastReceivedAtEpochMillis = index.toLong())
            } else {
                DiagnosticLogLine(
                    level = 5,
                    message = "routine-$index",
                    receivedAtEpochMillis = index.toLong(),
                    source = DiagnosticLogSource.Core,
                )
            }
            startup.appendPrioritizedBounded(
                line,
                stats,
                MAX_DIAGNOSTIC_STARTUP_LOG_LINES,
            ).also {
                startup = it.lines
                stats = it.stats
            }
        }
        val attempts = (1L..8L).map { generation ->
            DiagnosticConnectionAttempt(
                generation = generation,
                trigger = "attempt-$generation",
                startedAtEpochMillis = generation,
                startedAtElapsedRealtimeMillis = generation,
                outcome = DiagnosticAttemptOutcome.Connected,
                startupCoreLogs = startup,
                startupCoreLogStats = stats,
            )
        }
        val state = DiagnosticState(
            previousConnectionAttempts = attempts.take(7),
            connectionAttempt = attempts.last(),
        )

        assertEquals(MAX_DIAGNOSTIC_STARTUP_LOG_LINES, startup.size)
        assertTrue(startup.any { it.message == "wireguard received handshake response" })
        assertFalse(startup.any { it.message == "routine-0" })
        assertEquals(80L, stats.receivedLines)
        assertEquals(32L, stats.droppedLines)
        assertEquals(6, MAX_DIAGNOSTIC_ATTEMPTS)
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L, 8L), state.recentConnectionAttempts.map { it.generation })
    }

    @Test
    fun `repeated core messages are coalesced instead of growing the ring`() {
        var lines = emptyList<DiagnosticLogLine>()
        var stats = DiagnosticLogStats()
        repeat(100) { index ->
            val line = DiagnosticLogLine(
                level = 5,
                message = "same packet noise",
                receivedAtEpochMillis = index.toLong(),
                source = DiagnosticLogSource.Core,
                lastReceivedAtEpochMillis = index.toLong(),
            )
            lines.appendPrioritizedBounded(line, stats, limit = 8).also {
                lines = it.lines
                stats = it.stats
            }
        }

        assertEquals(1, lines.size)
        assertEquals(100, lines.single().repeatCount)
        assertEquals(99L, stats.coalescedLines)
        assertEquals(0L, stats.droppedLines)
    }

    @Test
    fun `classifier marks transport evidence but not ordinary dns traffic as priority`() {
        val handshake = CoreDiagnosticClassifier.classify(5, "wireguard received handshake response")
        val dnsNoise = CoreDiagnosticClassifier.classify(5, "dns query example")
        val error = CoreDiagnosticClassifier.classify(2, "generic failure")

        assertEquals(DiagnosticLogCategory.WireGuard, handshake.category)
        assertTrue(handshake.priority)
        assertEquals(DiagnosticLogCategory.Dns, dnsNoise.category)
        assertFalse(dnsNoise.priority)
        assertTrue(error.priority)
    }

    @Test
    fun `core callback collector is capped and replaces routine noise with handshake evidence`() {
        val collector = CoreDiagnosticBatchCollector(limit = 3)
        collector.add(5, "routine one")
        collector.add(5, "routine two")
        collector.add(5, "routine three")
        collector.add(5, "wireguard received handshake response")
        collector.add(5, "routine four")
        val batch = collector.result()

        assertEquals(3, batch.entries.size)
        assertTrue(batch.entries.any { it.second.contains("received handshake response") })
        assertFalse(batch.entries.any { it.second == "routine one" })
        assertEquals(2, batch.droppedLines)
    }

    @Test
    fun `xray startup noise is counted without taking batch slots`() {
        val collector = CoreDiagnosticBatchCollector(limit = 2)
        repeat(300) { collector.add(3, "WARN[0000] xray: [Warning] core: Xray 26.3.27 started") }
        repeat(100) {
            collector.add(
                3,
                "WARN[0000] xray: [Warning] common/errors: The feature gRPC transport (with unnecessary costs, etc.) is deprecated",
            )
        }
        collector.add(2, "ERROR[0046] [1 5ms] connection: open connection to 1.2.3.4:443 using outbound/vless[a]: i/o timeout")
        val batch = collector.result()

        assertEquals(1, batch.entries.size)
        assertEquals(0, batch.droppedLines)
        assertEquals(mapOf(CoreLogNoise.XrayStarted to 300, CoreLogNoise.XrayDeprecated to 100), batch.noise)
    }

    @Test
    fun `connection failures are summarised without addresses`() {
        val ipv6 = ConnectionFailureKey.parse(
            2,
            "ERROR[0000] [2837305672 18ms] connection: connection download closed: dial error: " +
                "dial tcp6 [2001:db8::1]:443: connect: network is unreachable",
        )
        assertEquals(ConnectionFailureKey("tcp6", 443, "unknown", "unreachable"), ipv6)

        val proxy = ConnectionFailureKey.parse(
            2,
            "ERROR[0046] [1 5.0s] connection: open connection to 203.0.113.5:443 using outbound/vless[node]: i/o timeout",
        )
        assertEquals(ConnectionFailureKey("tcp", null, "vless", "timeout"), proxy)

        val udp = ConnectionFailureKey.parse(2, "ERROR connection: dial udp 203.0.113.5:443: connection refused")
        assertEquals(ConnectionFailureKey("udp", 443, "unknown", "refused"), udp)

        assertEquals(null, ConnectionFailureKey.parse(5, "INFO connection: dial tcp 1.2.3.4:443: i/o timeout"))
        assertEquals(null, ConnectionFailureKey.parse(2, "ERROR dns: exchange failed"))
        assertTrue(listOfNotNull(ipv6, proxy, udp).none { "2001" in it.toString() || "203.0" in it.toString() })
    }

    @Test
    fun `per connection lines are evicted before ordinary info`() {
        val info = CoreDiagnosticClassifier.classify(5, "INFO[0001] outbound/vless[a]: outbound connection to x:443")
        val routine = CoreDiagnosticClassifier.classify(5, "INFO[0045] [1 0ms] router: found package name: app")
        assertTrue(routine.routine)
        assertFalse(info.routine)
        var lines = listOf(info, routine)
        lines = lines.appendPrioritizedBounded(info.copy(message = "INFO newer"), limit = 2).lines
        assertTrue(lines.none { it.routine })
        assertTrue(lines.any { it.message == info.message })
    }

    @Test
    fun `connection attempt exposes the slowest completed stage`() {
        val attempt = DiagnosticConnectionAttempt(
            generation = 7,
            trigger = "user_start",
            startedAtEpochMillis = 1_000,
            startedAtElapsedRealtimeMillis = 100,
            totalDurationMillis = 900,
            outcome = DiagnosticAttemptOutcome.Connected,
            stages = listOf(
                DiagnosticStageTiming(
                    key = "profile",
                    label = "Профиль",
                    startedAtEpochMillis = 1_000,
                    startedAtElapsedRealtimeMillis = 100,
                    durationMillis = 150,
                    status = DiagnosticStageStatus.Success,
                ),
                DiagnosticStageTiming(
                    key = "core_tun",
                    label = "Core и TUN",
                    startedAtEpochMillis = 1_150,
                    startedAtElapsedRealtimeMillis = 250,
                    durationMillis = 610,
                    status = DiagnosticStageStatus.Success,
                ),
                DiagnosticStageTiming(
                    key = "finalize",
                    label = "Финализация",
                    startedAtEpochMillis = 1_760,
                    startedAtElapsedRealtimeMillis = 860,
                ),
            ),
        )

        assertEquals("core_tun", attempt.slowestCompletedStage?.key)
        assertEquals(610L, attempt.slowestCompletedStage?.durationMillis)
    }

    @Test
    fun `stop attempt exposes the slowest teardown stage`() {
        val attempt = DiagnosticStopAttempt(
            generation = 8,
            trigger = "user_stop",
            startedAtEpochMillis = 2_000,
            startedAtElapsedRealtimeMillis = 200,
            totalDurationMillis = 750,
            outcome = DiagnosticStopOutcome.Completed,
            stages = listOf(
                DiagnosticStageTiming(
                    key = "close_tun",
                    label = "Закрытие Android TUN",
                    startedAtEpochMillis = 2_000,
                    startedAtElapsedRealtimeMillis = 200,
                    durationMillis = 4,
                    status = DiagnosticStageStatus.Success,
                ),
                DiagnosticStageTiming(
                    key = "close_libbox_service",
                    label = "Остановка сервиса libbox",
                    startedAtEpochMillis = 2_004,
                    startedAtElapsedRealtimeMillis = 204,
                    durationMillis = 710,
                    status = DiagnosticStageStatus.Success,
                ),
            ),
        )

        assertEquals("close_libbox_service", attempt.slowestCompletedStage?.key)
        assertEquals(710L, attempt.slowestCompletedStage?.durationMillis)
    }

    private fun ring(limit: Int, lines: List<DiagnosticLogLine>): DiagnosticLogAppendResult {
        var kept = emptyList<DiagnosticLogLine>()
        var stats = DiagnosticLogStats()
        lines.forEach { line ->
            kept.appendPrioritizedBounded(line, stats, limit).also {
                kept = it.lines
                stats = it.stats
            }
        }
        return DiagnosticLogAppendResult(kept, stats)
    }

    private fun core(level: Int, message: String, at: Long) = CoreDiagnosticClassifier.classify(level, message)
        .copy(receivedAtEpochMillis = at, lastReceivedAtEpochMillis = at)

    @Test
    fun `quota evicts debug first, then info, and priority evidence last`() {
        val lines = listOf(
            core(5, "info one", 1),
            core(7, "trace one", 2),
            core(2, "boom error one", 3),
            core(6, "debug one", 4),
            core(5, "info two", 5),
            core(2, "boom error two", 6),
            // limit 6 reached; each next line forces one eviction
            core(5, "info three", 7),
            core(5, "info four", 8),
            core(5, "info five", 9),
        )
        val result = ring(limit = 6, lines = lines).lines.map { it.message }

        assertEquals(6, result.size)
        assertFalse("trace evicted first", "trace one" in result)
        assertFalse("debug evicted second", "debug one" in result)
        assertFalse("then the oldest info", "info one" in result)
        assertTrue("boom error one" in result)
        assertTrue("boom error two" in result)
    }

    @Test
    fun `priority lines are evicted only when nothing less valuable remains, oldest first`() {
        val lines = (1..6).map { core(2, "error $it", it.toLong()) } + core(2, "error 7", 7)
        val result = ring(limit = 6, lines = lines).lines.map { it.message }
        assertEquals(listOf("error 2", "error 3", "error 4", "error 5", "error 6", "error 7"), result)
    }

    @Test
    fun `a debug line does not push out ordinary info under pressure`() {
        val full = (1..4).map { core(5, "info $it", it.toLong()) }
        val result = ring(limit = 4, lines = full + core(7, "trace late", 9))
        assertEquals(listOf("info 1", "info 2", "info 3", "info 4"), result.lines.map { it.message })
        assertEquals(1L, result.stats.droppedLines)
    }

    @Test
    fun `consecutive routine dns lines collapse into one counted line`() {
        val lines = (1..50).map { core(5, "dns: exchanged host$it.example A", it.toLong()) }
        val result = ring(limit = 8, lines = lines + core(2, "dns: exchange failed: i/o timeout", 60))
        assertEquals(2, result.lines.size)
        assertEquals(50, result.lines.first().repeatCount)
        assertEquals(DiagnosticLogCategory.Dns, result.lines.first().category)
        assertEquals(49L, result.stats.coalescedLines)
        assertTrue(result.lines.last().priority)
    }

    @Test
    fun `reconnect, quic, dial and idle markers are priority evidence`() {
        listOf(
            "outbound/hysteria2[a]: connection closed: timeout: no recent network activity",
            "quic stream reset by peer",
            "reconnecting to server",
            "dial tcp x:443 something",
        ).forEach { message ->
            assertTrue(message, CoreDiagnosticClassifier.classify(5, message).priority)
        }
    }
}
