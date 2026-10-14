package io.github.zapretkvn.android.network.geo

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoIpTableTest {
    /** Тот же формат ZGC1, что пишет scripts/build-geoip-country.py. */
    private fun table(
        v4: List<Pair<Long, String>>,
        v6: List<Pair<Long, String>>,
        month: Int = 202610,
    ): GeoIpTable {
        val codes = (v4 + v6).map { it.second }.distinct().sorted()
        fun stream(entries: List<Pair<Long, String>>): ByteArray {
            val out = ByteArrayOutputStream()
            var previous = 0L
            for ((start, country) in entries) {
                var delta = start - previous
                while (delta >= 0x80) {
                    out.write(((delta and 0x7F) or 0x80).toInt())
                    delta = delta ushr 7
                }
                out.write(delta.toInt())
                out.write(codes.indexOf(country) + 1)
                previous = start
            }
            return out.toByteArray()
        }
        val s4 = stream(v4)
        val s6 = stream(v6)
        val header = ByteBuffer.allocate(9 + codes.size * 2 + 16)
        header.put("ZGC1".toByteArray()).putInt(month).put(codes.size.toByte())
        codes.forEach { header.put(it.toByteArray()) }
        header.putInt(v4.size).putInt(s4.size).putInt(v6.size).putInt(s6.size)
        return GeoIpTable.parse(header.array() + s4 + s6)
    }

    private fun v4(text: String): Long = IpLiteral.parse(text)!!.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    private fun v6(text: String): Long =
        IpLiteral.parse(text)!!.take(6).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    private fun GeoIpTable.at(text: String): String? = lookup(IpLiteral.parse(text)!!)

    private val small = table(
        v4 = listOf(
            v4("1.0.0.0") to "AU",
            v4("5.255.0.0") to "RU",
            v4("46.4.0.0") to "DE",
            v4("185.12.0.0") to "FR",
            v4("185.13.0.0") to "NL",
        ),
        v6 = listOf(
            v6("2001:67c::") to "SE",
            v6("2a02:6b8::") to "RU",
            v6("2a02:6b9::") to "FI",
        ),
    )

    @Test
    fun `ipv4 is the range the address falls into`() {
        assertEquals("RU", small.at("5.255.255.5"))
        assertEquals("RU", small.at("5.255.0.0"))
        assertEquals("AU", small.at("5.254.255.255"))
        assertEquals("FR", small.at("185.12.34.56"))
        assertEquals("NL", small.at("185.13.0.1"))
        assertEquals("NL", small.at("200.200.200.200"))
        assertEquals("DE", small.at("46.4.1.1"))
    }

    @Test
    fun `address before the first range has no country`() {
        val late = table(listOf(v4("20.0.0.0") to "DE"), listOf(v6("2a00::") to "DE"))
        assertNull(late.at("19.255.255.255"))
        assertEquals("DE", late.at("20.0.0.0"))
        assertNull(late.at("2800::1"))
    }

    @Test
    fun `ipv6 uses the upper 48 bits`() {
        assertEquals("RU", small.at("2a02:6b8::feed:ff"))
        assertEquals("RU", small.at("2a02:6b8:ffff:ffff::1"))
        assertEquals("FI", small.at("2a02:6b9::1"))
        assertEquals("SE", small.at("2001:67c:1::1"))
        assertNull(small.at("2001:600::1"))
    }

    @Test
    fun `reserved private and non global addresses never get a country`() {
        val everything = table(listOf(v4("1.0.0.0") to "US"), listOf(v6("2000::") to "US"))
        listOf(
            "10.1.2.3", "127.0.0.1", "192.168.1.1", "172.16.0.5", "172.31.255.255", "169.254.1.1", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "198.18.0.1", "203.0.113.9",
            "::1", "::", "fe80::1", "fc00::1", "ff02::1", "2001:db8::1",
        ).forEach { assertNull(it, everything.at(it)) }
        assertEquals("US", everything.at("172.15.0.1"))
        assertEquals("US", everything.at("172.32.0.1"))
        assertEquals("US", everything.at("8.8.8.8"))
        assertEquals("US", everything.at("2606:4700::1"))
    }

    @Test
    fun `ipv4 mapped ipv6 is looked up as ipv4`() {
        assertEquals("FR", small.at("::ffff:185.12.34.56"))
        assertEquals("FR", small.at("::ffff:b90c:2238"))
    }

    @Test
    fun `wrong length is rejected`() {
        assertNull(small.lookup(ByteArray(3)))
        assertNull(small.lookup(ByteArray(0)))
    }

    @Test
    fun `lookup works across many blocks`() {
        // 5000 записей: у каждой своя страна по чётности, значит проверяется поиск по блокам.
        val entries = (0 until 5000).map { (v4("20.0.0.0") + it * 4096L) to if (it % 2 == 0) "DE" else "PL" }
        val big = table(entries, listOf(v6("2a00::") to "DE"))
        entries.forEachIndexed { index, (start, country) ->
            val bytes = ByteBuffer.allocate(4).putInt(start.toInt()).array()
            assertEquals(country, big.lookup(bytes))
            val last = ByteBuffer.allocate(4).putInt((start + 4095).toInt()).array()
            assertEquals("entry $index", country, big.lookup(last))
        }
    }

    @Test
    fun `database id carries the month`() {
        assertEquals("zgc1-202610", small.databaseId)
        assertEquals(202610, small.month)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `garbage is rejected`() {
        GeoIpTable.parse(ByteArray(64))
    }

    @Test
    fun `truncated table is rejected`() {
        val bytes = realAsset()
        runCatching { GeoIpTable.parse(bytes.copyOf(bytes.size - 5)) }
            .also { assertTrue(it.isFailure) }
    }

    // --- IP-литералы -----------------------------------------------------------------------

    @Test
    fun `literal parser accepts addresses and rejects names`() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), IpLiteral.parse("1.2.3.4"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), IpLiteral.parse(" 1.2.3.4 "))
        assertEquals(16, IpLiteral.parse("2001:db8::1")!!.size)
        assertEquals(16, IpLiteral.parse("[2001:db8::1]")!!.size)
        assertEquals(16, IpLiteral.parse("fe80::1%wlan0")!!.size)
        assertEquals(16, IpLiteral.parse("::")!!.size)
        assertEquals(16, IpLiteral.parse("1:2:3:4:5:6:7:8")!!.size)
        assertEquals(1.toByte(), IpLiteral.parse("::1")!![15])
        assertEquals(0xFF.toByte(), IpLiteral.parse("::ffff:1.2.3.4")!![11])
        assertNull(IpLiteral.parse("example.com"))
        assertNull(IpLiteral.parse("1.2.3"))
        assertNull(IpLiteral.parse("1.2.3.256"))
        assertNull(IpLiteral.parse("1.2.3.4.5"))
        assertNull(IpLiteral.parse("1:2:3:4:5:6:7"))
        assertNull(IpLiteral.parse("1::2::3"))
        assertNull(IpLiteral.parse(":1"))
        assertNull(IpLiteral.parse("12345::1"))
        assertNull(IpLiteral.parse("gggg::1"))
        assertNull(IpLiteral.parse(""))
        assertNull(IpLiteral.parse("1.2.3.4:443"))
    }

    // --- Настоящая таблица из ассетов --------------------------------------------------------

    private fun realAsset(): ByteArray {
        val file = listOf("src/main/assets/geoip/country.bin", "app/src/main/assets/geoip/country.bin")
            .map(::File).first(File::isFile)
        return file.readBytes()
    }

    @Test
    fun `packaged table resolves well known addresses`() {
        val real = GeoIpTable.parse(realAsset())
        // Ожидания сверены с эталонным декодером scripts/build-geoip-country.py по тому же файлу.
        assertEquals("US", real.at("8.8.8.8"))
        assertEquals("AU", real.at("1.1.1.1"))
        assertEquals("RU", real.at("5.255.255.5"))
        assertEquals("RU", real.at("77.88.8.8"))
        assertEquals("FR", real.at("185.12.34.56"))
        assertEquals("FI", real.at("95.216.1.1"))
        assertEquals("DE", real.at("46.4.1.1"))
        assertEquals("NL", real.at("51.15.1.1"))
        assertEquals("RU", real.at("2a02:6b8::feed:ff"))
        assertEquals("DE", real.at("2a01:4f8::1"))
        assertEquals("US", real.at("2400:cb00::1"))
        assertNull(real.at("10.0.0.1"))
        assertNull(real.at("192.168.1.1"))
        assertNull(real.at("fe80::1"))
        assertNull(real.at("2001:db8::1"))
    }

    @Test
    fun `packaged table matches its manifest`() {
        val real = GeoIpTable.parse(realAsset())
        val manifest = listOf("src/main/assets/geoip/manifest.json", "app/src/main/assets/geoip/manifest.json")
            .map(::File).first(File::isFile).readText()
        assertTrue(manifest.contains("\"month\": \"${real.month / 100}-${"%02d".format(real.month % 100)}\""))
        assertTrue(realAsset().size <= 3 * 1024 * 1024)
    }

    @Test
    fun `lazy loader reports an unreadable asset as unavailable without throwing`() {
        val broken = GeoIpCountry { throw java.io.FileNotFoundException("no asset") }
        assertEquals("unavailable", broken.databaseId)
        assertNull(broken.lookup(byteArrayOf(8, 8, 8, 8)))
        val real = GeoIpCountry { realAsset().inputStream() }
        assertEquals("US", real.lookupLiteral("8.8.8.8"))
        assertNull(real.lookupLiteral("example.com"))
        assertTrue(real.databaseId.startsWith("zgc1-"))
    }
}
