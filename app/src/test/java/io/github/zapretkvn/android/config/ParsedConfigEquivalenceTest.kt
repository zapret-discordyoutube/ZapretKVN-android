package io.github.zapretkvn.android.config

import io.github.zapretkvn.android.routing.InstalledRuleSets
import io.github.zapretkvn.android.routing.ManagedRoutingRule
import io.github.zapretkvn.android.routing.RoutingConfigEditor
import io.github.zapretkvn.android.routing.RoutingMatchType
import io.github.zapretkvn.android.routing.RoutingPreset
import io.github.zapretkvn.android.routing.RoutingRuleAction
import io.github.zapretkvn.android.vpn.runtime.FailoverPlanner
import java.io.File
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Путь с одним разбором ([ParsedConfig]) обязан давать побайтно тот же результат,
 * что и строковый путь, которым пользуются UI и остальной код: на фикстурах
 * `testdata/`, на собственных профилях и на заведомо некорректном вводе.
 */
class ParsedConfigEquivalenceTest {
    private val installed = InstalledRuleSets(
        version = 1,
        paths = mapOf(
            "zapret-ru-domains" to "/data/user/0/app/files/rule-sets/zapret-ru-domains.srs",
            "zapret-ru-ip" to "/data/user/0/app/files/rule-sets/zapret-ru-ip.srs",
        ),
    )

    @Test
    fun `fixtures exist so the equivalence checks are not vacuous`() {
        assertTrue("testdata fixtures not found", fixtures().size >= 6)
    }

    @Test
    fun `analyzers give the same result for string and parsed input`() {
        for ((name, raw) in allInputs()) {
            val parsed = runCatching { ParsedConfig.parse(raw) }.getOrNull()
            if (parsed == null) {
                // Синтаксическая ошибка: строковые обёртки ведут себя как раньше.
                assertEquals(name, ConfigAnalyzer.hasProfileDns(raw), false)
                assertEquals(name, ConfigAnalyzer.dnsWarnings(raw), emptyList<String>())
                assertEquals(name, BootstrapConfig.serverHostnames(raw), emptySet<String>())
                assertEquals(name, FailoverPlanner.plan(raw, emptyMap(), null), null)
                continue
            }
            same(name, "hasProfileDns", { ConfigAnalyzer.hasProfileDns(raw) }, { ConfigAnalyzer.hasProfileDns(parsed) })
            same(name, "dnsWarnings", { ConfigAnalyzer.dnsWarnings(raw) }, { ConfigAnalyzer.dnsWarnings(parsed) })
            same(name, "selectorGroups", { ConfigAnalyzer.selectorGroups(raw) }, { ConfigAnalyzer.selectorGroups(parsed) })
            same(name, "serverOutboundTags", { ConfigAnalyzer.serverOutboundTags(raw) }, { ConfigAnalyzer.serverOutboundTags(parsed) })
            same(name, "outboundDescriptions", { ConfigAnalyzer.outboundDescriptions(raw) }, { ConfigAnalyzer.outboundDescriptions(parsed) })
            same(name, "selectedProxyTag", { BootstrapConfig.selectedProxyTag(raw) }, { BootstrapConfig.selectedProxyTag(parsed) })
            same(name, "selectedProxyIpFamily", { BootstrapConfig.selectedProxyIpFamily(raw) }, { BootstrapConfig.selectedProxyIpFamily(parsed) })
            same(name, "serverHostnames", { BootstrapConfig.serverHostnames(raw) }, { BootstrapConfig.serverHostnames(parsed) })
            same(name, "target", { BootstrapConfig.target(raw) }, { BootstrapConfig.target(parsed) })
            same(name, "inspect", { RoutingConfigEditor.inspect(raw) }, { RoutingConfigEditor.inspect(parsed) })
            same(name, "usesManagedLocalRuleSets", { RoutingConfigEditor.usesManagedLocalRuleSets(raw) }, { RoutingConfigEditor.usesManagedLocalRuleSets(parsed) })
            same(name, "rebind", { RoutingConfigEditor.rebindManagedRuleSetPaths(raw, installed) }, { RoutingConfigEditor.rebindManagedRuleSetPaths(parsed, installed) })
            same(name, "failover", { FailoverPlanner.plan(raw, emptyMap(), "zapret-proxy") }, { FailoverPlanner.plan(parsed, emptyMap(), "zapret-proxy") })
            for (group in ConfigAnalyzer.selectorGroups(parsed)) {
                for (member in group.outbounds) {
                    same(name, "selectServer($member)", { ConfigAnalyzer.selectServer(raw, group.tag, member) }, { ConfigAnalyzer.selectServer(parsed, group.tag, member) })
                }
            }
            same(name, "addManagedSelector", { ConfigAnalyzer.addManagedSelector(raw, listOf("server-a")) }, { ConfigAnalyzer.addManagedSelector(parsed, listOf("server-a")) })
        }
    }

    @Test
    fun `runtime config builder gives the same json for string and parsed input`() {
        val optionSets = listOf(
            RuntimeConfigOptions(dnsMode = DnsMode.FromJson),
            RuntimeConfigOptions(dnsMode = DnsMode.Android),
            RuntimeConfigOptions(dnsMode = DnsMode.Secure),
            RuntimeConfigOptions(dnsMode = DnsMode.Automatic, healthCheckPackageName = "com.example.app"),
            RuntimeConfigOptions(
                dnsMode = DnsMode.Automatic,
                updaterPackageName = "com.example.app",
                standingUpdaterPackageName = "com.example.app",
                blockedPackages = setOf("com.example.b", "com.example.a"),
            ),
        )
        var ready = 0
        for ((name, raw) in allInputs()) {
            for (options in optionSets) {
                for (stats in listOf(false, true)) {
                    val fromString = RuntimeConfigBuilder.build(raw, stats, options)
                    val parsed = runCatching { ParsedConfig.parse(raw) }.getOrNull()
                    if (parsed == null) {
                        assertTrue("$name: syntax error must be Invalid", fromString is RuntimeConfigResult.Invalid)
                        continue
                    }
                    val fromParsed = RuntimeConfigBuilder.build(parsed, stats, options)
                    assertEquals("$name/${options.dnsMode}/$stats", fromString, fromParsed)
                    if (fromParsed is RuntimeConfigResult.Ready) {
                        ready++
                        // Дерево, которое сборщик отдаёт дальше, совпадает с разбором его же текста.
                        assertEquals(name, JsonConfig.parse(fromParsed.json), fromParsed.config.element)
                        assertEquals(name, fromParsed.json, fromParsed.config.raw)
                    }
                }
            }
        }
        assertTrue("no input produced a runtime config; test is vacuous", ready > 0)
    }

    @Test
    fun `routing apply gives the same json and diff and a tree equal to the reparsed text`() {
        val manual = listOf(
            ManagedRoutingRule(RoutingMatchType.DomainSuffix, listOf(".direct.example"), RoutingRuleAction.Direct),
            ManagedRoutingRule(RoutingMatchType.IpCidr, listOf("192.0.2.0/24"), RoutingRuleAction.Block),
            ManagedRoutingRule(RoutingMatchType.Domain, listOf("proxy.example"), RoutingRuleAction.Proxy),
        )
        var applied = 0
        for ((name, raw) in allInputs()) {
            val parsed = runCatching { ParsedConfig.parse(raw) }.getOrNull() ?: continue
            for (preset in RoutingPreset.entries) {
                for (rules in listOf(emptyList(), manual)) {
                    val fromString = runCatching { RoutingConfigEditor.apply(raw, preset, rules, installed) }
                    val fromParsed = runCatching { RoutingConfigEditor.applyParsed(parsed, preset, rules, installed) }
                    assertEquals("$name/$preset: failure parity", fromString.isSuccess, fromParsed.isSuccess)
                    if (fromString.isFailure) {
                        assertEquals(fromString.exceptionOrNull()!!.javaClass, fromParsed.exceptionOrNull()!!.javaClass)
                        assertEquals(fromString.exceptionOrNull()!!.message, fromParsed.exceptionOrNull()!!.message)
                        continue
                    }
                    val edit = fromParsed.getOrThrow()
                    assertEquals("$name/$preset", fromString.getOrThrow(), edit.result)
                    assertEquals("$name/$preset: tree vs reparsed text", JsonConfig.parse(edit.result.json), edit.config.element)
                    assertEquals(edit.result.json, edit.config.raw)
                    applied++
                    // Дальше по цепочке: сборка runtime из дерева и из текста результата маршрутизации.
                    for (mode in listOf(DnsMode.FromJson, DnsMode.Automatic)) {
                        val options = RuntimeConfigOptions(dnsMode = mode)
                        assertEquals(
                            "$name/$preset/$mode: runtime after routing",
                            RuntimeConfigBuilder.build(edit.result.json, false, options),
                            RuntimeConfigBuilder.build(edit.config, false, options),
                        )
                    }
                }
            }
        }
        assertTrue("routing was never applied; test is vacuous", applied > 0)
    }

    @Test
    fun `parsed config formats lazily from a tree and keeps the original text from a parse`() {
        val raw = """{"b":1,"a":[true,null,"x"]}"""
        val parsed = ParsedConfig.parse(raw)
        assertTrue(parsed.raw === raw)
        val tree = ParsedConfig.fromTree(parsed.element)
        assertEquals(JsonConfig.format(parsed.element), tree.raw)
        assertTrue(tree.raw === tree.raw)
        assertEquals(parsed, tree)
        assertTrue(ParsedConfig.parse("[1]").root == null)
    }

    private fun <T> same(name: String, what: String, viaString: () -> T, viaParsed: () -> T) {
        val a = runCatching(viaString)
        val b = runCatching(viaParsed)
        assertEquals("$name/$what: failure parity", a.isSuccess, b.isSuccess)
        if (a.isSuccess) {
            assertEquals("$name/$what", a.getOrThrow(), b.getOrThrow())
        } else {
            assertEquals("$name/$what: exception type", a.exceptionOrNull()!!.javaClass, b.exceptionOrNull()!!.javaClass)
            assertEquals("$name/$what: exception message", a.exceptionOrNull()!!.message, b.exceptionOrNull()!!.message)
        }
    }

    private fun allInputs(): List<Pair<String, String>> =
        fixtures() + own().toList() + listOf(
            "not-json" to "{",
            "array-root" to "[1,2,3]",
            "empty-object" to "{}",
        )

    private fun fixtures(): List<Pair<String, String>> {
        val root = listOf(File("../testdata"), File("testdata")).firstOrNull(File::isDirectory) ?: return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.path }
            .map { it.relativeTo(root).path to it.readText() }
            .toList()
    }

    private fun own(): Map<String, String> = mapOf(
        "selector-direct" to """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                {"type":"direct","tag":"server-a"},
                {"type":"selector","tag":"zapret-proxy","outbounds":["server-a"],"default":"server-a"},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """.trimIndent(),
        "multi-server-with-dns" to """
            {
              "log":{"level":"debug","output":"x.log"},
              "dns":{
                "servers":[
                  {"type":"udp","tag":"dns-a","server":"1.1.1.1"},
                  {"type":"udp","tag":"dns-b","server":"8.8.8.8","detour":"srv-1"},
                  {"type":"local","tag":"dns-local"}
                ],
                "rules":[{"domain":["a.example"],"action":"route","server":"dns-a"}],
                "final":"dns-a"
              },
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true,"include_package":["com.example.x"]}],
              "outbounds":[
                {"type":"vless","tag":"srv-1","server":"one.example.com","server_port":443,"uuid":"00000000-0000-0000-0000-000000000000","tls":{"enabled":true,"server_name":"one.example.com"}},
                {"type":"hysteria2","tag":"srv-2","server":"203.0.113.7","server_port":8443,"password":"x","maintenance":false},
                {"type":"selector","tag":"zapret-proxy","outbounds":["srv-1","srv-2"],"default":"srv-2"},
                {"type":"urltest","tag":"auto","outbounds":["srv-1","srv-2"],"tolerance":50,"interval":"3m"},
                {"type":"direct","tag":"direct"}
              ],
              "route":{
                "auto_detect_interface":true,
                "final":"zapret-proxy",
                "rule_set":[{"type":"inline","tag":"user-set","rules":[{"domain":["x.example"]}]}],
                "rules":[{"rule_set":["user-set"],"action":"route","outbound":"direct"}]
              },
              "experimental":{"cache_file":{"enabled":true},"clash_api":{"external_controller":"127.0.0.1:9090"}}
            }
        """.trimIndent(),
        "wireguard-endpoint-only" to """
            {
              "endpoints":[{"type":"wireguard","tag":"wg","address":["10.0.0.2/32","fd00::2/128"],"private_key":"AAAA",
                "peers":[{"address":"wg.example.com","port":51820,"public_key":"BBBB","allowed_ips":["0.0.0.0/0","::/0"]}]}],
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[{"type":"direct","tag":"direct"}],
              "route":{"auto_detect_interface":true,"final":"direct","rules":[{"ip_cidr":["0.0.0.0/0"],"action":"route","outbound":"wg"}]}
            }
        """.trimIndent(),
    )
}
