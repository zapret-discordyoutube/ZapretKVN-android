package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.config.OutboundDescription
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerAddressRedactorTest {
    private val profileId = "0123456789abcdef0123456789abcdef"
    private val ref = ServerAddressRedactor.serverRef(profileId, "uranus")

    // Сервер из реального лога: имя sslip.io раскрывает IP.
    private val profileJson = """
        {"outbounds":[
          {"type":"vless","tag":"uranus","server":"185-109-21-120.sslip.io","server_port":8443},
          {"type":"direct","tag":"direct"}
        ],
        "endpoints":[
          {"type":"wireguard","tag":"awg","address":["10.8.0.2/32"],
           "peers":[{"address":"91.200.12.34","port":51820}]}
        ]}
    """.trimIndent()

    @Before
    fun setUp() {
        ServerAddressRedactor.clearForTests()
        ServerAddressRedactor.registerProfile(profileId, profileJson)
    }

    @After
    fun tearDown() = ServerAddressRedactor.clearForTests()

    private fun assertNoLeak(text: String) {
        listOf("185-109-21-120", "185.109.21.120", "sslip.io", "91.200.12.34").forEach {
            assertFalse("leaked $it in: $text", text.contains(it))
        }
    }

    @Test
    fun `server hostname and embedded ip become opaque reference`() {
        assertEquals(
            "VPN-сервер не отвечает: <сервер $ref>:8443.",
            ServerAddressRedactor.redact("VPN-сервер не отвечает: 185-109-21-120.sslip.io:8443."),
        )
        assertEquals(
            "dial tcp <сервер $ref>:8443: i/o timeout",
            ServerAddressRedactor.redact("dial tcp 185.109.21.120:8443: i/o timeout"),
        )
        val awgRef = ServerAddressRedactor.serverRef(profileId, "awg")
        assertEquals(
            "handshake <сервер $awgRef>:51820",
            ServerAddressRedactor.redact("handshake 91.200.12.34:51820"),
        )
    }

    @Test
    fun `destinations local addresses and ports stay visible`() {
        val line = "outbound/vless[uranus]: outbound connection to 142.251.38.99:443 from 172.19.0.1:40112"
        assertEquals(line, ServerAddressRedactor.redact(line))
        // Адрес интерфейса WireGuard (10.8.0.2) — не сервер.
        assertEquals("tun 10.8.0.2", ServerAddressRedactor.redact("tun 10.8.0.2"))
    }

    @Test
    fun `address boundaries`() {
        ServerAddressRedactor.register(listOf("1.2.3.4" to "ep-a", "vpn.example.com" to "ep-b"))
        assertEquals(
            "11.2.3.45 <сервер ep-a>:80 1.2.3.40 <сервер ep-a>.",
            ServerAddressRedactor.redact("11.2.3.45 1.2.3.4:80 1.2.3.40 1.2.3.4."),
        )
        assertEquals(
            "cdn.vpn.example.com <сервер ep-b>. vpn.example.community",
            ServerAddressRedactor.redact("cdn.vpn.example.com VPN.Example.com. vpn.example.community"),
        )
    }

    @Test
    fun `ipv6 server`() {
        ServerAddressRedactor.register(listOf("2a01:4f8::1" to "ep-v6"))
        assertEquals(
            "dial [<сервер ep-v6>]:443 via 2a01:4f8::10",
            ServerAddressRedactor.redact("dial [2a01:4f8::1]:443 via 2a01:4f8::10"),
        )
    }

    @Test
    fun `local and private servers are not masked`() {
        ServerAddressRedactor.register(
            listOf("127.0.0.1" to "ep-lo", "192.168.0.10" to "ep-lan", "localhost" to "ep-name", "::1" to "ep-v6lo"),
        )
        val line = "relay 127.0.0.1:1080 lan 192.168.0.10:1080 localhost:1080 [::1]:1080"
        assertEquals(line, ServerAddressRedactor.redact(line))
    }

    @Test
    fun `ips resolved by the app join their server`() {
        ServerAddressRedactor.register(listOf("vpn.example.org" to "ep-dom"))
        ServerAddressRedactor.registerAliases("vpn.example.org", listOf("45.12.34.56"))
        ServerAddressRedactor.registerAliases("unknown.example.net", listOf("45.12.34.99"))
        assertEquals(
            "dial <сервер ep-dom>:443 and 45.12.34.99:443",
            ServerAddressRedactor.redact("dial 45.12.34.56:443 and 45.12.34.99:443"),
        )
    }

    @Test
    fun `user visible error and runtime journal hide server`() {
        val failure = RuntimeErrors.capture(
            component = "vless",
            stage = "runtime",
            message = "outbound/vless[uranus]: dial tcp 185.109.21.120:8443: connection refused",
            targetId = "185-109-21-120.sslip.io",
        )
        assertNoLeak(failure.message)
        assertNoLeak(failure.targetId)
        assertTrue(failure.message.contains("<сервер $ref>:8443"))

        val state = VpnFailureStates.from(IllegalStateException("VPN-сервер не отвечает: 185-109-21-120.sslip.io:8443."))
        assertNoLeak(state.message)
    }

    @Test
    fun `diagnostic export keeps reference instead of generic mask`() {
        val redacted = DiagnosticReportRedactor.redact(
            "WARN outbound/vless[uranus]: dial 185-109-21-120.sslip.io:8443 then example.org",
        )
        assertNoLeak(redacted)
        assertTrue(redacted.contains("<сервер $ref>:8443"))
        assertFalse(redacted.contains("example.org"))
    }

    @Test
    fun `runtime context endpoint is the same irreversible reference`() {
        val map = DiagnosticRuntimeMap.create(
            profileId = profileId,
            profileName = "Uranus 185-109-21-120.sslip.io",
            descriptions = mapOf(
                "uranus" to OutboundDescription("uranus", "vless", "185-109-21-120.sslip.io", "185-109-21-120.sslip.io:8443"),
            ),
            selectedRawTag = "uranus",
        )
        val target = map.resolve("outbound/vless[uranus]: error")
        assertEquals("$ref:8443", target.endpoint)
        assertNoLeak(target.toString())
    }
}
