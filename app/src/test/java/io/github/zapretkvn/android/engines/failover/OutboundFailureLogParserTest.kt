package io.github.zapretkvn.android.engines.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundFailureLogParserTest {
    @Test
    fun `extracts type and tag for every proxy protocol`() {
        val lines = listOf(
            "outbound/hysteria2[Tokyo]: no recent network activity",
            "outbound/vless[Amsterdam]: connection refused",
            "outbound/vmess[Paris-2]: i/o timeout",
            "outbound/trojan[Berlin]: connection reset by peer",
            "outbound/shadowsocks[Osaka]: EOF",
        )
        val parsed = OutboundFailureLogParser.all(lines)
        assertEquals(
            listOf("hysteria2", "vless", "vmess", "trojan", "shadowsocks"),
            parsed.map { it.outboundType },
        )
        assertEquals(
            listOf("Tokyo", "Amsterdam", "Paris-2", "Berlin", "Osaka"),
            parsed.map { it.outboundTag },
        )
    }

    @Test
    fun `lowercases the type but preserves the tag verbatim`() {
        val line = OutboundFailureLogParser.first(listOf("outbound/VLESS[MixedCaseTag]: boom"))
        assertEquals("vless", line?.outboundType)
        assertEquals("MixedCaseTag", line?.outboundTag)
    }

    @Test
    fun `ignores lines without an outbound tag`() {
        val parsed = OutboundFailureLogParser.all(
            listOf(
                "router: found DNS server",
                "outbound/direct: nothing tagged here",
                "inbound/tun[tun-in]: started",
            ),
        )
        assertTrue(parsed.isEmpty())
        assertNull(OutboundFailureLogParser.first(listOf("no outbound here")))
    }
}
