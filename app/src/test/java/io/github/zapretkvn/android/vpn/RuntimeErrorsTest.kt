package io.github.zapretkvn.android.vpn

import org.junit.Assert.*
import org.junit.Test

class RuntimeErrorsTest {
    @Test
    fun originalMessagesAreNotReplacedWithDiagnosis() {
        val message = "unrecognized upstream error 781"
        val failure = RuntimeErrors.capture("xray", "dial", message)
        assertEquals(message, failure.message)
        assertEquals("CORE_UNCLASSIFIED", failure.code)
        assertEquals("", failure.targetId)
    }

    @Test
    fun officialAndEmbeddedPinErrorsUseOneCode() {
        for (message in listOf("server certificate SHA-256 mismatch", "no certificate matches the pinned hash")) {
            val failure = RuntimeErrors.capture("hysteria", "handshake", message)
            assertEquals("TARGET_PIN_MISMATCH", failure.code)
            assertEquals(message, failure.message)
        }
    }

    @Test
    fun errorsDoNotShareTrafficLogLimitAndRepeatsRemainCounted() {
        val journal = RuntimeErrorJournal()
        repeat(2100) { index ->
            journal.record(RuntimeErrors.capture("sing-box", "dial", "error $index", sessionGeneration = 3), index.toLong())
        }
        journal.record(RuntimeErrors.capture("sing-box", "dial", "error 0", sessionGeneration = 3), 2100)
        val records = journal.forGeneration(3)
        assertEquals(2100, records.size)
        assertEquals(2L, records.first().occurrences)
        assertEquals("error 0", records.first().failure.message)
        assertTrue(journal.forGeneration(4).isEmpty())
    }

    @Test
    fun iniKeysAreRedactedEvenWithWhitespace() {
        val message = "PrivateKey = synthetic-private-key\nHeaderProtectionKey = synthetic-header-key"
        val failure = RuntimeErrors.capture("wireguard", "config", message)
        assertFalse(failure.message.contains("synthetic"))
        assertTrue(failure.message.contains("HeaderProtectionKey"))
    }

    @Test
    fun exceptionCauseIsRetainedWithoutGuessingServerState() {
        val error = IllegalStateException("readiness failed", java.io.IOException("read: connection reset by peer"))
        assertEquals("readiness failed\nCaused by: read: connection reset by peer", RuntimeErrors.describe(error))
    }

    @Test
    fun requestedCommandLogCloseIsNotAnErrorButSameRemoteCancellationIs() {
        val message = "rpc error: code = Canceled desc = context canceled"
        assertNull(RuntimeErrors.commandLogFailure(8, message, expectedClose = true))
        val unexpected = requireNotNull(RuntimeErrors.commandLogFailure(8, message, expectedClose = false))
        assertEquals("LOCAL_CONTROL_PLANE_UNAVAILABLE", unexpected.code)
        assertEquals("libbox-command-log", unexpected.component)
        assertEquals("CommandLog: $message", unexpected.message)
        assertEquals("stop", unexpected.action)
    }

    @Test
    fun realCommandLogCallbackDoesNotInsertCancellationIntoCoreErrorStream() {
        val errors = mutableListOf<String>()
        val disconnects = mutableListOf<String>()
        val handler = ZapretVpnService.RuntimeErrorLogClientHandler(
            generation = 8,
            onLogs = { _, _ -> },
            onEntry = { _, message -> errors += message },
            onConnected = {},
            onDisconnected = { disconnects += it },
        )
        val original = "rpc error: code = Canceled desc = context canceled"
        handler.disconnected(original)
        assertEquals(listOf(original), disconnects)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun cleanupCannotReplaceCapturedTimeoutWithLateCancellation() {
        val journal = RuntimeErrorJournal()
        val timeout = RuntimeErrors.capture("hysteria2", "https_probe", "timeout: no recent network activity", 8)
        journal.record(timeout, 100)
        val original = java.io.IOException("Read timed out")
        val failure = RuntimeStartupFailure(original, RuntimeErrors.bestEvidence(journal.forGeneration(8)))
        journal.record(RuntimeErrors.capture("sing-box", "runtime", "context canceled", 8), 120)
        assertEquals(timeout, RuntimeErrors.startupEvidence(failure, journal.forGeneration(8)))
        assertSame(original, failure.cause)
        assertEquals("Read timed out", RuntimeErrors.describe(failure))
    }

    @Test
    fun noCoreFailureBeforeCleanupMeansKeepOriginalHttpsError() {
        val original = java.io.IOException("cloudflare:Read timed out; google:Read timed out; opendns:Read timed out")
        val failure = RuntimeStartupFailure(original, null)
        val journal = RuntimeErrorJournal()
        journal.record(RuntimeErrors.capture("sing-box", "runtime", "context canceled", 8), 120)
        assertNull(RuntimeErrors.startupEvidence(failure, journal.forGeneration(8)))
        assertEquals(original.message, RuntimeErrors.describe(failure))
    }
}
