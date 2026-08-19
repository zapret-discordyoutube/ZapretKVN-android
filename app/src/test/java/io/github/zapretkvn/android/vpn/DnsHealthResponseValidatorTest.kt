package io.github.zapretkvn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DnsHealthResponseValidatorTest {
    @Test
    fun `valid response requires matching question and at least one answer`() {
        val query = query()
        val validation = DnsHealthResponseValidator.validate(
            query = query,
            response = response(query, flags = 0x8180, answers = 1),
            allowFallback = true,
        )

        assertEquals(0, validation.rcode)
        assertEquals(1, validation.answerCount)
        assertFalse(validation.needsTcpFallback)
    }

    @Test
    fun `truncated and empty UDP responses require TCP fallback`() {
        val query = query()
        val truncated = DnsHealthResponseValidator.validate(
            query,
            response(query, flags = 0x8380, answers = 0),
            allowFallback = true,
        )
        val empty = DnsHealthResponseValidator.validate(
            query,
            response(query, flags = 0x8180, answers = 0),
            allowFallback = true,
        )

        assertTrue(truncated.needsTcpFallback)
        assertEquals("truncated", truncated.fallbackReason)
        assertTrue(empty.needsTcpFallback)
        assertEquals("empty_answer", empty.fallbackReason)
    }

    @Test
    fun `empty TCP response and mismatched question fail closed`() {
        val query = query()
        assertFails { DnsHealthResponseValidator.validate(query, response(query, 0x8180, 0), false) }

        val mismatch = response(query, 0x8180, 1).apply { this[13] = 4 }
        assertFails { DnsHealthResponseValidator.validate(query, mismatch, true) }
    }

    @Test
    fun `nonzero rcode fails closed`() {
        val query = query()
        assertFails { DnsHealthResponseValidator.validate(query, response(query, 0x8183, 0), true) }
    }

    private fun query(): ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
        7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        0, 0, 1, 0, 1,
    )

    private fun response(query: ByteArray, flags: Int, answers: Int): ByteArray = query.copyOf().apply {
        this[2] = (flags ushr 8).toByte()
        this[3] = flags.toByte()
        this[6] = (answers ushr 8).toByte()
        this[7] = answers.toByte()
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Ожидалась ошибка валидации DNS-ответа.")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
