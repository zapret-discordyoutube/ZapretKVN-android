package io.github.zapretkvn.android.config

import io.github.zapretkvn.android.importer.ImportException
import io.github.zapretkvn.android.importer.ShareLinkParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Xray 26.11 refuses plain VLESS to a public address; field check 2026-11-09 (same rule as `requiresTransportSecurity`). */
class PlainTransportPolicyTest {
    private val id = "b831381d-6324-4d53-ad4f-8cda48b30811"

    private fun link(host: String, query: String = "type=ws&security=none") = "vless://$id@$host:443?$query#n"

    private fun outbound(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `public domain and ip without security violate`() {
        listOf("vpn.example.net", "203.0.113.7", "[2001:4860::1]", "8.8.8.8").forEach {
            assertTrue(it, PlainTransportPolicy.violatesLink(link(it)))
        }
        assertTrue(PlainTransportPolicy.violatesLink(link("vpn.example.net", "type=tcp")))
    }

    @Test
    fun `private addresses are allowed`() {
        listOf(
            "192.168.1.10", "10.0.0.5", "172.20.1.1", "127.0.0.1", "169.254.1.1", "[fd00::1]", "[::1]", "[fe80::1]",
            "router", "nas.lan", "vpn.example", "host.internal", "x.home.arpa", "localhost",
        ).forEach { assertFalse(it, PlainTransportPolicy.violatesLink(link(it))) }
        assertTrue(PlainTransportPolicy.violatesLink(link("172.32.0.1")))
    }

    @Test
    fun `tls reality or encryption make a public address fine`() {
        assertFalse(PlainTransportPolicy.violatesLink(link("vpn.example.net", "security=tls")))
        assertFalse(PlainTransportPolicy.violatesLink(link("vpn.example.net", "security=reality&pbk=k")))
        assertFalse(PlainTransportPolicy.violatesLink(link("vpn.example.net", "encryption=mlkem768x25519plus.native.0rtt.abc")))
    }

    @Test
    fun `only vless is checked`() {
        assertFalse(PlainTransportPolicy.violatesLink("trojan://pw@vpn.example.net:443?security=none"))
        assertFalse(PlainTransportPolicy.violates(outbound("""{"type":"hysteria2","server":"vpn.example.net"}""")))
    }

    @Test
    fun `stored outbounds are checked by uri or by sing-box fields`() {
        assertTrue(PlainTransportPolicy.violates(outbound("""{"type":"vless","tag":"a","uri":"${link("vpn.example.net")}"}""")))
        assertTrue(PlainTransportPolicy.violates(outbound("""{"type":"vless","tag":"a","server":"vpn.example.net"}""")))
        assertFalse(
            PlainTransportPolicy.violates(outbound("""{"type":"vless","server":"vpn.example.net","tls":{"enabled":true}}""")),
        )
        assertFalse(
            PlainTransportPolicy.violates(
                outbound("""{"type":"vless","server":"vpn.example.net","tls":{"reality":{"enabled":true}}}"""),
            ),
        )
        val root = outbound(
            """{"outbounds":[{"type":"direct","tag":"d"},{"type":"vless","tag":"bad","server":"vpn.example.net"},
               {"type":"vless","tag":"ok","server":"10.1.1.1"}]}""",
        )
        assertEquals(listOf("bad"), PlainTransportPolicy.violatingTags(root))
    }

    @Test
    fun `import rejects a plain public vless link with a clear message`() {
        val error = assertThrows(ImportException::class.java) { ShareLinkParser.parse(link("vpn.example.net")) }
        assertEquals(PlainTransportPolicy.IMPORT_MESSAGE, error.message)
        ShareLinkParser.parse(link("vpn.example.net", "type=ws&security=tls&sni=vpn.example.net"))
        ShareLinkParser.parse(link("192.168.1.10"))
    }
}
