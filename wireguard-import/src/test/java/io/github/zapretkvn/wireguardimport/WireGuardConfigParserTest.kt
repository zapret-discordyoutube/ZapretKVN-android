package io.github.zapretkvn.wireguardimport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfigParserTest {
    @Test
    fun `wireguard fields map to a native sing box endpoint`() {
        val result = WireGuardConfigParser.parse(WIREGUARD_CONF)
        val root = Json.parseToJsonElement(result.json) as JsonObject
        val endpoint = (root["endpoints"] as JsonArray).single() as JsonObject
        val peer = (endpoint["peers"] as JsonArray).single() as JsonObject
        val route = root["route"] as JsonObject
        val dnsServers = ((root["dns"] as JsonObject)["servers"] as JsonArray)

        assertEquals("WireGuard", result.protocolName)
        assertEquals("192.0.2.1:51820", result.endpointLabel)
        assertEquals("wireguard", endpoint.string("type"))
        assertEquals("1280", endpoint["mtu"].content())
        assertEquals("192.0.2.2/32", (endpoint["address"] as JsonArray).single().content())
        assertEquals(TEST_PRIVATE_KEY, endpoint.string("private_key"))
        assertEquals("192.0.2.1", peer.string("address"))
        assertEquals("51820", peer["port"].content())
        assertEquals("25", peer["persistent_keepalive_interval"].content())
        assertEquals("direct", route.string("final"))
        assertEquals("wireguard-out", ((route["rules"] as JsonArray)[1] as JsonObject).string("outbound"))
        assertEquals("wireguard-out", (dnsServers[1] as JsonObject).string("detour"))
        assertEquals("wireguard-out", (dnsServers[2] as JsonObject).string("detour"))
    }

    @Test
    fun `amneziawg 2 fields map to the amnezia object`() {
        val result = WireGuardConfigParser.parse(AWG2_CONF)
        val root = Json.parseToJsonElement(result.json) as JsonObject
        val endpoint = (root["endpoints"] as JsonArray).single() as JsonObject
        val amnezia = endpoint["amnezia"] as JsonObject

        assertEquals("AmneziaWG 2.0", result.protocolName)
        assertEquals("1420", endpoint["mtu"].content())
        assertEquals("4", amnezia["jc"].content())
        assertEquals("684141592-1751861769", amnezia.string("h1"))
        assertEquals("1957920865", amnezia["h2"].content())
        assertEquals("<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001>", amnezia.string("i1"))
        assertFalse("i2" in amnezia)
        assertEquals(1, (root["outbounds"] as JsonArray).size)
        assertEquals("direct", ((root["outbounds"] as JsonArray).single() as JsonObject).string("type"))
    }

    @Test
    fun `amneziawg 3 fields map to the amnezia object`() {
        val result = WireGuardConfigParser.parse(AWG3_CONF)
        val root = Json.parseToJsonElement(result.json) as JsonObject
        val endpoint = (root["endpoints"] as JsonArray).single() as JsonObject
        val amnezia = endpoint["amnezia"] as JsonObject

        assertEquals("AmneziaWG 3.0", result.protocolName)
        assertEquals(TEST_HEADER_PROTECTION_KEY, amnezia.string("header_protection_key"))
        assertEquals("0-96", amnezia.string("content_padding_addition"))
        assertEquals("120-180", amnezia.string("rekey_after_time"))
        assertEquals("5", amnezia["rekey_timeout"].content())
        assertEquals("600", amnezia["reject_after_time"].content())
        assertEquals("25", amnezia["keepalive_timeout"].content())
        assertEquals("20-30", amnezia.string("max_handshake_attempts"))
        // Поля второго поколения из того же конфига не потеряны.
        assertEquals("4", amnezia["jc"].content())
    }

    @Test
    fun `amneziawg 3 values are strictly validated`() {
        assertThrows(WireGuardImportException::class.java) {
            WireGuardConfigParser.parse(
                AWG3_CONF.replace(TEST_HEADER_PROTECTION_KEY, "not-base64")
            )
        }
        assertThrows(WireGuardImportException::class.java) {
            WireGuardConfigParser.parse(
                AWG3_CONF.replace(TEST_HEADER_PROTECTION_KEY, "QUJD")
            )
        }
        assertThrows(WireGuardImportException::class.java) {
            WireGuardConfigParser.parse(
                AWG3_CONF.replace("ContentPaddingAddition = 0-96", "ContentPaddingAddition = 96-0")
            )
        }
        assertThrows(WireGuardImportException::class.java) {
            WireGuardConfigParser.parse(
                AWG3_CONF.replace("RekeyTimeout = 5", "RekeyTimeout = 4294967296")
            )
        }
    }

    @Test
    fun `unsupported behavior and malformed cryptographic material fail closed`() {
        assertThrows(WireGuardImportException::class.java) {
            WireGuardConfigParser.parse(WIREGUARD_CONF.replace("[Peer]", "PostUp = curl bad.example\n[Peer]"))
        }
        assertThrows(WireGuardImportException::class.java) {
            WireGuardConfigParser.parse(WIREGUARD_CONF.replace(TEST_PRIVATE_KEY, "not-base64"))
        }
        assertThrows(WireGuardImportException::class.java) {
            WireGuardConfigParser.parse(WIREGUARD_CONF.replace("[Peer]", "I1 = <unknown 2>\n[Peer]"))
        }
        assertThrows(WireGuardImportException::class.java) {
            WireGuardConfigParser.parse(WIREGUARD_CONF.replace("AllowedIPs = 0.0.0.0/0", "AllowedIPs = 192.0.2.1/24"))
        }
        assertThrows(WireGuardImportException::class.java) {
            WireGuardConfigParser.parse(AWG2_CONF.replace("Jmax = 50", "Jmax = 5"))
        }
    }

    @Test(timeout = 5_000L)
    fun `bounded malformed input always returns a domain error`() {
        val malformed = listOf(
            "[Interface]",
            "[Peer]\nPublicKey = bad",
            "[Interface]\nPrivateKey = bad\nAddress = hostname",
            "[Interface]\nPrivateKey = $TEST_PRIVATE_KEY\nAddress = 10.0.0.1/99",
        )
        malformed.forEach { input ->
            assertTrue(WireGuardConfigParser.looksLikeConfig(input))
            assertThrows(WireGuardImportException::class.java) { WireGuardConfigParser.parse(input) }
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement?.content(): String =
        (this as JsonPrimitive).content

    private companion object {
        const val TEST_PRIVATE_KEY = "TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo="
        const val TEST_PUBLIC_KEY = "vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg="

        const val WIREGUARD_CONF = """
            [Interface]
            PrivateKey = $TEST_PRIVATE_KEY
            Address = 192.0.2.2/32
            DNS = 192.0.2.53, 198.51.100.53

            [Peer]
            PublicKey = $TEST_PUBLIC_KEY
            Endpoint = 192.0.2.1:51820
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
        """

        const val TEST_HEADER_PROTECTION_KEY = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWY="

        val AWG3_CONF = AWG2_CONF.replace(
            "[Peer]",
            """HeaderProtectionKey = $TEST_HEADER_PROTECTION_KEY
            ContentPaddingAddition = 0-96
            RekeyAfterTime = 120-180
            RekeyTimeout = 5
            RejectAfterTime = 600
            KeepaliveTimeout = 25
            MaxHandshakeAttempts = 20-30

            [Peer]""",
        )

        const val AWG2_CONF = """
            [Interface]
            PrivateKey = $TEST_PRIVATE_KEY
            Address = 10.8.1.4/32, fd00::4/128
            MTU = 1420
            Jc = 4
            Jmin = 10
            Jmax = 50
            S1 = 142
            S2 = 41
            S3 = 56
            S4 = 11
            H1 = 684141592-1751861769
            H2 = 1957920865
            H3 = 2043550980-2107134838
            H4 = 2127672251-2132651859
            I1 = <r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001>
            I2 =
            I3 =
            I4 =
            I5 =

            [Peer]
            PublicKey = $TEST_PUBLIC_KEY
            PresharedKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = vpn.example:51820
            PersistentKeepalive = 25
        """
    }
}
