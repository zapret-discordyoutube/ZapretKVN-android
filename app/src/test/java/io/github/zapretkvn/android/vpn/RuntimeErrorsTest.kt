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
}
