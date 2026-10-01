package io.github.zapretkvn.android.network.probes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcmpPingProbeTest {
    @Test
    fun `echo reply must match address family sequence and payload`() {
        val ipv4Request = IcmpEchoPacket.request(ipv6 = false, sequence = 7)
        val ipv4Reply = ipv4Request.copyOf().also { it[0] = 0 }
        assertTrue(IcmpEchoPacket.isMatchingReply(ipv4Reply, ipv4Reply.size, false, ipv4Request))
        val otherSequence = IcmpEchoPacket.request(ipv6 = false, sequence = 8)
        assertFalse(IcmpEchoPacket.isMatchingReply(ipv4Reply, ipv4Reply.size, false, otherSequence))
        assertFalse(IcmpEchoPacket.isMatchingReply(ipv4Reply, ipv4Reply.size, true, ipv4Request))

        val ipv6Request = IcmpEchoPacket.request(ipv6 = true, sequence = 9)
        val ipv6Reply = ipv6Request.copyOf().also { it[0] = 129.toByte() }
        assertTrue(IcmpEchoPacket.isMatchingReply(ipv6Reply, ipv6Reply.size, true, ipv6Request))
        ipv6Reply[30] = 0
        assertFalse(IcmpEchoPacket.isMatchingReply(ipv6Reply, ipv6Reply.size, true, ipv6Request))
    }

    @Test
    fun `echo request looks like the stock ping utility and carries no app signature`() {
        // Пакет идёт открытым текстом мимо туннеля: 16 байт времени отправки и
        // дальше байты 0x10..0x37 — ровно то, что шлёт системный ping.
        val micros = 1_700_000_123L * 1_000_000L + 456_789L
        val packet = IcmpEchoPacket.request(ipv6 = false, sequence = 1, nowEpochMicros = micros)

        assertEquals(64, packet.size)
        val data = packet.copyOfRange(8, packet.size)
        assertEquals(1_700_000_123L, data.longLe(0))
        assertEquals(456_789L, data.longLe(8))
        assertArrayEquals(ByteArray(40) { (it + 16).toByte() }, data.copyOfRange(16, 56))
        assertFalse(String(packet, Charsets.ISO_8859_1).contains("Zapret", ignoreCase = true))
    }

    @Test
    fun `reply to an earlier request is rejected`() {
        val earlier = IcmpEchoPacket.request(ipv6 = false, sequence = 1, nowEpochMicros = 1_000_000L)
        val current = IcmpEchoPacket.request(ipv6 = false, sequence = 1, nowEpochMicros = 9_000_000L)
        val lateReply = earlier.copyOf().also { it[0] = 0 }
        assertFalse(IcmpEchoPacket.isMatchingReply(lateReply, lateReply.size, false, current))
    }

    private fun ByteArray.longLe(offset: Int): Long =
        (0 until 8).fold(0L) { acc, index -> acc or ((this[offset + index].toLong() and 0xff) shl (8 * index)) }
}
