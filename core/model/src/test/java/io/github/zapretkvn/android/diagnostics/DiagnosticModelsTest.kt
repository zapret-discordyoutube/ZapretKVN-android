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
    fun `visible log view is bounded to newest eighty lines`() {
        val application = (0 until 60).map { DiagnosticLogLine(5, "app-$it", it.toLong()) }
        val core = (60 until 120).map { DiagnosticLogLine(3, "core-$it", it.toLong()) }

        val logs = DiagnosticState(applicationLogs = application, coreLogs = core).logs

        assertEquals(MAX_DIAGNOSTIC_LOG_LINES, logs.size)
        assertFalse(logs.any { it.message == "app-39" })
        assertTrue(logs.any { it.message == "app-40" })
        assertEquals("core-119", logs.last().message)
    }

    @Test
    fun `individual ring also drops its oldest lines`() {
        val result = (0 until 100).fold(emptyList<DiagnosticLogLine>()) { lines, index ->
            lines.appendBounded(DiagnosticLogLine(5, "line-$index", index.toLong()))
        }

        assertEquals(MAX_DIAGNOSTIC_LOG_LINES, result.size)
        assertEquals("line-20", result.first().message)
        assertEquals("line-99", result.last().message)
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
        val attempts = (1L..4L).map { generation ->
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
            previousConnectionAttempts = attempts.take(3),
            connectionAttempt = attempts.last(),
        )

        assertEquals(MAX_DIAGNOSTIC_STARTUP_LOG_LINES, startup.size)
        assertTrue(startup.any { it.message == "wireguard received handshake response" })
        assertFalse(startup.any { it.message == "routine-0" })
        assertEquals(80L, stats.receivedLines)
        assertEquals(32L, stats.droppedLines)
        assertEquals(listOf(2L, 3L, 4L), state.recentConnectionAttempts.map { it.generation })
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
}
