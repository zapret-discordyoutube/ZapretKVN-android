package io.github.zapretkvn.android.config

import io.github.zapretkvn.android.hardening.TunMtuMode
import io.github.zapretkvn.android.hardening.VpnHidingOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.zapretkvn.android.routing.InstalledRuleSets
import io.github.zapretkvn.android.routing.ManagedRoutingRule
import io.github.zapretkvn.android.routing.RoutingConfigEditor
import io.github.zapretkvn.android.routing.RoutingMatchType
import io.github.zapretkvn.android.routing.RoutingPreset
import io.github.zapretkvn.android.routing.RoutingRuleAction

class RuntimeConfigBuilderTest {
    @Test
    fun `blocked packages get first DNS and route reject rules without changing stored JSON`() {
        val stored = validConfig()
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.FromJson,
                blockedPackages = setOf("com.example.beta", "com.example.alpha"),
            ),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val dnsRule = ((root["dns"] as JsonObject)["rules"] as JsonArray)
            .first() as JsonObject
        val routeRule = ((root["route"] as JsonObject)["rules"] as JsonArray)
            .first() as JsonObject
        val expectedPackages = """["com.example.alpha","com.example.beta"]"""

        assertEquals("reject", dnsRule.string("action"))
        assertEquals(expectedPackages, dnsRule["package_name"].toString())
        assertEquals("reject", routeRule.string("action"))
        assertEquals(expectedPackages, routeRule["package_name"].toString())
        assertFalse("package_name" in stored)
    }

    @Test
    fun `invalid blocked package fails before runtime creation`() {
        val result = RuntimeConfigBuilder.build(
            validConfig(),
            options = RuntimeConfigOptions(
                blockedPackages = setOf("not a package"),
            ),
        )

        assertTrue(result is RuntimeConfigResult.Invalid)
        assertTrue((result as RuntimeConfigResult.Invalid).message.contains("заблокированных"))
    }

    @Test
    fun `rootless hardening protects raw profiles and can be explicitly disabled`() {
        val raw = validConfig(
            rootExtra = """
                ,"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090"}}
            """.trimIndent(),
        )
            .replace("\"tag\":\"zapret-proxy\"", "\"tag\":\"user-selector\"")
            .replace("\"final\":\"zapret-proxy\"", "\"final\":\"user-selector\"")
            .replace(
                "\"inbounds\":[{",
                "\"inbounds\":[{\"type\":\"mixed\",\"listen\":\"127.0.0.1\",\"listen_port\":1080},{",
            )

        val protected = RuntimeConfigBuilder.build(raw)
        val advanced = RuntimeConfigBuilder.build(
            raw,
            options = RuntimeConfigOptions(
                vpnHiding = VpnHidingOptions(blockLocalEndpoints = false),
            ),
        )

        assertTrue(protected is RuntimeConfigResult.Invalid)
        assertTrue((protected as RuntimeConfigResult.Invalid).message.contains("localhost"))
        assertTrue(advanced is RuntimeConfigResult.Ready)
        assertTrue("external_controller" in (advanced as RuntimeConfigResult.Ready).json)
    }

    @Test
    fun `mtu normalization changes only effective runtime`() {
        val stored = validConfig()
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                vpnHiding = VpnHidingOptions(tunMtuMode = TunMtuMode.Normalize1500),
            ),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val tun = (root["inbounds"] as JsonArray).single() as JsonObject

        assertEquals("1500", (tun["mtu"] as JsonPrimitive).content)
        assertFalse("stored profile must not gain an MTU", "\"mtu\"" in stored)
    }

    @Test
    fun `wireguard gets Android mtu only in runtime`() {
        val stored = validConfig(
            rootExtra = """
                ,"endpoints":[
                  {"type":"wireguard","tag":"wg-default"},
                  {"type":"wireguard","tag":"wg-explicit","mtu":1376,"detour":"custom-direct"},
                  {"type":"other","tag":"untouched"}
                ]
            """.trimIndent(),
        )

        val result = RuntimeConfigBuilder.build(stored) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val endpoints = (root["endpoints"] as JsonArray)
            .map { it as JsonObject }
        val tun = (root["inbounds"] as JsonArray).single() as JsonObject

        assertEquals("1280", (endpoints[0]["mtu"] as JsonPrimitive).content)
        assertEquals(null, endpoints[0].string("detour"))
        assertEquals("1376", (endpoints[1]["mtu"] as JsonPrimitive).content)
        assertEquals("custom-direct", endpoints[1].string("detour"))
        assertFalse("mtu" in endpoints[2])
        assertEquals("1280", (tun["mtu"] as JsonPrimitive).content)
        assertFalse("stored profile must stay untouched", "1280" in stored)
        assertFalse("stored profile must not gain a detour", "zapret-wireguard-direct" in stored)
    }

    @Test
    fun `wireguard clamps Android tun mtu in every DNS mode`() {
        val stored = validConfig(
            rootExtra = """
                ,"endpoints":[
                  {"type":"wireguard","tag":"wg","mtu":1280,"address":["10.0.0.2/32"],
                   "private_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                   "peers":[{"address":"192.0.2.1","port":51820,
                             "public_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                             "allowed_ips":["0.0.0.0/0"]}]}
                ]
            """.trimIndent(),
        )

        DnsMode.entries.forEach { mode ->
            val result = RuntimeConfigBuilder.build(
                stored,
                options = RuntimeConfigOptions(
                    dnsMode = mode,
                    vpnHiding = VpnHidingOptions(tunMtuMode = TunMtuMode.Normalize1500),
                ),
            ) as RuntimeConfigResult.Ready
            val root = JsonConfig.parse(result.json) as JsonObject
            val tun = (root["inbounds"] as JsonArray).single() as JsonObject

            assertEquals("DNS mode $mode", "1280", (tun["mtu"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `wireguard profile mode preserves explicit tun mtu`() {
        val stored = validConfig(
            tunExtra = ""","mtu":1420""",
            rootExtra = """
                ,"endpoints":[{"type":"wireguard","tag":"wg","mtu":1280}]
            """.trimIndent(),
        )
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                vpnHiding = VpnHidingOptions(tunMtuMode = TunMtuMode.CoreDefault),
            ),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val tun = (root["inbounds"] as JsonArray).single() as JsonObject

        assertEquals("1420", (tun["mtu"] as JsonPrimitive).content)
    }

    @Test
    fun `wireguard profile mode derives tun mtu when profile omitted it`() {
        val stored = validConfig(
            rootExtra = """
                ,"endpoints":[{"type":"wireguard","tag":"wg","mtu":1280}]
            """.trimIndent(),
        )
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                vpnHiding = VpnHidingOptions(tunMtuMode = TunMtuMode.CoreDefault),
            ),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val tun = (root["inbounds"] as JsonArray).single() as JsonObject
        val storedRoot = JsonConfig.parse(stored) as JsonObject
        val storedTun = (storedRoot["inbounds"] as JsonArray).single() as JsonObject

        assertEquals("1280", (tun["mtu"] as JsonPrimitive).content)
        assertFalse("stored profile must not gain a TUN MTU", "mtu" in storedTun)
    }

    @Test
    fun `runtime copy owns logging packages and managed interrupt without mutating source`() {
        val stored = validConfig(
            tunExtra = """
                ,"include_package":["stored.include"]
            """.trimIndent(),
            rootExtra = """
                ,"log":{"level":"trace","output":"/sdcard/secret.log","timestamp":true}
                ,"extended_unknown":{"keep":42}
            """.trimIndent(),
        )

        val result = RuntimeConfigBuilder.build(stored, enableTrafficStats = true) as RuntimeConfigResult.Ready
        val runtime = JsonConfig.parse(result.json) as JsonObject
        val tun = ((runtime["inbounds"] as JsonArray).single() as JsonObject)
        val log = runtime["log"] as JsonObject
        val selector = (runtime["outbounds"] as JsonArray)
            .map { it as JsonObject }
            .first { it.string("tag") == ConfigAnalyzer.MANAGED_SELECTOR_TAG }
        val experimental = runtime["experimental"] as JsonObject

        assertFalse("include_package" in tun)
        assertFalse("exclude_package" in tun)
        assertEquals("warn", (log["level"] as JsonPrimitive).content)
        assertFalse("output" in log)
        assertTrue((log["timestamp"] as JsonPrimitive).boolean)
        assertTrue((selector["interrupt_exist_connections"] as JsonPrimitive).boolean)
        assertTrue(experimental["clash_api"] is JsonObject)
        assertFalse(
            "managed traffic manager must not open an external listener",
            "external_controller" in (experimental["clash_api"] as JsonObject),
        )
        assertEquals(42, ((((runtime["extended_unknown"] as JsonObject)["keep"]) as JsonPrimitive).content.toInt()))
        assertTrue("stored profile must stay untouched", "stored.include" in stored)
        assertTrue("stored profile must keep log output", "/sdcard/secret.log" in stored)
    }

    @Test
    fun `managed runtime removes every external Clash listener field without mutating source`() {
        val stored = validConfig(
            rootExtra = """
                ,"experimental":{"clash_api":{
                  "external_controller":"0.0.0.0:9090",
                  "external_controller_tls":"0.0.0.0:9443",
                  "secret":"controller-secret",
                  "external_ui":"ui",
                  "external_ui_download_url":"https://example.test/ui.zip",
                  "external_ui_download_detour":"direct",
                  "access_control_allow_origin":["*"],
                  "access_control_allow_private_network":true
                }}
            """.trimIndent(),
        )

        val result = RuntimeConfigBuilder.build(stored, enableTrafficStats = true) as RuntimeConfigResult.Ready
        val experimental = (JsonConfig.parse(result.json) as JsonObject)["experimental"] as JsonObject
        val clash = experimental["clash_api"] as JsonObject

        assertTrue(clash.isEmpty())
        assertTrue("0.0.0.0:9090" in stored)
        assertTrue("controller-secret" in stored)
    }

    @Test
    fun `valid full dual stack config produces runtime json`() {
        val runtime = RuntimeConfigBuilder.build(validConfig()) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(runtime.json) as JsonObject

        assertFalse("release runtime must not synthesize a traffic manager", "experimental" in root)
    }

    @Test
    fun `updater route is temporary package scoped and uses selected proxy`() {
        val stored = validConfig()
        val normal = RuntimeConfigBuilder.build(stored) as RuntimeConfigResult.Ready
        val temporary = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Automatic,
                updaterPackageName = "io.github.zapretkvn.android",
            ),
        ) as RuntimeConfigResult.Ready
        val normalRoot = JsonConfig.parse(normal.json) as JsonObject
        val normalRules = ((normalRoot["route"] as JsonObject)["rules"] as? JsonArray).orEmpty()
            .map { it as JsonObject }
        val temporaryRoot = JsonConfig.parse(temporary.json) as JsonObject
        val temporaryRules = ((temporaryRoot["route"] as JsonObject)["rules"] as JsonArray)
            .map { it as JsonObject }
        val updaterRule = temporaryRules.first { "package_name" in it }
        val dnsRules = (((temporaryRoot["dns"] as JsonObject)["rules"] as JsonArray))
            .map { it as JsonObject }

        assertFalse(normalRules.any { "package_name" in it })
        assertEquals(
            "io.github.zapretkvn.android",
            ((updaterRule["package_name"] as JsonArray).single() as JsonPrimitive).content,
        )
        assertEquals("[\"git.zapret.moe\"]", updaterRule["domain"].toString())
        assertEquals("[]", updaterRule["domain_suffix"].toString())
        assertEquals("zapret-proxy", updaterRule.string("outbound"))
        assertTrue(
            dnsRules.any { rule ->
                rule.string("server") == "zapret-secure-dns" &&
                    rule["package_name"] == updaterRule["package_name"]
            },
        )
        assertFalse("stored profile must stay untouched", "git.zapret.moe" in stored)
    }

    @Test
    fun `zero and two tun inbounds are rejected`() {
        val zero = validConfig().replace(
            """"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}]""",
            """"inbounds":[]""",
        )
        val two = validConfig().replace(
            """"inbounds":[""",
            """"inbounds":[{"type":"tun","address":["10.0.0.1/30","fd00::1/126"],"auto_route":true},""",
        )

        assertTrue(RuntimeConfigBuilder.build(zero) is RuntimeConfigResult.Invalid)
        assertTrue(RuntimeConfigBuilder.build(two) is RuntimeConfigResult.Invalid)
    }

    @Test
    fun `partial or single stack routes are rejected`() {
        val singleStack = validConfig().replace(
            """["172.19.0.1/30","fdfe:dcba:9876::1/126"]""",
            """["172.19.0.1/30"]""",
        )
        val partial = validConfig(
            tunExtra = """, "route_address":["10.0.0.0/8","::/0"]""",
        )

        assertTrue(RuntimeConfigBuilder.build(singleStack) is RuntimeConfigResult.Invalid)
        assertTrue(RuntimeConfigBuilder.build(partial) is RuntimeConfigResult.Invalid)
    }

    @Test
    fun `package conflict route exclusion and forbidden dial fields are rejected`() {
        val conflict = validConfig(
            tunExtra = """, "include_package":["a"], "exclude_package":["b"]""",
        )
        val excluded = validConfig(
            tunExtra = """, "route_exclude_address":["192.168.0.0/16"]""",
        )
        val routeSet = validConfig(
            tunExtra = """, "route_address_set":["private"]""",
        )
        val forbidden = validConfig().replace(
            """"type":"direct","tag":"direct"""",
            """"type":"direct","tag":"direct","routing_mark":7""",
        )

        assertTrue(RuntimeConfigBuilder.build(conflict) is RuntimeConfigResult.Invalid)
        assertTrue(RuntimeConfigBuilder.build(excluded) is RuntimeConfigResult.Invalid)
        assertTrue(RuntimeConfigBuilder.build(routeSet) is RuntimeConfigResult.Invalid)
        assertTrue(RuntimeConfigBuilder.build(forbidden) is RuntimeConfigResult.Invalid)
    }

    @Test
    fun `raw profile keeps explicit log level but never writes runtime log to disk`() {
        val raw = validConfig(rootExtra = """, "log":{"level":"error","output":"runtime.log"}""")
            .replace("\"tag\":\"zapret-proxy\"", "\"tag\":\"user-selector\"")
            .replace("\"final\":\"zapret-proxy\"", "\"final\":\"user-selector\"")
        val runtime = RuntimeConfigBuilder.build(raw) as RuntimeConfigResult.Ready
        val log = (JsonConfig.parse(runtime.json) as JsonObject)["log"] as JsonObject

        assertEquals("error", (log["level"] as JsonPrimitive).content)
        assertFalse("output" in log)
    }

    @Test
    fun `raw profile without log never inherits sing box trace default`() {
        val raw = validConfig()
            .replace("\"tag\":\"zapret-proxy\"", "\"tag\":\"user-selector\"")
            .replace("\"final\":\"zapret-proxy\"", "\"final\":\"user-selector\"")
        val runtime = RuntimeConfigBuilder.build(raw) as RuntimeConfigResult.Ready
        val log = (JsonConfig.parse(runtime.json) as JsonObject)["log"] as JsonObject

        assertEquals("warn", (log["level"] as JsonPrimitive).content)
        assertFalse("stored profile must stay untouched", "\"log\"" in raw)
    }

    @Test
    fun `raw verbose log is clamped in runtime without mutating source`() {
        val raw = validConfig(rootExtra = """, "log":{"level":"trace","timestamp":true}""")
            .replace("\"tag\":\"zapret-proxy\"", "\"tag\":\"user-selector\"")
            .replace("\"final\":\"zapret-proxy\"", "\"final\":\"user-selector\"")
        val runtime = RuntimeConfigBuilder.build(raw) as RuntimeConfigResult.Ready
        val log = (JsonConfig.parse(runtime.json) as JsonObject)["log"] as JsonObject

        assertEquals("warn", (log["level"] as JsonPrimitive).content)
        assertTrue((log["timestamp"] as JsonPrimitive).boolean)
        assertTrue("stored profile must keep its explicit level", "\"level\":\"trace\"" in raw)
    }

    @Test
    fun `auto route and auto interface detection are mandatory`() {
        val noAutoRoute = validConfig().replace("\"auto_route\":true", "\"auto_route\":false")
        val noAutoInterface = validConfig().replace(
            "\"auto_detect_interface\":true",
            "\"auto_detect_interface\":false",
        )

        assertTrue(RuntimeConfigBuilder.build(noAutoRoute) is RuntimeConfigResult.Invalid)
        assertTrue(RuntimeConfigBuilder.build(noAutoInterface) is RuntimeConfigResult.Invalid)
    }

    @Test
    fun `managed Android DNS is a runtime-only zapret overlay`() {
        val stored = validConfig(rootExtra = ",\"unknown_dns_owner\":true")
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Android),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val dns = root["dns"] as JsonObject
        val servers = dns["servers"] as JsonArray
        val routeRules = ((root["route"] as JsonObject)["rules"] as JsonArray)

        assertEquals(
            listOf("zapret-android-dns", "zapret-dns-override"),
            servers.map { (it as JsonObject).string("tag") },
        )
        assertEquals("zapret-android-dns", (dns["final"] as JsonPrimitive).content)
        assertTrue(routeRules.any { (it as JsonObject).string("action") == "hijack-dns" })
        assertTrue((root["unknown_dns_owner"] as JsonPrimitive).boolean)
        assertFalse("stored JSON must not be rewritten", "zapret-android-dns" in stored)
    }

    @Test
    fun `managed DNS override is exact normalized and ordered after reject`() {
        val stored = validConfig(
            rootExtra = """
                ,"dns":{"rules":[{"domain":["blocked.test"],"action":"reject"}]}
            """.trimIndent(),
        )
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Secure,
                dnsOverride = DnsOverride(
                    hostname = " NTC.PARTY. ",
                    ipv4Address = " 130.255.77.28 ",
                ),
            ),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val dns = root["dns"] as JsonObject
        val servers = (dns["servers"] as JsonArray).map { it as JsonObject }
        val rules = (dns["rules"] as JsonArray).map { it as JsonObject }
        val overrideServer = servers.single { it.string("tag") == "zapret-dns-override" }
        val predefined = overrideServer["predefined"] as JsonObject

        assertEquals("130.255.77.28", (((predefined["ntc.party"] as JsonArray).single()) as JsonPrimitive).content)
        assertEquals("reject", rules[0].string("action"))
        assertEquals("zapret-dns-override", rules[1].string("server"))
        assertTrue(rules[1]["domain"].toString().contains("ntc.party"))
        assertFalse("stored profile must stay untouched", "zapret-dns-override" in stored)
    }

    @Test
    fun `disabled override and FromJson do not add managed hosts`() {
        val stored = validConfig()
        val disabled = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Android,
                dnsOverride = DnsOverride(enabled = false),
            ),
        ) as RuntimeConfigResult.Ready
        val fromJson = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.FromJson,
                dnsOverride = DnsOverride(),
            ),
        ) as RuntimeConfigResult.Ready

        assertFalse("zapret-dns-override" in disabled.json)
        assertFalse("zapret-dns-override" in fromJson.json)
    }

    @Test
    fun `FromJson uses app scoped TLS sniff and routes health through selected outbound`() {
        val stored = validConfig(
            rootExtra = """
                ,"dns":{"servers":[{"type":"udp","tag":"profile-dns","server":"192.0.2.53"}],"final":"profile-dns"}
            """.trimIndent(),
        )

        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.FromJson,
                healthCheckPackageName = "io.github.zapretkvn.android.debug",
            ),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val dns = root["dns"] as JsonObject
        val rules = ((root["route"] as JsonObject)["rules"] as JsonArray)
            .map { it as JsonObject }

        assertEquals("profile-dns", dns.string("final"))
        assertEquals(1, (dns["servers"] as JsonArray).size)
        assertEquals("sniff", rules[0].string("action"))
        assertEquals("tcp", rules[0].string("network"))
        assertEquals("tls", ((rules[0]["sniffer"] as JsonArray).single() as JsonPrimitive).content)
        assertTrue(rules[0]["package_name"].toString().contains("io.github.zapretkvn.android.debug"))
        assertEquals("zapret-proxy", rules[1].string("outbound"))
        assertEquals("tcp", rules[1].string("network"))
        assertEquals("443", (rules[1]["port"] as JsonPrimitive).content)
        assertTrue(rules[1]["domain"].toString().contains("cp.cloudflare.com"))
        assertTrue(rules[1]["package_name"].toString().contains("io.github.zapretkvn.android.debug"))
        assertFalse("stored profile must stay untouched", "\"sniff\"" in stored)
    }

    @Test
    fun `FromJson without profile DNS uses minimal Android fallback`() {
        val stored = validConfig()

        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(dnsMode = DnsMode.FromJson),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val dns = root["dns"] as JsonObject
        val route = root["route"] as JsonObject
        val rules = (route["rules"] as JsonArray).map { it as JsonObject }

        assertEquals("zapret-android-dns", dns.string("final"))
        assertEquals("local", ((dns["servers"] as JsonArray).single() as JsonObject).string("type"))
        assertEquals("zapret-android-dns", route.string("default_domain_resolver"))
        assertEquals("hijack-dns", rules[0].string("action"))
        assertEquals("zapret-proxy", rules[1].string("outbound"))
        assertFalse("stored profile must stay without a DNS section", "\"dns\"" in stored)
    }

    @Test
    fun `invalid enabled override fails before core start`() {
        val result = RuntimeConfigBuilder.build(
            validConfig(),
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Android,
                dnsOverride = DnsOverride(hostname = "bad host", ipv4Address = "999.1.1.1"),
            ),
        )

        assertTrue(result is RuntimeConfigResult.Invalid)
        assertTrue((result as RuntimeConfigResult.Invalid).message.contains("DNS-переопределение"))
    }

    @Test
    fun `secure DNS uses real IP parallel fallback and no fakeip`() {
        val result = RuntimeConfigBuilder.build(
            validConfig(),
            options = RuntimeConfigOptions(dnsMode = DnsMode.Secure),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val dns = root["dns"] as JsonObject
        val servers = (dns["servers"] as JsonArray).map { it as JsonObject }
        val rules = (dns["rules"] as JsonArray).map { it as JsonObject }
        val fallback = servers.first { it.string("tag") == "zapret-secure-dns" }

        assertEquals("parallel", fallback.string("strategy"))
        val expected = listOf(
            Triple("zapret-doh-1", "9.9.9.9", "dns.quad9.net"),
            Triple("zapret-doh-2", "8.8.8.8", "dns.google"),
            Triple("zapret-doh-3", "208.67.222.222", "dns.opendns.com"),
            Triple("zapret-doh-4", "1.1.1.1", "cloudflare-dns.com"),
            Triple("zapret-doh-5", "77.88.8.8", "common.dot.dns.yandex.net"),
        )
        expected.forEach { (tag, address, serverName) ->
            val server = servers.first { it.string("tag") == tag }
            assertEquals(address, server.string("server"))
            assertEquals(
                serverName,
                ((server["tls"] as JsonObject)["server_name"] as JsonPrimitive).content,
            )
            assertEquals("zapret-proxy", server.string("detour"))
        }
        assertEquals(
            expected.map(Triple<String, String, String>::first),
            (fallback["servers"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertFalse(servers.any { it.string("type") == "fakeip" })
        assertEquals("4096", (dns["cache_capacity"] as JsonPrimitive).content)
        assertTrue((dns["reverse_mapping"] as JsonPrimitive).boolean)
        assertTrue(
            rules.any {
                it.string("server") == "zapret-secure-dns" &&
                    it.string("strategy") == "ipv4_only"
            },
        )
    }

    @Test
    fun `switching a runtime copy from secure to Android removes every generated DoH`() {
        val secure = RuntimeConfigBuilder.build(
            validConfig(),
            options = RuntimeConfigOptions(dnsMode = DnsMode.Secure),
        ) as RuntimeConfigResult.Ready
        val android = RuntimeConfigBuilder.build(
            secure.json,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Android),
        ) as RuntimeConfigResult.Ready
        val dns = (JsonConfig.parse(android.json) as JsonObject)["dns"] as JsonObject
        val tags = (dns["servers"] as JsonArray).map { (it as JsonObject).string("tag") }

        assertFalse(tags.any { it?.startsWith("zapret-doh-") == true })
        assertFalse("zapret-secure-dns" in tags)
        assertEquals("zapret-android-dns", dns.string("final"))
    }

    @Test
    fun `automatic DNS mirrors direct domain rules and final proxy`() {
        val stored = validConfig().replace(
            "\"auto_detect_interface\":true,\"final\":\"zapret-proxy\"",
            "\"auto_detect_interface\":true,\"rules\":[{\"domain_suffix\":[\".ru\"],\"action\":\"route\",\"outbound\":\"direct\"}],\"final\":\"zapret-proxy\"",
        )
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Automatic,
                proxyIpv4Only = false,
            ),
        ) as RuntimeConfigResult.Ready
        val dns = (JsonConfig.parse(result.json) as JsonObject)["dns"] as JsonObject
        val rules = (dns["rules"] as JsonArray).map { it as JsonObject }
        val ru = rules.first { it["domain_suffix"]?.toString()?.contains(".ru") == true }

        assertEquals("zapret-android-dns", ru.string("server"))
        assertEquals("zapret-secure-dns", (dns["final"] as JsonPrimitive).content)
        assertFalse(rules.any { it.string("strategy") == "ipv4_only" })
    }

    @Test
    fun `optional IPv4 only filters proxy DNS without changing direct rules or stored JSON`() {
        val stored = validConfig().replace(
            "\"auto_detect_interface\":true,\"final\":\"zapret-proxy\"",
            "\"auto_detect_interface\":true,\"rules\":[" +
                "{\"domain_suffix\":[\".ru\"],\"action\":\"route\",\"outbound\":\"direct\"}," +
                "{\"domain_suffix\":[\".proxy.test\"],\"action\":\"route\",\"outbound\":\"zapret-proxy\"}" +
                "],\"final\":\"zapret-proxy\"",
        )
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Automatic,
                proxyIpv4Only = true,
            ),
        ) as RuntimeConfigResult.Ready
        val dns = (JsonConfig.parse(result.json) as JsonObject)["dns"] as JsonObject
        val rules = (dns["rules"] as JsonArray).map { it as JsonObject }
        val direct = rules.first { it["domain_suffix"]?.toString()?.contains(".ru") == true }
        val secure = rules.first { it["domain_suffix"]?.toString()?.contains(".proxy.test") == true }
        val secureFinal = rules.first {
            it.string("server") == "zapret-secure-dns" && "domain_suffix" !in it
        }

        assertEquals("zapret-android-dns", direct.string("server"))
        assertFalse("strategy" in direct)
        assertEquals("zapret-secure-dns", secure.string("server"))
        assertEquals("ipv4_only", secure.string("strategy"))
        assertEquals("ipv4_only", secureFinal.string("strategy"))
        assertEquals("prefer_ipv4", (dns["strategy"] as JsonPrimitive).content)
        assertFalse("stored JSON must not gain a DNS strategy", "ipv4_only" in stored)

        val android = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Android,
                proxyIpv4Only = true,
            ),
        ) as RuntimeConfigResult.Ready
        val androidDns = (JsonConfig.parse(android.json) as JsonObject)["dns"] as JsonObject
        val androidRules = (androidDns["rules"] as JsonArray).map { it as JsonObject }
        assertTrue(
            androidRules.any {
                it.string("strategy") == "ipv4_only" &&
                    it["domain"]?.toString()?.contains("cp.cloudflare.com") == true
            },
        )
        assertTrue(
            androidRules.any {
                it.string("strategy") == "ipv4_only" && "domain" !in it
            },
        )
        assertTrue(
            androidRules.any {
                it["domain_suffix"]?.toString()?.contains(".ru") == true &&
                    "strategy" !in it
            },
        )

        val androidRoot = JsonConfig.parse(android.json) as JsonObject
        val androidRouteRules = ((androidRoot["route"] as JsonObject)["rules"] as JsonArray)
            .map { it as JsonObject }
        val healthRoute = androidRouteRules.first {
            it.string("outbound") == "zapret-proxy" && "domain" in it
        }
        val healthDomains = (healthRoute["domain"] as JsonArray).map {
            (it as JsonPrimitive).content
        }
        assertEquals(
            listOf("cp.cloudflare.com", "connectivitycheck.gstatic.com", "dns.opendns.com"),
            healthDomains,
        )
        val rebuiltAndroid = RuntimeConfigBuilder.build(
            android.json,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Android,
                proxyIpv4Only = true,
            ),
        ) as RuntimeConfigResult.Ready
        val rebuiltRoot = JsonConfig.parse(rebuiltAndroid.json) as JsonObject
        val rebuiltRouteRules = ((rebuiltRoot["route"] as JsonObject)["rules"] as JsonArray)
            .map { it as JsonObject }
        assertEquals(
            1,
            rebuiltRouteRules.count { rule ->
                val domains = rule["domain"] as? JsonArray
                domains?.any { (it as JsonPrimitive).content == "cp.cloudflare.com" } == true
            },
        )

        val directFinalStored = stored.replace(
            "\"final\":\"zapret-proxy\"",
            "\"final\":\"direct\"",
        )
        val directFinal = RuntimeConfigBuilder.build(
            directFinalStored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Automatic,
                proxyIpv4Only = true,
            ),
        ) as RuntimeConfigResult.Ready
        val directFinalDns = (JsonConfig.parse(directFinal.json) as JsonObject)["dns"] as JsonObject
        val directFinalRules = (directFinalDns["rules"] as JsonArray).map { it as JsonObject }

        assertEquals("zapret-android-dns", directFinalDns.string("final"))
        assertTrue(
            directFinalRules.any {
                it.string("server") == "zapret-secure-dns" &&
                    it.string("strategy") == "ipv4_only" &&
                    "domain_suffix" in it
            },
        )
        assertFalse(
            directFinalRules.any {
                it.string("server") == "zapret-secure-dns" && "domain_suffix" !in it
            },
        )

        val directFinalAndroid = RuntimeConfigBuilder.build(
            directFinalStored,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Android,
                proxyIpv4Only = true,
            ),
        ) as RuntimeConfigResult.Ready
        val directFinalAndroidDns =
            (JsonConfig.parse(directFinalAndroid.json) as JsonObject)["dns"] as JsonObject
        val directFinalAndroidRules = (directFinalAndroidDns["rules"] as JsonArray)
            .map { it as JsonObject }
        assertTrue(
            directFinalAndroidRules.any {
                it["domain_suffix"]?.toString()?.contains(".proxy.test") == true &&
                    it.string("strategy") == "ipv4_only"
            },
        )
        assertTrue(
            directFinalAndroidRules.last().let { rule ->
                rule.string("server") == "zapret-android-dns" && "strategy" !in rule
            },
        )
    }

    @Test
    fun `automatic DNS mirrors managed domain set but not IP-only set`() {
        val routed = RoutingConfigEditor.apply(
            raw = validConfig(),
            preset = RoutingPreset.BypassLan,
            manualRules = listOf(
                ManagedRoutingRule(
                    RoutingMatchType.DomainSuffix,
                    listOf(".direct.example"),
                    RoutingRuleAction.Direct,
                ),
                ManagedRoutingRule(
                    RoutingMatchType.IpCidr,
                    listOf("192.0.2.0/24"),
                    RoutingRuleAction.Direct,
                ),
            ),
            installed = InstalledRuleSets(1, emptyMap()),
        ).json
        val runtime = RuntimeConfigBuilder.build(
            routed,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Automatic),
        ) as RuntimeConfigResult.Ready

        assertTrue(runtime.json.contains("zapret-user-domainsuffix-"))
        val dns = (JsonConfig.parse(runtime.json) as JsonObject)["dns"] as JsonObject
        val dnsText = (dns["rules"] as JsonArray).toString()
        assertTrue(dnsText.contains("zapret-user-domainsuffix-"))
        assertFalse(dnsText.contains("zapret-user-ipcidr-"))
    }

    @Test
    fun `LKG overlay changes only selected server outbound in runtime`() {
        val stored = validConfig().replace(
            "{\"type\":\"direct\",\"tag\":\"server-a\"}",
            "{\"type\":\"vless\",\"tag\":\"server-a\",\"server\":\"vpn.example\",\"server_port\":443,\"uuid\":\"00000000-0000-4000-8000-000000000000\",\"tls\":{\"enabled\":true,\"server_name\":\"vpn.example\"}}",
        )
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                bootstrapHost = BootstrapHostOverlay("server-a", "vpn.example", listOf("203.0.113.10")),
            ),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val server = (root["outbounds"] as JsonArray)
            .map { it as JsonObject }
            .first { it.string("tag") == "server-a" }
        val dns = root["dns"] as JsonObject

        assertEquals("vpn.example", server.string("server"))
        assertEquals("zapret-bootstrap-lkg", server.string("domain_resolver"))
        assertEquals("zapret-android-dns", dns.string("final"))
        assertEquals(2, (dns["servers"] as JsonArray).size)
        assertFalse("stored profile must stay untouched", "domain_resolver" in stored)
    }

    @Test
    fun `managed DNS rejects IPv4 tun without room for internal resolver`() {
        val slash32 = validConfig().replace("172.19.0.1/30", "172.19.0.1/32")
        val result = RuntimeConfigBuilder.build(
            slash32,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Automatic),
        )

        assertTrue(result is RuntimeConfigResult.Invalid)
        assertTrue((result as RuntimeConfigResult.Invalid).message.contains("/30"))
    }

    @Test
    fun `Android DNS works for direct-only raw profile without selector`() {
        val directOnly = validConfig()
            .replace(
                "{\"type\":\"selector\",\"tag\":\"zapret-proxy\",\"outbounds\":[\"server-a\"],\"default\":\"server-a\"},",
                "",
            )
            .replace("\"final\":\"zapret-proxy\"", "\"final\":\"direct\"")
        val result = RuntimeConfigBuilder.build(
            directOnly,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Android),
        )

        assertTrue(result is RuntimeConfigResult.Ready)
    }

    private fun validConfig(
        tunExtra: String = "",
        rootExtra: String = "",
    ): String = """
        {
          "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true$tunExtra}],
          "outbounds":[
            {"type":"direct","tag":"server-a"},
            {"type":"selector","tag":"zapret-proxy","outbounds":["server-a"],"default":"server-a"},
            {"type":"direct","tag":"direct"}
          ],
          "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
          $rootExtra
        }
    """.trimIndent()
}
