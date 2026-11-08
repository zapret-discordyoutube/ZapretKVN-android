package io.github.zapretkvn.runtimelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLogCodecTest {
    @Test
    fun `record survives a round trip with domains addresses and special characters`() {
        val text = "dns: exchanged rr3---sn-abc.googlevideo.com. IN A 142.250.1.5\\path\nsecond\rthird"
        val record = LogRecord(1_794_000_123_456L, LogLayer.Dns, 5, text)
        val out = StringBuilder()
        RuntimeLogCodec.encode(1_794_000_000_000L, record, out)

        val line = out.toString()
        assertTrue("one physical line", line.count { it == '\n' } == 1 && line.endsWith("\n"))
        assertEquals(record, RuntimeLogCodec.decode(1_794_000_000_000L, line.trimEnd('\n')))
        assertTrue("domain and address are kept verbatim", "googlevideo.com" in line && "142.250.1.5" in line)
    }

    @Test
    fun `timestamp is a short base36 offset`() {
        val out = StringBuilder()
        RuntimeLogCodec.encode(1_000L, LogRecord(1_000L + 36 * 36, LogLayer.App, 4, "x"), out)
        assertEquals("100 A4 x\n", out.toString())
    }

    @Test
    fun `header is parsed only for this version and returns the start time`() {
        assertEquals(1_794_000_000_000L, RuntimeLogCodec.parseHeader(RuntimeLogCodec.header(1_794_000_000_000L, 7)))
        assertNull(RuntimeLogCodec.parseHeader("#zkvn-log 9 123 1"))
        assertNull(RuntimeLogCodec.parseHeader("random text"))
    }

    @Test
    fun `broken lines decode to null instead of throwing`() {
        listOf("", "x", "zz Z4 text", "10 A", "10 A9x text", "10 AX text").forEach {
            assertNull(it, RuntimeLogCodec.decode(0, it))
        }
    }

    @Test
    fun `levels are clamped into one digit`() {
        val out = StringBuilder()
        RuntimeLogCodec.encode(0, LogRecord(0, LogLayer.Core, 42, "a"), out)
        RuntimeLogCodec.encode(0, LogRecord(0, LogLayer.Core, -3, "b"), out)
        assertEquals("0 C9 a\n0 C0 b\n", out.toString())
    }
}
