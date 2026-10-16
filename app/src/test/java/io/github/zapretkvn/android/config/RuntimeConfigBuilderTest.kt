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
import org.junit.Assert.assertNull
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
        assertEquals("[\"zapret.moe\"]", updaterRule["domain"].toString())
        assertEquals("[\".zapret.moe\"]", updaterRule["domain_suffix"].toString())
        assertEquals("zapret-proxy", updaterRule.string("outbound"))
        assertTrue(
            dnsRules.any { rule ->
                rule.string("server") == "zapret-secure-dns" &&
                    rule["package_name"] == updaterRule["package_name"]
            },
        )
        assertFalse("stored profile must stay untouched", "zapret.moe" in stored)
    }

    @Test
    fun `standing updater route needs no restart and never invalidates a profile`() {
        val standing = RuntimeConfigBuilder.build(
            validConfig(),
            options = RuntimeConfigOptions(standingUpdaterPackageName = "io.github.zapretkvn.android"),
        ) as RuntimeConfigResult.Ready
        val rules = (((JsonConfig.parse(standing.json) as JsonObject)["route"] as JsonObject)["rules"] as JsonArray)
            .map { it as JsonObject }
        val updaterRule = rules.first { "package_name" in it }
        assertEquals("zapret-proxy", updaterRule.string("outbound"))
        assertEquals("[\".zapret.moe\"]", updaterRule["domain_suffix"].toString())

        // Профиль без выбранного proxy: правило молча пропускается — результат тот же,
        // что и без него (ни лишнего правила, ни ошибки «нужен proxy»).
        val noProxy = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[{"type":"direct","tag":"direct"}],
              "route":{"auto_detect_interface":true,"final":"direct"}
            }
        """.trimIndent()
        assertNull(BootstrapConfig.selectedProxyTag(noProxy))
        run {
            assertEquals(
                RuntimeConfigBuilder.build(noProxy),
                RuntimeConfigBuilder.build(
                    noProxy,
                    options = RuntimeConfigOptions(standingUpdaterPackageName = "io.github.zapretkvn.android"),
                ),
            )
        }
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
            listOf(
                "zapret-android-dns",
                "zapret-node-dns-udp",
                "zapret-node-dns-tcp",
                "zapret-secure-dns",
                "zapret-dns-override",
            ),
            servers.map { (it as JsonObject).string("tag") },
        )
        assertEquals("zapret-secure-dns", (dns["final"] as JsonPrimitive).content)
        assertEquals("zapret-android-dns", (root["route"] as JsonObject).string("default_domain_resolver"))
        assertTrue(routeRules.any { (it as JsonObject).string("action") == "hijack-dns" })
        assertTrue((root["unknown_dns_owner"] as JsonPrimitive).boolean)
        assertFalse("stored JSON must not be rewritten", "zapret-android-dns" in stored)
    }

    @Test
    fun `Android DNS without node tunnelling keeps every name on the system resolver`() {
        // Strict Private DNS и резерв Automatic: проксируемые имена остаются на Android.
        val result = RuntimeConfigBuilder.build(
            validConfig(),
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Android,
                androidDnsProxiedViaNode = false,
            ),
        ) as RuntimeConfigResult.Ready
        val dns = (JsonConfig.parse(result.json) as JsonObject)["dns"] as JsonObject
        val tags = (dns["servers"] as JsonArray).map { (it as JsonObject).string("tag") }
        val rules = (dns["rules"] as JsonArray).map { it as JsonObject }

        assertEquals(listOf("zapret-android-dns", "zapret-dns-override"), tags)
        assertEquals("zapret-android-dns", dns.string("final"))
        assertTrue(rules.all { it.string("server") != "zapret-secure-dns" })
        assertTrue(
            rules.any {
                it.string("server") == "zapret-android-dns" &&
                    it["domain"]?.toString()?.contains("cp.cloudflare.com") == true
            },
        )
    }

    @Test
    fun `Android DNS sends only direct domains and server names to the system resolver`() {
        val stored = validConfig(
            rootExtra = """,
              "endpoints":[
                {"type":"wireguard","tag":"wg-a","address":["10.0.0.2/32"],"peers":[{"address":"WG.example.","port":51820}]}
              ]
            """,
        ).replace(
            "{\"type\":\"direct\",\"tag\":\"server-a\"}",
            "{\"type\":\"vless\",\"tag\":\"server-a\",\"server\":\"vpn.example\",\"server_port\":443," +
                "\"uuid\":\"00000000-0000-4000-8000-000000000000\",\"tls\":{\"enabled\":true,\"server_name\":\"vpn.example\"}}",
        ).replace(
            "\"auto_detect_interface\":true,\"final\":\"zapret-proxy\"",
            "\"auto_detect_interface\":true,\"rules\":[" +
                "{\"rule_set\":[\"zapret-ru-domains\"],\"action\":\"route\",\"outbound\":\"direct\"}," +
                "{\"domain_suffix\":[\".ru\"],\"action\":\"route\",\"outbound\":\"direct\"}," +
                "{\"domain_suffix\":[\".proxy.test\"],\"action\":\"route\",\"outbound\":\"zapret-proxy\"}" +
                "],\"final\":\"zapret-proxy\"",
        )
        val options = RuntimeConfigOptions(
            dnsMode = DnsMode.Android,
            proxyIpv4Only = true,
            bootstrapHost = BootstrapHostOverlay("server-a", "vpn.example", listOf("203.0.113.10")),
        )
        val result = RuntimeConfigBuilder.build(stored, options = options) as RuntimeConfigResult.Ready
        val dns = (JsonConfig.parse(result.json) as JsonObject)["dns"] as JsonObject
        val servers = (dns["servers"] as JsonArray).map { it as JsonObject }
        val rules = (dns["rules"] as JsonArray).map { it as JsonObject }

        // Резолвер узла собран тем же кодом, что для «Защищённого»: detour через proxy.
        listOf("zapret-node-dns-udp", "zapret-node-dns-tcp").forEach { tag ->
            assertEquals("zapret-proxy", servers.single { it.string("tag") == tag }.string("detour"))
        }
        assertTrue(servers.any { it.string("tag") == "zapret-secure-dns" })
        assertTrue(servers.any { it.string("tag") == "zapret-android-dns" && it.string("type") == "local" })

        // Имена VPN-серверов (outbound, пир WireGuard, bootstrap) — локально и раньше любого
        // правила на DNS узла (перед ними стоит только DNS-переопределение).
        val serverNames = rules.first { it["domain"]?.toString()?.contains("vpn.example") == true }
        assertTrue(
            rules.indexOf(serverNames) < rules.indexOfFirst { it.string("server") == "zapret-secure-dns" },
        )
        assertEquals("zapret-android-dns", serverNames.string("server"))
        assertEquals(
            listOf("vpn.example", "wg.example"),
            (serverNames["domain"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertFalse("strategy" in serverNames)

        // Direct-домены (rule_set и суффикс) — локальный DNS без strategy.
        val ruSet = rules.first { it["rule_set"]?.toString()?.contains("zapret-ru-domains") == true }
        val ruSuffix = rules.first { it["domain_suffix"]?.toString()?.contains(".ru") == true }
        listOf(ruSet, ruSuffix).forEach { direct ->
            assertEquals("zapret-android-dns", direct.string("server"))
            assertFalse("strategy" in direct)
        }
        // Односложные имена — тоже локально.
        assertTrue(
            rules.any {
                it.string("server") == "zapret-android-dns" &&
                    it["domain_regex"]?.toString()?.contains("^[^.]+$") == true
            },
        )
        // Проксируемый домен, health-хосты и catch-all — DNS узла с ipv4_only.
        val proxied = rules.first { it["domain_suffix"]?.toString()?.contains(".proxy.test") == true }
        val health = rules.first { it["domain"]?.toString()?.contains("cp.cloudflare.com") == true }
        val catchAll = rules.last()
        listOf(proxied, health, catchAll).forEach { viaNode ->
            assertEquals("zapret-secure-dns", viaNode.string("server"))
            assertEquals("ipv4_only", viaNode.string("strategy"))
        }
        assertEquals(
            listOf("cp.cloudflare.com", "connectivitycheck.gstatic.com", "www.google.com"),
            (health["domain"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals("zapret-secure-dns", dns.string("final"))

        // Повторная сборка из runtime-копии даёт те же правила, без дублей.
        val rebuilt = RuntimeConfigBuilder.build(result.json, options = options) as RuntimeConfigResult.Ready
        val rebuiltDns = (JsonConfig.parse(rebuilt.json) as JsonObject)["dns"] as JsonObject
        assertEquals(dns["rules"], rebuiltDns["rules"])
        assertEquals(dns["servers"], rebuiltDns["servers"])
        assertFalse("stored profile must stay untouched", "zapret-secure-dns" in stored)
    }

    @Test
    fun `Android DNS keeps health hosts on the node resolver even when final is direct`() {
        val stored = validConfig().replace("\"final\":\"zapret-proxy\"", "\"final\":\"direct\"")
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Android, proxyIpv4Only = false),
        ) as RuntimeConfigResult.Ready
        val dns = (JsonConfig.parse(result.json) as JsonObject)["dns"] as JsonObject
        val rules = (dns["rules"] as JsonArray).map { it as JsonObject }
        val health = rules.first { it["domain"]?.toString()?.contains("cp.cloudflare.com") == true }

        assertEquals("zapret-android-dns", dns.string("final"))
        assertEquals("zapret-secure-dns", health.string("server"))
        assertFalse("strategy" in health)
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
        // sing-box >= 1.14 rejects configs that dial domains without a resolver;
        // FromJson keeps the profile DNS and points the route default at it.
        assertEquals("profile-dns", (root["route"] as JsonObject).string("default_domain_resolver"))
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
        // The DNS sink reject follows hijack-dns so only non-53 sink traffic is rejected.
        assertEquals("reject", rules[1].string("action"))
        assertEquals(
            listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
            (rules[1]["ip_cidr"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals("zapret-proxy", rules.first { it.string("outbound") == "zapret-proxy" }.string("outbound"))
        assertFalse("stored profile must stay without a DNS section", "\"dns\"" in stored)
    }

    @Test
    fun `managed DNS rejects non-53 traffic to the TUN DNS sink exactly once across rebuilds`() {
        val stored = validConfig()
        val first = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Secure),
        ) as RuntimeConfigResult.Ready
        val firstRules = ((JsonConfig.parse(first.json) as JsonObject)["route"] as JsonObject)["rules"] as JsonArray
        val rules = firstRules.map { it as JsonObject }

        assertEquals("hijack-dns", rules[0].string("action"))
        val sink = rules[1]
        assertEquals("reject", sink.string("action"))
        assertEquals(
            listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
            (sink["ip_cidr"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertNull("sink reject must not narrow to port 53", sink["port"])

        // Rebuilding from the runtime overlay strips the previous sink rule and regenerates one.
        val second = RuntimeConfigBuilder.build(
            first.json,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Secure),
        ) as RuntimeConfigResult.Ready
        val secondRules = ((JsonConfig.parse(second.json) as JsonObject)["route"] as JsonObject)["rules"] as JsonArray
        val rejectsWithCidr = secondRules
            .map { it as JsonObject }
            .filter { it.string("action") == "reject" && it["ip_cidr"] != null }
        assertEquals(1, rejectsWithCidr.size)
    }

    /**
     * Strict Private DNS: DoT/DoH Android к резолверу пользователя идёт напрямую,
     * как без VPN, а не через узел. Правила стоят сразу за перехватом DNS, раньше
     * пользовательских, и не размножаются при пересборке из runtime JSON.
     */
    @Test
    fun `strict private DNS resolver traffic goes direct right after DNS hijack`() {
        val options = RuntimeConfigOptions(
            dnsMode = DnsMode.Android,
            privateDnsRoute = PrivateDnsRoute(
                hostname = "Dns.Example.",
                addresses = listOf("203.0.113.53", "2001:db8::53", "not-an-ip"),
            ),
        )
        val first = RuntimeConfigBuilder.build(validConfig(), options = options) as RuntimeConfigResult.Ready
        val second = RuntimeConfigBuilder.build(first.json, options = options) as RuntimeConfigResult.Ready
        for (result in listOf(first, second)) {
            val root = JsonConfig.parse(result.json) as JsonObject
            val rules = ((root["route"] as JsonObject)["rules"] as JsonArray).map { it as JsonObject }
            assertEquals("hijack-dns", rules[0].string("action"))
            assertEquals("reject", rules[1].string("action"))
            val byIp = rules[2]
            assertEquals(
                listOf("203.0.113.53/32", "2001:db8::53/128"),
                (byIp["ip_cidr"] as JsonArray).map { (it as JsonPrimitive).content },
            )
            assertEquals(listOf("853", "443"), (byIp["port"] as JsonArray).map { (it as JsonPrimitive).content })
            assertNull("DoH over HTTP/3 must match UDP too", byIp["network"])
            assertEquals("zapret-private-dns", byIp.string("outbound"))
            assertEquals("sniff", rules[3].string("action"))
            assertEquals("853", (rules[3]["port"] as JsonPrimitive).content)
            assertEquals(
                listOf("dns.example"),
                (rules[4]["domain"] as JsonArray).map { (it as JsonPrimitive).content },
            )
            assertEquals("zapret-private-dns", rules[4].string("outbound"))
            assertEquals(1, rules.count { it.string("action") == "sniff" && it["package_name"] == null })
            assertEquals(2, rules.count { it.string("outbound") == "zapret-private-dns" })
            val direct = (root["outbounds"] as JsonArray).map { it as JsonObject }
                .filter { it.string("tag") == "zapret-private-dns" }
            assertEquals(1, direct.size)
            assertEquals("direct", direct.single().string("type"))
        }
    }

    @Test
    fun `without strict private DNS no resolver bypass is generated`() {
        val result = RuntimeConfigBuilder.build(
            validConfig(),
            options = RuntimeConfigOptions(dnsMode = DnsMode.Android),
        ) as RuntimeConfigResult.Ready
        assertFalse("zapret-private-dns" in result.json)
        val rules = (((JsonConfig.parse(result.json) as JsonObject)["route"] as JsonObject)["rules"] as JsonArray)
            .map { it as JsonObject }
        assertTrue(rules.none { (it["port"] as? JsonPrimitive)?.contentOrNull == "853" })
    }

    @Test
    fun `strict private DNS without resolved addresses still routes DoT by name`() {
        val result = RuntimeConfigBuilder.build(
            validConfig(),
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.FromJson,
                privateDnsRoute = PrivateDnsRoute("dns.example", emptyList()),
            ),
        ) as RuntimeConfigResult.Ready
        val rules = (((JsonConfig.parse(result.json) as JsonObject)["route"] as JsonObject)["rules"] as JsonArray)
            .map { it as JsonObject }
        assertTrue(rules.none { it["ip_cidr"] != null && it.string("outbound") == "zapret-private-dns" })
        assertEquals(1, rules.count { it.string("outbound") == "zapret-private-dns" && it["domain"] != null })
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
    fun `tunnel DNS is plain 53 through the proxy so the node resolver answers`() {
        val result = RuntimeConfigBuilder.build(
            validConfig(),
            options = RuntimeConfigOptions(dnsMode = DnsMode.Secure),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val dns = root["dns"] as JsonObject
        val servers = (dns["servers"] as JsonArray).map { it as JsonObject }
        val rules = (dns["rules"] as JsonArray).map { it as JsonObject }
        val fallback = servers.first { it.string("tag") == "zapret-secure-dns" }

        // Узел перенаправляет DNS туннеля на 53 в собственный resolver и
        // подменяет там адреса управляемых имён. DoH этого перехвата не видит и
        // возвращает настоящий origin.
        assertEquals("sequential", fallback.string("strategy"))
        assertEquals(
            listOf("zapret-node-dns-udp", "zapret-node-dns-tcp"),
            (fallback["servers"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        listOf(
            "zapret-node-dns-udp" to "udp",
            "zapret-node-dns-tcp" to "tcp",
        ).forEach { (tag, transport) ->
            val server = servers.first { it.string("tag") == tag }
            assertEquals(transport, server.string("type"))
            // 1.1.1.1 и 1.0.0.1 — канонические upstream узла, они исключены из
            // его перенаправления и ушли бы к настоящему Cloudflare.
            assertEquals("8.8.8.8", server.string("server"))
            assertEquals("53", (server["server_port"] as JsonPrimitive).content)
            assertEquals("zapret-proxy", server.string("detour"))
            assertFalse("tls" in server)
        }
        assertFalse(servers.any { it.string("type") == "https" })
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
    fun `switching a runtime copy from secure to Android removes every generated resolver`() {
        val secure = RuntimeConfigBuilder.build(
            validConfig(),
            options = RuntimeConfigOptions(dnsMode = DnsMode.Secure),
        ) as RuntimeConfigResult.Ready
        val android = RuntimeConfigBuilder.build(
            secure.json,
            options = RuntimeConfigOptions(
                dnsMode = DnsMode.Android,
                androidDnsProxiedViaNode = false,
            ),
        ) as RuntimeConfigResult.Ready
        val dns = (JsonConfig.parse(android.json) as JsonObject)["dns"] as JsonObject
        val tags = (dns["servers"] as JsonArray).map { (it as JsonObject).string("tag") }

        assertFalse(tags.any { it?.startsWith("zapret-node-dns-") == true })
        assertFalse("zapret-secure-dns" in tags)
        assertEquals("zapret-android-dns", dns.string("final"))
    }

    @Test
    fun `a runtime copy written by an older DoH build drops its managed resolvers`() {
        val legacy = validConfig(
            rootExtra = """
                ,"dns":{"servers":[{"type":"https","tag":"zapret-doh-2","server":"8.8.8.8","tls":{"enabled":true,"server_name":"dns.google"},"detour":"zapret-proxy"}],"final":"zapret-doh-2"}
            """.trimIndent(),
        )
        val result = RuntimeConfigBuilder.build(
            legacy,
            options = RuntimeConfigOptions(dnsMode = DnsMode.Secure),
        ) as RuntimeConfigResult.Ready
        val dns = (JsonConfig.parse(result.json) as JsonObject)["dns"] as JsonObject
        val tags = (dns["servers"] as JsonArray).map { (it as JsonObject).string("tag") }

        assertFalse(tags.any { it?.startsWith("zapret-doh-") == true })
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
            listOf("cp.cloudflare.com", "connectivitycheck.gstatic.com", "www.google.com"),
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
        // final = direct: безымянные прямые имена идут на системный DNS без отдельного catch-all.
        assertEquals("zapret-android-dns", directFinalAndroidDns.string("final"))
        assertTrue(
            directFinalAndroidRules.none { rule ->
                rule.string("server") == "zapret-secure-dns" && "domain_suffix" !in rule && "domain" !in rule
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
    fun `bootstrap overlay applies resolver to selected WireGuard endpoint only`() {
        val stored = validConfig(rootExtra = """,
          "endpoints":[
            {"type":"wireguard","tag":"wg-a","address":["10.0.0.2/32"],"peers":[{"address":"wg.example","port":51820}]},
            {"type":"wireguard","tag":"wg-b","address":["10.0.0.3/32"],"peers":[{"address":"other.example","port":51820}]}
          ]
        """)
            .replace("\"final\":\"zapret-proxy\"", "\"final\":\"wg-a\"")
            .replace(
                "{\"type\":\"selector\",\"tag\":\"zapret-proxy\",\"outbounds\":[\"server-a\"],\"default\":\"server-a\"},",
                "",
            )
        val result = RuntimeConfigBuilder.build(
            stored,
            options = RuntimeConfigOptions(
                bootstrapHost = BootstrapHostOverlay("wg-a", "wg.example", listOf("203.0.113.20")),
            ),
        ) as RuntimeConfigResult.Ready
        val root = JsonConfig.parse(result.json) as JsonObject
        val endpoints = (root["endpoints"] as JsonArray).map { it as JsonObject }

        assertEquals("zapret-bootstrap-lkg", endpoints.first { it.string("tag") == "wg-a" }.string("domain_resolver"))
        assertEquals(null, endpoints.first { it.string("tag") == "wg-b" }.string("domain_resolver"))
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
        // Без proxy туннелировать нечего: DNS узла не создаётся, всё остаётся на Android.
        val dns = (JsonConfig.parse((result as RuntimeConfigResult.Ready).json) as JsonObject)["dns"] as JsonObject
        val tags = (dns["servers"] as JsonArray).map { (it as JsonObject).string("tag") }
        assertEquals("zapret-android-dns", dns.string("final"))
        assertFalse(tags.any { it?.startsWith("zapret-node-dns-") == true || it == "zapret-secure-dns" })
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
