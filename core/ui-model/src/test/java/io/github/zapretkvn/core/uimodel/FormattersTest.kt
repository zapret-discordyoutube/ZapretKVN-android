package io.github.zapretkvn.core.uimodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattersTest {
    @Test
    fun `bytes and session duration remain compact`() {
        assertEquals("0 Б", formatBytes(0))
        assertEquals("1.0 КБ", formatBytes(1_024))
        assertEquals("1.5 МБ", formatBytes(1_572_864))
        assertEquals("1.5 МБ/с", formatRate(1_572_864))
        assertEquals("00:00", formatDuration(0))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun `ping switches to compact seconds above one second`() {
        assertEquals("—", formatPing(null))
        assertEquals("999 мс", formatPing(999))
        assertEquals("1 с", formatPing(1_000))
        assertEquals("1,98 с", formatPing(1_978))
        assertEquals("2 с", formatPing(1_999))
    }

    @Test
    fun `age reads as human words`() {
        assertEquals("только что", formatAge(59_000))
        assertEquals("12 мин назад", formatAge(12 * 60_000L))
        assertEquals("3 ч назад", formatAge(3 * 3_600_000L))
        assertEquals("2 д назад", formatAge(49 * 3_600_000L))
    }

    @Test
    fun `russian plurals follow the 1 2-4 5+ and teens rules`() {
        val forms = arrayOf("сервер", "сервера", "серверов")
        assertEquals("1 сервер", countLabel(1, forms[0], forms[1], forms[2]))
        assertEquals("3 сервера", countLabel(3, forms[0], forms[1], forms[2]))
        assertEquals("5 серверов", countLabel(5, forms[0], forms[1], forms[2]))
        assertEquals("11 серверов", countLabel(11, forms[0], forms[1], forms[2]))
        assertEquals("21 сервер", countLabel(21, forms[0], forms[1], forms[2]))
        assertEquals("112 серверов", countLabel(112, forms[0], forms[1], forms[2]))
        assertEquals("0 серверов", countLabel(0, forms[0], forms[1], forms[2]))
    }

    @Test
    fun `latency bands use relay thresholds`() {
        assertEquals(LatencyBand.Fast, LatencyBand.of(84))
        assertEquals(LatencyBand.Normal, LatencyBand.of(150))
        assertEquals(LatencyBand.Slow, LatencyBand.of(400))
    }

    @Test
    fun `fastest label appears only for a clear winner`() {
        assertNull(clearlyFastest(listOf("a" to 80)))
        assertEquals("a", clearlyFastest(listOf("a" to 80, "b" to 120)))
        assertNull(clearlyFastest(listOf("a" to 100, "b" to 108)))
        assertEquals("b", clearlyFastest(listOf("a" to 1_000, "b" to 800, "c" to 990)))
    }
}
