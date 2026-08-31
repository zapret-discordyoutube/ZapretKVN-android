package io.github.zapretkvn.android.importer

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.RuntimeConfigBuilder
import io.github.zapretkvn.android.config.RuntimeConfigOptions
import io.github.zapretkvn.android.config.RuntimeConfigResult
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.profiles.ManagedProfileEditor
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.profiles.ProtocolOutboundBuilders
import io.github.zapretkvn.android.profiles.TlsSettings
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportParserTest {
    @Test
    fun `wireguard conf maps directly to sing box endpoint`() {
        val candidate = ImportParser.parse(
            """
                [Interface]
                PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
                Address = 192.0.2.2/32
                DNS = 192.0.2.53, 198.51.100.53

                [Peer]
                PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
                Endpoint = 192.0.2.1:51820
                AllowedIPs = 0.0.0.0/0
                PersistentKeepalive = 25
            """.trimIndent(),
            ProfileSource.File,
            "wg1_r1107syg5xn",
        ) as ImportCandidate.WireGuard
        val root = JsonConfig.parse(candidate.json) as JsonObject
        val endpoint = (root["endpoints"] as JsonArray).single() as JsonObject
        val peer = (endpoint["peers"] as JsonArray).single() as JsonObject
        val route = root["route"] as JsonObject
        val dnsServers = ((root["dns"] as JsonObject)["servers"] as JsonArray)

        assertEquals("WireGuard", candidate.protocolName)
        assertEquals("wg1_r1107syg5xn", candidate.suggestedName)
        assertEquals("wireguard", endpoint.string("type"))
        assertEquals("192.0.2.2/32", ((endpoint["address"] as JsonArray).single() as JsonPrimitive).content)
        assertEquals("TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=", endpoint.string("private_key"))
        assertEquals("192.0.2.1", peer.string("address"))
        assertEquals("51820", (peer["port"] as JsonPrimitive).content)
        assertEquals("25", (peer["persistent_keepalive_interval"] as JsonPrimitive).content)
        assertEquals("direct", route.string("final"))
        assertEquals("wireguard-out", ((route["rules"] as JsonArray)[1] as JsonObject).string("outbound"))
        assertEquals("wireguard-out", (dnsServers[1] as JsonObject).string("detour"))
        assertEquals("wireguard-out", (dnsServers[2] as JsonObject).string("detour"))
        val fromJson = RuntimeConfigBuilder.build(
            candidate.json,
            options = RuntimeConfigOptions(dnsMode = DnsMode.FromJson),
        ) as RuntimeConfigResult.Ready
        val fromJsonRoot = JsonConfig.parse(fromJson.json) as JsonObject
        val runtimeEndpoint = (fromJsonRoot["endpoints"] as JsonArray).single() as JsonObject
        val runtimeRouteRules = ((fromJsonRoot["route"] as JsonObject)["rules"] as JsonArray)
            .map { it as JsonObject }
        assertEquals(null, runtimeEndpoint.string("detour"))
        assertTrue(
            runtimeRouteRules.any {
                it.string("outbound") == "wireguard-out" &&
                    it["domain"].toString().contains("cp.cloudflare.com")
            },
        )
        val automatic = RuntimeConfigBuilder.build(
            candidate.json,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Automatic),
        ) as RuntimeConfigResult.Ready
        val automaticDns = (JsonConfig.parse(automatic.json) as JsonObject)["dns"] as JsonObject
        val automaticServers = (automaticDns["servers"] as JsonArray).map { it as JsonObject }
        assertEquals("zapret-secure-dns", automaticDns.string("final"))
        assertEquals(
            "wireguard-out",
            automaticServers.first { it.string("tag") == "zapret-doh-1" }.string("detour"),
        )
    }

    @Test
    fun `amneziawg 2 conf maps native obfuscation fields without a proxy layer`() {
        val candidate = ImportParser.parse(
            """
                # AWG 2.0 native format
                [Interface]
                PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
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
                I1 = <r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>
                I2 =
                I3 =
                I4 =
                I5 =

                [Peer]
                PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
                PresharedKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                AllowedIPs = 0.0.0.0/0, ::/0
                Endpoint = vpn.example:42333
                PersistentKeepalive = 25
            """.trimIndent(),
            ProfileSource.File,
            "AWG Finland",
        ) as ImportCandidate.WireGuard
        val root = JsonConfig.parse(candidate.json) as JsonObject
        val endpoint = (root["endpoints"] as JsonArray).single() as JsonObject
        val amnezia = endpoint["amnezia"] as JsonObject

        assertEquals("AmneziaWG 2.0", candidate.protocolName)
        assertEquals("1420", (endpoint["mtu"] as JsonPrimitive).content)
        assertEquals("4", (amnezia["jc"] as JsonPrimitive).content)
        assertEquals("684141592-1751861769", amnezia.string("h1"))
        assertEquals("1957920865", (amnezia["h2"] as JsonPrimitive).content)
        assertEquals(
            "<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>",
            amnezia.string("i1"),
        )
        assertFalse("i2" in amnezia)
        assertEquals(1, (root["outbounds"] as JsonArray).size)
        assertEquals("direct", ((root["outbounds"] as JsonArray).single() as JsonObject).string("type"))
    }

    @Test
    fun `wireguard conf rejects unknown keys malformed keys and awg tags`() {
        val base = """
            [Interface]
            PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=
            Address = 192.0.2.2/32
            %s
            [Peer]
            PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=
            Endpoint = 192.0.2.1:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        assertThrows(ImportException::class.java) {
            ImportParser.parse(base.format("PostUp = curl bad.example"), ProfileSource.File)
        }
        assertThrows(ImportException::class.java) {
            ImportParser.parse(base.format("PrivateKey = not-base64"), ProfileSource.File)
        }
        assertThrows(ImportException::class.java) {
            ImportParser.parse(base.format("I1 = <unknown 2>"), ProfileSource.File)
        }
    }

    @Test
    fun `plain and base64 subscriptions support six protocol families`() {
        val vmessPayload = Base64.getEncoder().withoutPadding().encodeToString(
            """{"v":"2","ps":"VMess","add":"vm.example","port":"443","id":"22222222-2222-4222-8222-222222222222","aid":"0","scy":"auto","net":"tcp","tls":"tls","sni":"vm.example"}"""
                .toByteArray(),
        )
        val ssCredentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:ss-secret".toByteArray())
        val links = listOf(
            "# comment is allowed",
            "vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls#VLESS",
            "vmess://$vmessPayload",
            "trojan://trojan-secret@trojan.example:443?sni=trojan.example#Trojan",
            "ss://$ssCredentials@ss.example:8388#SS",
            "hy2://hy-secret@hy.example:443?sni=hy.example&obfs=salamander&obfs-password=obfs#HY2",
            "tuic://33333333-3333-4333-8333-333333333333:tuic-secret@tuic.example:443?sni=tuic.example&congestion_control=bbr#TUIC",
        ).joinToString("\n")

        val plain = ImportParser.parse(links, ProfileSource.Clipboard) as ImportCandidate.Managed
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(links.toByteArray())
        val base64 = ImportParser.parse(encoded, ProfileSource.Clipboard) as ImportCandidate.Managed

        val expectedTypes = listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic")
        assertEquals(expectedTypes, plain.servers.map { it.outbound.string("type") })
        assertEquals(expectedTypes, base64.servers.map { it.outbound.string("type") })
        assertEquals(6, ConfigAnalyzer.selectorGroups(plain.buildJson()).single().outbounds.size)
    }

    @Test
    fun `json subscription is canonicalized as candidates while sing box json stays raw`() {
        val candidate = ImportParser.parse(
            """
                {
                  "servers": [
                    "vless://11111111-1111-4111-8111-111111111111@one.example?security=tls#One",
                    {"url":"trojan://secret@two.example?sni=two.example#Two"}
                  ]
                }
            """.trimIndent(),
            ProfileSource.Url,
            "JSON subscription",
        ) as ImportCandidate.Managed

        assertEquals(ProfileSource.Subscription, candidate.source)
        assertEquals(listOf("vless", "trojan"), candidate.servers.map { it.outbound.string("type") })
        assertEquals(listOf("443", "443"), candidate.servers.map {
            (it.outbound["server_port"] as JsonPrimitive).content
        })

        val raw = ImportParser.parse(
            """{"outbounds":[{"type":"direct","tag":"direct"}]}""",
            ProfileSource.File,
        )
        assertTrue(raw is ImportCandidate.RawJson)
    }

    @Test
    fun `standard ports reality fields and unsupported parameter failures are explicit`() {
        val vmessPayload = Base64.getEncoder().withoutPadding().encodeToString(
            """{"v":"2","ps":"Reality","add":"vm.example","id":"22222222-2222-4222-8222-222222222222","net":"tcp","tls":"reality","sni":"front.example","pbk":"public-key","sid":"abcd"}"""
                .toByteArray(),
        )
        val vmess = ShareLinkParser.parse("vmess://$vmessPayload").outbound
        val reality = ((vmess["tls"] as JsonObject)["reality"] as JsonObject)
        assertEquals("443", (vmess["server_port"] as JsonPrimitive).content)
        assertEquals("public-key", reality.string("public_key"))
        assertEquals("abcd", reality.string("short_id"))

        val ssCredentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:secret".toByteArray())
        val shadowsocks = ShareLinkParser.parse("ss://$ssCredentials@ss.example#SS").outbound
        assertEquals("8388", (shadowsocks["server_port"] as JsonPrimitive).content)

        // spx не переносится в sing-box, но и не мешает: ссылка импортируется
        // с предупреждением, а отказ остаётся для параметров неизвестного класса.
        val degraded = ShareLinkParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@one.example?security=tls&spx=%2F",
        )
        assertTrue(degraded.importWarnings.any { it.contains("spx") })

        val unknown = ShareLinkParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@one.example?security=tls&zzz=1",
        )
        assertTrue(unknown.importWarnings.any { it.contains("zzz") })
    }

    @Test
    fun `text dump extracts every supported link across labels wrappers and shared lines`() {
        val vmessPayload = Base64.getEncoder().withoutPadding().encodeToString(
            """{"v":"2","ps":"VMess","add":"vm.example","port":"443","id":"22222222-2222-4222-8222-222222222222","aid":"0","scy":"auto","net":"tcp","tls":"tls","sni":"vm.example"}"""
                .toByteArray(),
        )
        val input = """
            Конфиги из бота:
            1. Основной: vless://11111111-1111-4111-8111-111111111111@one.example:443#One
            Резервные: <vmess://$vmessPayload> trojan://secret@trojan.example:443#Trojan,
            Канал: https://t.me/example
            Последний — hy2://secret@hy.example:443?sni=hy.example#HY2.
        """.trimIndent()

        val candidate = ImportParser.parse(input, ProfileSource.File, "Все конфиги") as ImportCandidate.Managed

        assertEquals(
            listOf("vless", "vmess", "trojan", "hysteria2"),
            candidate.servers.map { it.outbound.string("type") },
        )
        assertEquals(listOf("One", "VMess", "Trojan", "HY2"), candidate.servers.map { it.displayName })
        assertEquals("Все конфиги", candidate.suggestedName)
    }

    @Test
    fun `unsupported config scheme is reported while supported links remain importable`() {
        val input = """
            Основной: vless://11111111-1111-4111-8111-111111111111@one.example:443
            Резерв: socks://user:password@unknown.example:1080
            SSH: ssh://user:password@ssh.example:22
        """.trimIndent()

        val candidate = ImportParser.parse(input, ProfileSource.Clipboard) as ImportCandidate.Managed

        assertEquals(1, candidate.servers.size)
        assertEquals(
            listOf("Пропущены неподдерживаемые схемы: socks://, ssh://."),
            candidate.importWarnings,
        )
    }

    @Test
    fun `mixed hysteria vless and unsupported tunnel schemes import partially`() {
        val pin = "ef".repeat(32)
        val input = listOf(
            "hysteria2://secret@hy.example:443?insecure=1&pinSHA256=$pin#HY2",
            "vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls#VLESS",
            "wireguard://unsupported-subscription-entry",
            "awg://unsupported-subscription-entry",
            "ssh://unsupported-subscription-entry",
        ).joinToString("\n")

        val candidate = ImportParser.parse(
            input,
            ProfileSource.Subscription,
        ) as ImportCandidate.Managed

        assertEquals(
            listOf("hysteria2", "vless"),
            candidate.servers.map { it.outbound.string("type") },
        )
        val warning = candidate.importWarnings.single { it.contains("неподдерживаемые схемы") }
        assertTrue(warning.contains("wireguard://"))
        assertTrue(warning.contains("awg://"))
        assertTrue(warning.contains("ssh://"))
    }

    @Test
    fun `subscription containing only unsupported schemes still fails clearly`() {
        val error = assertThrows(ImportException::class.java) {
            ImportParser.parse("ssh://user:password@ssh.example:22", ProfileSource.Clipboard)
        }

        assertEquals(
            "В подписке нет поддерживаемых ссылок. Неподдерживаемые схемы: ssh://.",
            error.message,
        )
    }

    @Test
    fun `ipv6 percent encoding transport tls and reality map to exact json fields`() {
        val vless = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@[2001:db8::1]:443" +
                "?security=reality&sni=edge.example&fp=chrome&pbk=public-key&sid=abcd" +
                "&type=ws&path=%2Fvpn&host=cdn.example#IPv6%20Reality",
            ProfileSource.Qr,
        ) as ImportCandidate.Managed
        val outbound = vless.servers.single().outbound

        assertEquals("2001:db8::1", outbound.string("server"))
        assertEquals("IPv6 Reality", vless.servers.single().displayName)
        assertEquals("ws", (outbound["transport"] as JsonObject).string("type"))
        val tls = outbound["tls"] as JsonObject
        assertEquals("edge.example", tls.string("server_name"))
        assertEquals(
            "public-key",
            (tls["reality"] as JsonObject).string("public_key"),
        )

        val trojan = ImportParser.parse(
            "trojan://p%40ss%3Aword+plus@trojan.example:443#Encoded",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        assertEquals("p@ss:word+plus", trojan.servers.single().outbound.string("password"))
    }

    @Test
    fun `vless xray udp443 flow alias maps to sing box vision`() {
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@vision.example:443" +
                "?security=reality&flow=xtls-rprx-vision-udp443" +
                "&sni=cdn.example&fp=chrome" +
                "&pbk=nDCKIlAlRIBhaDNs04SMghv0qbjQhfQrXyocJriGRg4&sid=abcd#Vision",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed

        assertEquals(
            "xtls-rprx-vision",
            candidate.servers.single().outbound.string("flow"),
        )
    }

    @Test
    fun `vless encryption is preserved for pinned core validation`() {
        val encryption = "mlkem768x25519plus.native.0rtt." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@vision.example:443" +
                "?security=reality&flow=xtls-rprx-vision&encryption=$encryption" +
                "&sni=cdn.example&fp=edge&pbk=public-key&sid=abcd#VLESSenc",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed

        assertEquals(
            encryption,
            candidate.servers.single().outbound.string("encryption"),
        )
    }

    @Test
    fun `vless unknown flow fails before core validation`() {
        val error = assertThrows(ImportException::class.java) {
            ImportParser.parse(
                "vless://11111111-1111-4111-8111-111111111111@vision.example:443" +
                    "?security=tls&flow=unknown-flow",
                ProfileSource.Clipboard,
            )
        }

        assertTrue(error.message.orEmpty().contains("VLESS flow 'unknown-flow'"))
    }

    @Test
    fun `vless xhttp maps XTLS URL encoded extra to pinned sing box transport`() {
        val extra = encodeURIComponent(
            """{"headers":{"Referer":"https://cdn.example/a+b"},"xmux":{"maxConcurrency":"16-32","hKeepAlivePeriod":10},"noGRPCHeader":true,"noSSEHeader":true,"xPaddingBytes":"100-1000","scMaxEachPostBytes":1000000,"scMinPostsIntervalMs":"20-40","scMaxBufferedPosts":30,"xPaddingPlacement":"header","uplinkHTTPMethod":"POST"}""",
        )
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@xhttp.example:443" +
                "?security=tls&sni=cdn.example&alpn=h2%2Chttp%2F1.1&type=xhttp" +
                "&mode=stream-up&path=%2Fapi&host=cdn.example&extra=$extra#XHTTP",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        val outbound = candidate.servers.single().outbound
        val transport = outbound["transport"] as JsonObject
        val tls = outbound["tls"] as JsonObject
        val xmux = transport["xmux"] as JsonObject
        val headers = transport["headers"] as JsonObject

        assertEquals("xhttp", transport.string("type"))
        assertEquals("stream-up", transport.string("mode"))
        assertEquals("/api", transport.string("path"))
        assertEquals("cdn.example", transport.string("host"))
        assertEquals("true", (transport["no_grpc_header"] as JsonPrimitive).content)
        assertEquals("true", (transport["no_sse_header"] as JsonPrimitive).content)
        assertEquals("100-1000", transport.string("x_padding_bytes"))
        assertEquals("1000000", (transport["sc_max_each_post_bytes"] as JsonPrimitive).content)
        assertEquals("20-40", transport.string("sc_min_posts_interval_ms"))
        assertEquals("30", (transport["sc_max_buffered_posts"] as JsonPrimitive).content)
        assertEquals("header", transport.string("x_padding_placement"))
        assertEquals("POST", transport.string("uplink_http_method"))
        assertEquals("https://cdn.example/a+b", headers.string("Referer"))
        assertEquals("16-32", xmux.string("max_concurrency"))
        assertEquals("10", (xmux["h_keep_alive_period"] as JsonPrimitive).content)
        assertEquals(
            listOf("h2", "http/1.1"),
            (tls["alpn"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun `vless xhttp imports the complete xhttp object instead of individual fields`() {
        val extra = encodeURIComponent(
            """
                {
                  "type":"xhttp",
                  "mode":"packet-up",
                  "host":"download.example",
                  "path":"/complete",
                  "extra":{
                    "headers":{"User-Agent":"Zapret/XHTTP"},
                    "domainStrategy":"prefer_ipv4",
                    "sessionIDTable":"0123456789abcdef",
                    "sessionIDLength":"12-16",
                    "trustedXForwardedFor":["192.0.2.0/24"],
                    "congestionController":"bbr2",
                    "cwnd":64,
                    "xmux":{
                      "maxConcurrency":"8-16",
                      "cMaxReuseTimes":"32-64",
                      "hMaxReusableSecs":"1800-3000"
                    }
                  }
                }
            """.trimIndent(),
        )
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@xhttp.example:443" +
                "?security=tls&type=xhttp&extra=$extra#Complete+XHTTP",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        val transport = candidate.servers.single().outbound["transport"] as JsonObject
        val headers = transport["headers"] as JsonObject
        val xmux = transport["xmux"] as JsonObject

        assertEquals("xhttp", transport.string("type"))
        assertEquals("packet-up", transport.string("mode"))
        assertEquals("download.example", transport.string("host"))
        assertEquals("/complete", transport.string("path"))
        assertEquals("Zapret/XHTTP", headers.string("User-Agent"))
        assertEquals("prefer_ipv4", transport.string("domain_strategy"))
        assertEquals("0123456789abcdef", transport.string("session_id_table"))
        assertEquals("12-16", transport.string("session_id_length"))
        assertEquals("bbr2", transport.string("congestion_controller"))
        assertEquals("64", (transport["cwnd"] as JsonPrimitive).content)
        assertEquals("8-16", xmux.string("max_concurrency"))
        assertEquals("32-64", xmux.string("c_max_reuse_times"))
        assertEquals("1800-3000", xmux.string("h_max_reusable_secs"))
        assertEquals(
            "192.0.2.0/24",
            ((transport["trusted_x_forwarded_for"] as JsonArray).single() as JsonPrimitive).content,
        )
    }

    @Test
    fun `vless xhttp without extra uses required core padding default`() {
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@xhttp.example:443" +
                "?security=tls&type=xhttp&mode=stream-up&path=%2Fapi#XHTTP",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        val transport = candidate.servers.single().outbound["transport"] as JsonObject

        assertEquals("xhttp", transport.string("type"))
        assertEquals("100-1000", transport.string("x_padding_bytes"))
    }

    @Test
    fun `vless xhttp converts legacy xmux lifetime milliseconds to pinned core seconds`() {
        val rangeExtra = encodeURIComponent(
            """{"xmux":{"cMaxLifetimeMs":"1800000-3000000"}}""",
        )
        val rangeCandidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@xhttp.example:443" +
                "?security=tls&type=xhttp&extra=$rangeExtra#XHTTP",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        val rangeTransport = rangeCandidate.servers.single().outbound["transport"] as JsonObject
        val rangeXmux = rangeTransport["xmux"] as JsonObject

        assertEquals("1800-3000", rangeXmux.string("h_max_reusable_secs"))

        val numberExtra = encodeURIComponent("""{"xmux":{"cMaxLifetimeMs":3600000}}""")
        val numberCandidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@xhttp.example:443" +
                "?security=tls&type=xhttp&extra=$numberExtra#XHTTP",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        val numberTransport = numberCandidate.servers.single().outbound["transport"] as JsonObject
        val numberXmux = numberTransport["xmux"] as JsonObject

        assertEquals(
            "3600",
            (numberXmux["h_max_reusable_secs"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `vless xhttp rejects ambiguous or lossy legacy xmux lifetime`() {
        listOf(
            """{"xmux":{"cMaxLifetimeMs":1500}}""",
            """{"xmux":{"cMaxLifetimeMs":3600000,"hMaxReusableSecs":3600}}""",
        ).forEach { rawExtra ->
            val extra = encodeURIComponent(rawExtra)
            assertThrows(ImportException::class.java) {
                ImportParser.parse(
                    "vless://11111111-1111-4111-8111-111111111111@xhttp.example:443" +
                        "?security=tls&type=xhttp&extra=$extra#XHTTP",
                    ProfileSource.Clipboard,
                )
            }
        }
    }

    @Test
    fun `vless xhttp rejects nonstandard Base64 extra instead of misreading XTLS`() {
        val extra = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"xPaddingBytes":"100-1000"}""".toByteArray(),
        )
        val error = assertThrows(ImportException::class.java) {
            ImportParser.parse(
                "vless://11111111-1111-4111-8111-111111111111@xhttp.example:443" +
                    "?security=tls&type=xhttp&extra=$extra#XHTTP",
                ProfileSource.Clipboard,
            )
        }

        assertTrue(error.message.orEmpty().contains("URL-кодированным JSON-объектом"))
    }

    @Test
    fun `vless xhttp preserves new fields for validation by the pinned core`() {
        val extra = encodeURIComponent("""{"futureOption":true}""")
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@xhttp.example:443" +
                "?security=tls&type=xhttp&extra=$extra#XHTTP",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        val transport = candidate.servers.single().outbound["transport"] as JsonObject

        assertEquals("true", (transport["future_option"] as JsonPrimitive).content)
    }

    @Test
    fun `trojan xhttp with reality maps transport and tls to pinned sing box schema`() {
        val candidate = ImportParser.parse(
            "trojan://5c8e7f8b-14b3-4ed4-a512-df54bf37c223@77-246-97-234.sslip.io:39529" +
                "?security=reality&type=xhttp&sni=www.intel.com" +
                "&pbk=nDCKIlAlRIBhaDNs04SMghv0qbjQhfQrXyocJriGRg4" +
                "&fp=edge&sid=82bdd80edf4aaf7c#Trojan+XHTTP",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        val outbound = candidate.servers.single().outbound
        val transport = outbound["transport"] as JsonObject
        val tls = outbound["tls"] as JsonObject
        val reality = tls["reality"] as JsonObject

        assertEquals("trojan", outbound.string("type"))
        assertEquals("xhttp", transport.string("type"))
        assertEquals("100-1000", transport.string("x_padding_bytes"))
        assertEquals("www.intel.com", tls.string("server_name"))
        assertEquals("edge", (tls["utls"] as JsonObject).string("fingerprint"))
        assertEquals("nDCKIlAlRIBhaDNs04SMghv0qbjQhfQrXyocJriGRg4", reality.string("public_key"))
        assertEquals("82bdd80edf4aaf7c", reality.string("short_id"))
    }

    @Test
    fun `vmess xhttp maps payload network tls and alpn to pinned sing box schema`() {
        val payload = Base64.getEncoder().encodeToString(
            """
                {"v":"2","ps":"VMess XHTTP","add":"beeline.example","port":"33096",
                "id":"040d2805-e9f5-43c5-b8e6-017ed169e6cc","aid":"0","scy":"auto",
                "net":"xhttp","type":"none","tls":"tls","sni":"beeline.example",
                "alpn":"http/1.1","fp":"edge","path":"/"}
            """.trimIndent().toByteArray(),
        )
        val candidate = ImportParser.parse(
            "vmess://$payload",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        val outbound = candidate.servers.single().outbound
        val transport = outbound["transport"] as JsonObject
        val tls = outbound["tls"] as JsonObject

        assertEquals("vmess", outbound.string("type"))
        assertEquals("xhttp", transport.string("type"))
        assertEquals("/", transport.string("path"))
        assertEquals(null, transport.string("mode"))
        assertEquals("100-1000", transport.string("x_padding_bytes"))
        assertEquals("beeline.example", tls.string("server_name"))
        assertEquals("edge", (tls["utls"] as JsonObject).string("fingerprint"))
        assertEquals(
            listOf("http/1.1"),
            (tls["alpn"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun `vmess xhttp keeps valid mode from type field`() {
        val payload = Base64.getEncoder().encodeToString(
            """
                {"v":"2","ps":"VMess XHTTP","add":"xhttp.example","port":"443",
                "id":"040d2805-e9f5-43c5-b8e6-017ed169e6cc","net":"xhttp",
                "type":"packet-up","tls":"tls","path":"/api"}
            """.trimIndent().toByteArray(),
        )
        val candidate = ImportParser.parse(
            "vmess://$payload",
            ProfileSource.Clipboard,
        ) as ImportCandidate.Managed
        val transport = candidate.servers.single().outbound["transport"] as JsonObject

        assertEquals("packet-up", transport.string("mode"))
    }

    @Test(timeout = 5_000L)
    fun `parser rejects arbitrary bounded input without non-domain failures`() {
        val random = Random(0x5A17)
        repeat(1_000) {
            val input = buildString {
                repeat(random.nextInt(0, 512)) {
                    append(random.nextInt(0x20, 0x7f).toChar())
                }
            }
            try {
                ImportParser.parse(input, ProfileSource.Clipboard)
            } catch (_: ImportException) {
                // Expected domain result for malformed input.
            }
        }
    }

    @Test
    fun `activity scan reports one set without mutating json`() {
        val raw = """
            {
              "log": {"level": "debug"},
              "ntp": {"enabled": true, "server": "time.example"},
              "outbounds": [{"type": "urltest", "tag": "auto", "outbounds": ["direct"]}],
              "route": {"rule_set": [{"type": "remote", "tag": "remote", "url": "https://rules.example/a.srs"}]},
              "experimental": {"clash_api": {"external_controller": "127.0.0.1:9090"}},
              "heartbeat": "10s"
            }
        """.trimIndent()

        val flags = ImportedConfigActivityScanner.scan(raw)

        assertEquals(ImportedActivityFlag.entries.toSet(), flags)
        assertTrue(ImportedConfigActivityScanner.warning(flags)!!.endsWith("JSON не будет изменён."))
    }

    @Test
    fun `redactor masks uri json uuid and subscription query`() {
        val uuid = "11111111-1111-4111-8111-111111111111"
        val input = """vless://$uuid@vpn.example:443?token=secret https://sub.example/list?token=secret {"password":"secret"}"""

        val output = SecretRedactor.redactInline(input)

        assertFalse(uuid in output)
        assertFalse("token=secret" in output)
        assertFalse("\"password\":\"secret\"" in output)
        assertTrue(SecretRedactor.MASK in output)
    }

    @Test
    fun `managed refresh preserves selector default and unknown fields then falls back`() {
        val one = server("One", "one.example")
        val two = server("Two", "two.example")
        val three = server("Three", "three.example")
        val original = ManagedProfileFactory.subscription(listOf(one, two))
        val secondTag = ManagedProfileFactory.stableTags(listOf(one, two))[1]
        val selected = ConfigAnalyzer.selectServer(original, ConfigAnalyzer.MANAGED_SELECTOR_TAG, secondTag)
        val selectedRoot = JsonConfig.parse(selected) as JsonObject
        val withUnknown = JsonConfig.format(
            JsonObject(selectedRoot.toMutableMap().apply {
                this["extended_unknown"] = JsonPrimitive("kept")
            }),
        )

        val preserved = ManagedProfileEditor.refreshServers(withUnknown, listOf(two, three))
        assertEquals(secondTag, preserved.selectedTag)
        assertFalse(preserved.selectionChanged)
        assertEquals(
            "kept",
            ((JsonConfig.parse(preserved.json) as JsonObject)["extended_unknown"] as JsonPrimitive).content,
        )

        val fallback = ManagedProfileEditor.refreshServers(preserved.json, listOf(three))
        assertTrue(fallback.selectionChanged)
        assertEquals(
            ConfigAnalyzer.selectorGroups(fallback.json).single().outbounds.first(),
            fallback.selectedTag,
        )
    }

    @Test
    fun `single server append changes only managed outbound list`() {
        val original = ManagedProfileFactory.single(server("One", "one.example"))
        val rootBefore = JsonConfig.parse(original) as JsonObject

        val update = ManagedProfileEditor.appendServer(original, server("Two", "two.example"))

        val rootAfter = JsonConfig.parse(update.json) as JsonObject
        assertEquals(rootBefore["inbounds"], rootAfter["inbounds"])
        assertEquals(rootBefore["route"], rootAfter["route"])
        assertEquals(2, ConfigAnalyzer.selectorGroups(update.json).single().outbounds.size)
        val outbounds = rootAfter["outbounds"] as JsonArray
        assertEquals(4, outbounds.size)
    }

    private fun server(name: String, host: String) = ProtocolOutboundBuilders.vless(
        displayName = name,
        server = host,
        serverPort = 443,
        uuid = "11111111-1111-4111-8111-111111111111",
        tls = TlsSettings(enabled = true, serverName = host),
    )


    // Ссылки ниже — реальный выхлоп панелей Marzban, Remnawave, 3x-ui и Hiddify.

    @Test
    fun `reality link with spx and headerType imports`() {
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@one.example:443" +
                "?security=reality&sni=example.org&fp=chrome&pbk=abc&sid=ff&spx=%2F" +
                "&type=tcp&headerType=none&flow=xtls-rprx-vision#One",
            ProfileSource.Link,
            "Профиль",
        ) as ImportCandidate.Managed

        assertEquals(1, candidate.servers.size)
        assertTrue(candidate.importWarnings.any { it.contains("spx") })
    }

    @Test
    fun `hysteria2 link with fp and security imports without applying utls`() {
        val candidate = ImportParser.parse(
            "hysteria2://secret@one.example:8443?alpn=h3&fp=chrome&security=tls&sni=example.org#Hy2",
            ProfileSource.Link,
            "Профиль",
        ) as ImportCandidate.Managed

        val outbound = candidate.servers.single().outbound
        val tls = outbound["tls"] as JsonObject
        assertEquals(null, tls["utls"])
        assertTrue(candidate.importWarnings.any { it.contains("fp") })
    }

    @Test
    fun `hysteria2 rejects a link that claims TLS is off`() {
        assertThrows(ImportException::class.java) {
            ImportParser.parse(
                "hysteria2://secret@one.example:8443?security=none#Hy2",
                ProfileSource.Link,
                "Профиль",
            )
        }
    }

    @Test
    fun `pinning next to insecure keeps full certificate pin`() {
        val pin = (0 until 32).joinToString(":") { "%02X".format(it) }
        val candidate = ImportParser.parse(
            "hysteria2://secret@one.example:8443" +
                "?insecure=1&pinSHA256=${URLEncoder.encode(pin, StandardCharsets.UTF_8)}" +
                "&obfs=salamander&obfs-password=pw&sni=one.example#Hy2",
            ProfileSource.Link,
            "Профиль",
        ) as ImportCandidate.Managed

        val outbound = candidate.servers.single().outbound
        assertEquals((0 until 32).joinToString("") { "%02x".format(it) }, outbound.string("certificate_sha256"))
        assertTrue(candidate.importWarnings.any { it.contains("проверку сертификата") })
    }

    @Test
    fun `malformed certificate pin is refused`() {
        val error = assertThrows(ImportException::class.java) {
            ImportParser.parse("hysteria2://secret@one.example:8443?pinSHA256=deadbeef#Hy2", ProfileSource.Link)
        }
        assertTrue(error.message.orEmpty().contains("32 байта"))
    }

    @Test
    fun `hysteria2 insecure without pin is refused fail closed`() {
        val error = assertThrows(ImportException::class.java) {
            ImportParser.parse(
                "hysteria2://secret@one.example:8443?insecure=1&sni=one.example#Hy2",
                ProfileSource.Link,
            )
        }

        assertTrue(error.message.orEmpty().contains("insecure"))
        assertTrue(error.message.orEmpty().contains("pinSHA256"))
    }

    @Test
    fun `hysteria2 pin without insecure remains valid and preserved`() {
        val pin = "ab".repeat(32)
        val candidate = ImportParser.parse(
            "hysteria2://secret@one.example:8443?pinSHA256=$pin&sni=one.example#Hy2",
            ProfileSource.Link,
        ) as ImportCandidate.Managed
        val outbound = candidate.servers.single().outbound
        val tls = outbound["tls"] as JsonObject

        assertEquals(pin, outbound.string("certificate_sha256"))
        assertFalse("insecure" in tls)
    }

    @Test
    fun `hysteria2 stored and runtime transport fields stay identical`() {
        val pin = "cd".repeat(32)
        val uri = "hysteria2://user%3Apass@192.0.2.10:443,20000-20002" +
            "?sni=edge.example&insecure=1&pinSHA256=$pin" +
            "&obfs=gecko&obfs-password=cover&hop-interval=15s#Gecko"
        val candidate = ImportParser.parse(uri, ProfileSource.Link) as ImportCandidate.Managed
        val stored = candidate.buildJson()
        val storedOutbound = ((JsonConfig.parse(stored) as JsonObject)["outbounds"] as JsonArray)
            .map { it as JsonObject }
            .single { it.string("type") == "hysteria2" }
        val runtime = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Android),
        ) as RuntimeConfigResult.Ready
        val runtimeOutbound = ((JsonConfig.parse(runtime.json) as JsonObject)["outbounds"] as JsonArray)
            .map { it as JsonObject }
            .single { it.string("type") == "hysteria2" }

        assertEquals(storedOutbound, runtimeOutbound)
        assertEquals(uri, runtimeOutbound.string("uri"))
        assertEquals(pin, runtimeOutbound.string("certificate_sha256"))
        assertEquals(
            "true",
            ((runtimeOutbound["tls"] as JsonObject)["insecure"] as JsonPrimitive).content,
        )
        assertEquals("gecko", (runtimeOutbound["obfs"] as JsonObject).string("type"))
        assertEquals(2, (runtimeOutbound["server_ports"] as JsonArray).size)
    }

    @Test
    fun `hysteria2 kotlin model matches exact core uri dialect`() {
        val uri = "hy2://edge.example:443,20000:20002?auth=user+pass&up=2+Gbps&" +
            "down=2000000+bps&hop-interval=15&obfs=gecko&obfs-password=cover+key&" +
            "min-packet-size=64&max-packet-size=128#Exact"
        val server = (ImportParser.parse(uri, ProfileSource.Link) as ImportCandidate.Managed)
            .servers.single()
        val outbound = server.outbound
        val obfs = outbound["obfs"] as JsonObject

        assertEquals("user pass", outbound.string("password"))
        assertEquals("2000", (outbound["up_mbps"] as JsonPrimitive).content)
        assertEquals("2", (outbound["down_mbps"] as JsonPrimitive).content)
        assertEquals("15s", outbound.string("hop_interval"))
        assertEquals(
            listOf("443", "20000-20002"),
            (outbound["server_ports"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals("cover key", obfs.string("password"))
        assertEquals("64", (obfs["min_packet_size"] as JsonPrimitive).content)
        assertEquals("128", (obfs["max_packet_size"] as JsonPrimitive).content)
    }

    @Test
    fun `hysteria2 rejects fields the exact core cannot execute`() {
        listOf(
            "hy2://secret@2001:db8::1",
            "hy2://secret@edge.example:443?hop-interval=tomorrow",
            "hy2://secret@edge.example:443?up=100Mbps",
            "hy2://secret@edge.example:443?obfs=salamander&obfs-password=x&min-packet-size=64",
            "hy2://secret@edge.example:443?obfs=gecko&obfs-password=x&max-packet-size=2049",
            "hy2://secret@edge.example:443?sni=one.example;peer=two.example",
            "hy2://secret@edge.example:443?sni=%GG",
        ).forEach { uri ->
            assertThrows("Unexpectedly accepted $uri", ImportException::class.java) {
                ImportParser.parse(uri, ProfileSource.Link)
            }
        }
    }

    @Test
    fun `hysteria2 duplicate known query meaning is refused as ambiguous`() {
        listOf(
            "sni=one.example&sni=two.example",
            "sni=one.example&peer=two.example",
            "insecure=0&allow-insecure=1",
        ).forEach { query ->
            val error = assertThrows(ImportException::class.java) {
                ImportParser.parse(
                    "hysteria2://secret@one.example:8443?$query#Hy2",
                    ProfileSource.Link,
                )
            }
            assertTrue(error.message.orEmpty().contains("повторяет параметр"))
        }
    }

    @Test
    fun `hysteria2 empty optional values match official core defaults`() {
        val candidate = ImportParser.parse(
            "hysteria2://secret@one.example:8443?sni=&obfs=&pinSHA256=#Hy2",
            ProfileSource.Link,
        ) as ImportCandidate.Managed
        val outbound = candidate.servers.single().outbound
        val tls = outbound["tls"] as JsonObject

        assertEquals("one.example", tls.string("server_name"))
        assertFalse("obfs" in outbound)
        assertFalse("certificate_sha256" in outbound)
    }

    @Test
    fun `hysteria2 literal and percent encoded controls are refused`() {
        listOf(
            "hysteria2://secret@one.example:8443?auth=bad%0Avalue#Hy2",
            "hysteria2://secret@one.example:8443#bad%0Dname",
            "hysteria2://secret@one.example:8443?insecure=0\n&sni=one.example#Hy2",
        ).forEach { link ->
            assertThrows(ImportException::class.java) {
                ImportParser.parse(link, ProfileSource.Link)
            }
        }
    }

    @Test
    fun `parameter names are matched by meaning not spelling`() {
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@one.example:443" +
                "?security=tls&SNI=example.org&allow_insecure=1&Fingerprint=chrome#One",
            ProfileSource.Link,
            "Профиль",
        ) as ImportCandidate.Managed

        val tls = candidate.servers.single().outbound["tls"] as JsonObject
        assertEquals("example.org", (tls["server_name"] as JsonPrimitive).content)
        assertEquals("true", (tls["insecure"] as JsonPrimitive).content)
        assertTrue(candidate.importWarnings.any { it.contains("проверку сертификата") })
    }

    /**
     * Панели добавляют собственные параметры быстрее, чем их можно перечислить:
     * fm и vcn у 3x-ui появились после spx. Незнакомое имя теряется с
     * предупреждением, а отказ остаётся за проверками, названными поимённо.
     */
    @Test
    fun `vendor specific parameters do not block the import`() {
        val candidate = ImportParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@one.example:443" +
                "?security=tls&sni=one.example&fm=%7B%22a%22%3A1%7D&vcn=1&zzz=1#One",
            ProfileSource.Link,
            "Профиль",
        ) as ImportCandidate.Managed

        assertEquals(1, candidate.servers.size)
        val warning = candidate.importWarnings.single { it.contains("не применены") }
        assertTrue(warning, warning.contains("fm"))
        assertTrue(warning, warning.contains("vcn"))
        assertTrue(warning, warning.contains("zzz"))
    }

    @Test
    fun `unknown security mode is refused instead of silently dropping tls`() {
        val error = assertThrows(ImportException::class.java) {
            ImportParser.parse(
                "vless://11111111-1111-4111-8111-111111111111@one.example:443?security=xtls#One",
                ProfileSource.Link,
                "Профиль",
            )
        }
        assertTrue(error.message.orEmpty().contains("security"))
    }

    @Test
    fun `header obfuscation is refused because it changes the wire format`() {
        assertThrows(ImportException::class.java) {
            ImportParser.parse(
                "vless://11111111-1111-4111-8111-111111111111@one.example:443" +
                    "?security=tls&type=tcp&headerType=http#One",
                ProfileSource.Link,
                "Профиль",
            )
        }
    }

    @Test
    fun `shadowsocks accepts a plain tcp marker and refuses a foreign transport`() {
        val candidate = ImportParser.parse(
            "ss://YWVzLTI1Ni1nY206c2VjcmV0@one.example:8388?type=tcp#SS",
            ProfileSource.Link,
            "Профиль",
        ) as ImportCandidate.Managed
        assertEquals(1, candidate.servers.size)

        assertThrows(ImportException::class.java) {
            ImportParser.parse(
                "ss://YWVzLTI1Ni1nY206c2VjcmV0@one.example:8388?type=ws#SS",
                ProfileSource.Link,
                "Профиль",
            )
        }
    }

    @Test
    fun `salamander without a password is refused instead of building a dead server`() {
        assertThrows(ImportException::class.java) {
            ImportParser.parse(
                "hysteria2://secret@one.example:8443?obfs=salamander#Hy2",
                ProfileSource.Link,
                "Профиль",
            )
        }
    }

    @Test
    fun `gecko is preserved for the official embedded core`() {
        val candidate = ImportParser.parse(
            "hysteria2://Abc_-123@one.example:8443?obfs=gecko&obfs-password=cover#Gecko",
            ProfileSource.Link,
        ) as ImportCandidate.Managed
        val outbound = candidate.servers.single().outbound
        val obfs = outbound["obfs"] as JsonObject
        assertEquals("Abc_-123", outbound.string("password"))
        assertEquals("gecko", obfs.string("type"))
        assertEquals("cover", obfs.string("password"))
    }

    @Test
    fun `hysteria2 official port union ipv6 ech and percent encoded name are preserved`() {
        val candidate = ImportParser.parse(
            "hy2://user%3Apass@[2001:db8::1]:443,20000-20002" +
                "?obfs=gecko&obfs-password=cover&hop-interval=15s&ECH=AAECAw#Synthetic%20HY2",
            ProfileSource.Subscription,
        ) as ImportCandidate.Managed
        val server = candidate.servers.single()
        val outbound = server.outbound
        assertEquals("Synthetic HY2", server.displayName)
        assertEquals(
            "hy2://user%3Apass@[2001:db8::1]:443,20000-20002" +
                "?obfs=gecko&obfs-password=cover&hop-interval=15s&ECH=AAECAw#Synthetic%20HY2",
            outbound.string("uri"),
        )
        assertEquals("2001:db8::1", outbound.string("server"))
        assertEquals("443", (outbound["server_port"] as JsonPrimitive).content)
        assertEquals("user:pass", outbound.string("password"))
        assertEquals("15s", outbound.string("hop_interval"))
        assertEquals(2, (outbound["server_ports"] as JsonArray).size)
        val tls = outbound["tls"] as JsonObject
        assertTrue((tls["ech"] as JsonObject).string("config").orEmpty().contains("BEGIN ECH CONFIGS"))
    }

    @Test
    fun `hysteria2 schemes and opaque URI details survive without entering identity`() {
        val pin = "ab".repeat(32)
        val links = listOf(
            "hy2://user%3Apass@[2001:db8::1]:443" +
                "?insecure=1&pinSHA256=$pin&vendor=%2F%2f%25&vendor=second+value#Name%20one",
            "hysteria2://user%3Apass@[2001:db8::1]:443" +
                "?insecure=1&pinSHA256=$pin&vendor=%2F%2f%25&vendor=second+value#Name%20two",
        )

        val servers = links.map { link ->
            (ImportParser.parse(link, ProfileSource.Link) as ImportCandidate.Managed).servers.single()
        }

        links.zip(servers).forEach { (link, server) ->
            assertEquals(link, server.outbound.string("uri"))
            assertTrue(server.importWarnings.any { it.contains("сохранены в исходной URI") })
            assertFalse(server.importWarnings.any { it.contains("не переносятся") })
            assertFalse(link in server.identityKey)
            assertFalse("user:pass" in server.identityKey)
            assertFalse("$pin" in server.identityKey)
        }
        assertEquals(servers[0].identityKey, servers[1].identityKey)

        val tags = ManagedProfileFactory.stableTags(servers)
        assertEquals(2, tags.distinct().size)
        assertFalse(tags.any { it.contains("user") || it.contains(pin) })
    }

    @Test
    fun `hysteria2 publication changes runtime identity but preserve logical split identity`() {
        val base = "hysteria2://secret@one.example:443?insecure=1"
        val first = (ImportParser.parse(
            "$base&pinSHA256=${"00".repeat(32)}&vendor=one#Same",
            ProfileSource.Link,
        ) as ImportCandidate.Managed).servers.single()
        val second = (ImportParser.parse(
            "$base&pinSHA256=${"11".repeat(32)}&vendor=two#Same",
            ProfileSource.Link,
        ) as ImportCandidate.Managed).servers.single()

        assertFalse(first.identityKey == second.identityKey)
        assertEquals(
            ManagedProfileFactory.stableMemberKeys(listOf(first)),
            ManagedProfileFactory.stableMemberKeys(listOf(second)),
        )
        assertEquals(first.refreshIdentityKey, second.refreshIdentityKey)
        assertFalse(first.identityKey.contains("secret"))
        assertFalse(second.identityKey.contains("secret"))
        assertFalse(first.refreshIdentityKey.contains("secret"))
    }

    @Test
    fun `native hysteria2 builder omits optional URI unless supplied`() {
        val legacy = ProtocolOutboundBuilders.hysteria2(
            displayName = "Legacy",
            server = "one.example",
            serverPort = 443,
            password = "secret",
        ).outbound
        assertFalse("uri" in legacy)

        val uri = "hysteria2://secret@one.example:443?x=%2F#Legacy"
        val imported = ProtocolOutboundBuilders.hysteria2(
            displayName = "Imported",
            server = "one.example",
            serverPort = 443,
            password = "secret",
            uri = uri,
        ).outbound
        assertEquals(uri, imported.string("uri"))
    }

    @Test
    fun `bandwidth hints survive their unit suffix`() {
        val candidate = ImportParser.parse(
            "hysteria2://secret@one.example:8443?up=100%20Mbps&down=200#Hy2",
            ProfileSource.Link,
            "Профиль",
        ) as ImportCandidate.Managed

        val outbound = candidate.servers.single().outbound
        assertEquals("100", (outbound["up_mbps"] as JsonPrimitive).content)
        assertEquals("200", (outbound["down_mbps"] as JsonPrimitive).content)
    }

    @Test
    fun `repeated warnings are collapsed with a count`() {
        val link = "vless://11111111-1111-4111-8111-111111111111@%s:443" +
            "?security=tls&sni=example.org&spx=%%2F#%s"
        val candidate = ImportParser.parse(
            (1..3).joinToString("\n") { link.format("host$it.example", "S$it") },
            ProfileSource.Subscription,
            "Подписка",
        ) as ImportCandidate.Managed

        assertEquals(3, candidate.servers.size)
        val warning = candidate.importWarnings.single { it.contains("spx") }
        assertTrue(warning, warning.contains("серверов: 3"))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun encodeURIComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

}
