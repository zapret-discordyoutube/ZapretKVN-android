package io.github.zapretkvn.networkbootstrap

import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedBootstrapDnsTest {
    private fun answer(query: ByteArray, rcode: Int, vararg records: Pair<Int, ByteArray>): ByteArray {
        val header = byteArrayOf(0, 0, 0x81.toByte(), (0x80 or rcode).toByte(), 0, 1, 0, records.size.toByte(), 0, 0, 0, 0)
        var body = header + query.copyOfRange(12, query.size)
        for ((type, data) in records) {
            // Имя ответа — указатель сжатия на вопрос, как у реальных резолверов.
            body += byteArrayOf(0xC0.toByte(), 0x0C, 0, type.toByte(), 0, 1, 0, 0, 0, 60, 0, data.size.toByte()) + data
        }
        return body
    }

    @Test
    fun queryCarriesTheNameAndAZeroId() {
        val query = DnsWire.buildQuery("VPN.Example.com.", DnsWire.TYPE_A)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, 0, 1), query.copyOfRange(0, 6))
        val name = byteArrayOf(3) + "VPN".toByteArray() + byteArrayOf(7) + "Example".toByteArray() +
            byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        assertArrayEquals(name, query.copyOfRange(12, 12 + name.size))
        assertArrayEquals(byteArrayOf(0, 1, 0, 1), query.copyOfRange(query.size - 4, query.size))
    }

    @Test
    fun responseYieldsAddressesAndSkipsOtherRecords() {
        val query = DnsWire.buildQuery("vpn.example.com", DnsWire.TYPE_A)
        val parsed = DnsWire.parse(
            answer(
                query,
                0,
                5 to byteArrayOf(0xC0.toByte(), 0x0C),
                DnsWire.TYPE_A to byteArrayOf(203.toByte(), 0, 113, 7),
                DnsWire.TYPE_AAAA to ByteArray(15) + byteArrayOf(1),
            ),
        )
        assertEquals(0, parsed.rcode)
        assertEquals(listOf("203.0.113.7", "0:0:0:0:0:0:0:1"), parsed.addresses.map { it.hostAddress })
    }

    @Test
    fun nameErrorAndGarbageAreReported() {
        val query = DnsWire.buildQuery("gone.example.com", DnsWire.TYPE_A)
        val parsed = DnsWire.parse(answer(query, DnsResponseClassifier.RCODE_NAME_ERROR))
        assertEquals(DnsResponseClassifier.RCODE_NAME_ERROR, parsed.rcode)
        assertTrue(parsed.addresses.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { DnsWire.parse(byteArrayOf(0, 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            DnsWire.parse(answer(query, 0, DnsWire.TYPE_A to byteArrayOf(1, 2, 3, 4)).copyOf(query.size + 14))
        }
    }

    @Test
    fun anyAddressWinsOverFailuresAndMissingNames() {
        val found = TrustedDnsOutcome.Addresses(listOf(InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 7))))
        val merged = TrustedDnsPolicy.merge(
            listOf(TrustedDnsOutcome.Unavailable, TrustedDnsOutcome.NameMissing, found),
        )
        assertSame(found, merged)
    }

    @Test
    fun nameIsMissingOnlyWhenEveryResolverAgrees() {
        assertSame(
            TrustedDnsOutcome.NameMissing,
            TrustedDnsPolicy.merge(listOf(TrustedDnsOutcome.NameMissing, TrustedDnsOutcome.NameMissing)),
        )
        // Один недоступный резолвер: это ещё не «сервер переехал».
        assertSame(
            TrustedDnsOutcome.Unavailable,
            TrustedDnsPolicy.merge(listOf(TrustedDnsOutcome.NameMissing, TrustedDnsOutcome.Unavailable)),
        )
        assertSame(TrustedDnsOutcome.Unavailable, TrustedDnsPolicy.merge(emptyList()))
    }

    @Test
    fun unusableAddressesAreRejected() {
        assertTrue(TrustedDnsPolicy.usable(InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 7))))
        assertTrue(!TrustedDnsPolicy.usable(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))))
        assertTrue(!TrustedDnsPolicy.usable(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
        assertTrue(!TrustedDnsPolicy.usable(InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 1))))
    }

    @Test
    fun questionNameIsReadFromARawQuery() {
        val query = DnsWire.buildQuery("VPN.Example.com", DnsWire.TYPE_AAAA)
        assertEquals("vpn.example.com", DnsWire.questionName(query))
        assertEquals(null, DnsWire.questionName(byteArrayOf(0, 1, 2)))
        // Оборванное сообщение не должно ронять транспорт ядра.
        assertEquals(null, DnsWire.questionName(query.copyOf(15)))
    }

    @Test
    fun rawAnswersPreferAddressesAndNeedConsensusForMissingName() {
        val query = DnsWire.buildQuery("vpn.example.com", DnsWire.TYPE_A)
        val positive = answer(query, 0, DnsWire.TYPE_A to byteArrayOf(203.toByte(), 0, 113, 7))
        val empty = answer(query, 0)
        val missing = answer(query, DnsResponseClassifier.RCODE_NAME_ERROR)

        assertTrue(RawAnswerPolicy.isPositive(positive))
        assertTrue(!RawAnswerPolicy.isPositive(empty))
        assertSame(positive, RawAnswerPolicy.merge(listOf(null, missing, positive)))
        assertSame(missing, RawAnswerPolicy.merge(listOf(missing, missing)))
        // Часть резолверов недоступна: это не доказательство, что имени нет.
        assertEquals(null, RawAnswerPolicy.merge(listOf(missing, null)))
        assertEquals(null, RawAnswerPolicy.merge(listOf(empty, null)))
        assertEquals(null, RawAnswerPolicy.merge(emptyList()))
    }

    @Test
    fun serverNameRegistryMatchesNormalizedNamesOnly() {
        ServerNameRegistry.replace(listOf("VPN.Example.com.", " node2.example.net ", ""))
        try {
            assertTrue(ServerNameRegistry.contains("vpn.example.com"))
            assertTrue(ServerNameRegistry.contains("NODE2.example.net."))
            assertTrue(!ServerNameRegistry.contains("example.com"))
            assertTrue(!ServerNameRegistry.contains("sub.vpn.example.com"))
            assertTrue(!ServerNameRegistry.contains(null))
            assertEquals(2, ServerNameRegistry.snapshot().size)
        } finally {
            ServerNameRegistry.replace(emptyList())
        }
    }

    @Test
    fun tamperingMonitorRemembersDetection() {
        DnsTamperingMonitor.reset()
        assertEquals(0L, DnsTamperingMonitor.lastDetectedAtMillis)
        DnsTamperingMonitor.mark(1234L)
        assertEquals(1234L, DnsTamperingMonitor.lastDetectedAtMillis)
        DnsTamperingMonitor.reset()
    }
}
