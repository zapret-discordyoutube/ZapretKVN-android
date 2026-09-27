package io.github.zapretkvn.android.routing

import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.string
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ManagedServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingConfigEditorTest {
    private val installed = InstalledRuleSets(
        version = 1,
        paths = mapOf(
            "zapret-ru-domains" to "/data/user/0/app/files/rule-sets/zapret-ru-domains.srs",
            "zapret-ru-ip" to "/data/user/0/app/files/rule-sets/zapret-ru-ip.srs",
        ),
    )

    @Test
    fun `all presets produce expected final LAN and RU pair`() {
        val all = edit(RoutingPreset.AllThroughVpn)
        val lan = edit(RoutingPreset.BypassLan)
        val selected = edit(RoutingPreset.OnlySelectedSites)
        val ruDirect = edit(RoutingPreset.RussiaDirect)
        val ruVpn = edit(RoutingPreset.RussiaVpn)

        assertEquals("zapret-proxy", route(all).string("final"))
        assertFalse(routeRules(all).any(::isPrivate))
        assertTrue(routeRules(lan).any(::isPrivate))
        assertEquals("direct", route(selected).string("final"))
        assertEquals("direct", ruRule(ruDirect).string("outbound"))
        assertEquals("zapret-proxy", route(ruDirect).string("final"))
        assertEquals("zapret-proxy", ruRule(ruVpn).string("outbound"))
        assertEquals("direct", route(ruVpn).string("final"))
        assertEquals(RoutingPreset.RussiaDirect, RoutingConfigEditor.inspect(ruDirect).preset)
        assertEquals(RoutingPreset.RussiaVpn, RoutingConfigEditor.inspect(ruVpn).preset)
        assertEquals(RoutingPreset.OnlySelectedSites, RoutingConfigEditor.inspect(selected).preset)
    }

    @Test
    fun `explicit custom preset remains custom when previous final resembles a preset`() {
        val allThrough = edit(RoutingPreset.AllThroughVpn)
        val custom = RoutingConfigEditor.apply(
            allThrough,
            RoutingPreset.Custom,
            emptyList(),
            installed,
        ).json

        assertEquals(RoutingPreset.Custom, RoutingConfigEditor.inspect(custom).preset)
        assertTrue(custom.contains("zapret-preset-custom"))
        assertEquals("zapret-proxy", route(custom).string("final"))
    }

    @Test
    fun `russia vpn inspection retains a non-managed selector when final is direct`() {
        val raw = profile()
            .replace("zapret-proxy", "external-selector")
        val edited = RoutingConfigEditor.apply(
            raw,
            RoutingPreset.RussiaVpn,
            emptyList(),
            installed,
        )

        assertEquals(RoutingPreset.RussiaVpn, edited.inspection.preset)
        assertEquals("external-selector", edited.inspection.proxyTag)
        assertEquals("external-selector", ruRule(edited.json).string("outbound"))
        assertEquals("direct", route(edited.json).string("final"))
    }

    @Test
    fun `reject direct proxy order and DNS block are compiled atomically`() {
        val rules = listOf(
            ManagedRoutingRule(RoutingMatchType.Domain, listOf("vpn.example"), RoutingRuleAction.Proxy),
            ManagedRoutingRule(RoutingMatchType.IpCidr, listOf("192.0.2.0/24"), RoutingRuleAction.Block),
            ManagedRoutingRule(RoutingMatchType.DomainSuffix, listOf(".direct.example"), RoutingRuleAction.Direct),
            ManagedRoutingRule(RoutingMatchType.Domain, listOf("blocked.example"), RoutingRuleAction.Block),
        )
        val result = RoutingConfigEditor.apply(profile(), RoutingPreset.BypassLan, rules, installed)
        val root = parsed(result.json)
        val policy = routeRules(result.json).filter { it.string("action") != "hijack-dns" }
        val actions = policy.map { rule ->
            if (rule.string("action") == "reject") "reject" else rule.string("outbound")!!
        }
        val dnsRejects = ((root["dns"] as JsonObject)["rules"] as JsonArray)
            .map { it as JsonObject }
            .filter { it.string("action") == "reject" }

        assertEquals(listOf("reject", "reject", "direct", "direct", "zapret-proxy"), actions)
        assertEquals(1, dnsRejects.size)
        assertTrue(result.diff.contains("route reject"))
        assertFalse(result.json.contains("package_name"))
        assertFalse(result.json.contains("\"sniff\""))
        assertFalse(result.json.contains("geoip"))
        assertFalse(result.json.contains("geosite"))
    }

    @Test
    fun `domain block gets DNS reject while IP and IP rule-set do not`() {
        val rules = listOf(
            ManagedRoutingRule(RoutingMatchType.Domain, listOf("blocked.example"), RoutingRuleAction.Block),
            ManagedRoutingRule(RoutingMatchType.IpCidr, listOf("203.0.113.1/32"), RoutingRuleAction.Block),
            ManagedRoutingRule(RoutingMatchType.IpRuleSet, listOf("user-ip"), RoutingRuleAction.Block),
        )
        val result = RoutingConfigEditor.apply(profileWithRuleSet(), RoutingPreset.AllThroughVpn, rules, installed)
        val root = parsed(result.json)
        val dnsRules = ((root["dns"] as JsonObject)["rules"] as JsonArray).map { it as JsonObject }

        assertEquals(1, dnsRules.count { it.string("action") == "reject" })
        assertEquals(3, routeRules(result.json).count { it.string("action") == "reject" })
        assertEquals(RoutingMatchType.IpRuleSet, result.inspection.rules.last().matchType)
    }

    @Test
    fun `IP rule-set rule is valid for the core and legacy ip_version array is replaced`() {
        val rules = listOf(
            ManagedRoutingRule(RoutingMatchType.IpRuleSet, listOf("user-ip"), RoutingRuleAction.Direct),
        )
        val result = RoutingConfigEditor.apply(profileWithRuleSet(), RoutingPreset.AllThroughVpn, rules, installed)
        val ipRule = routeRules(result.json).single { rule ->
            (rule["rule_set"] as? JsonArray)?.map { (it as JsonPrimitive).content } == listOf("user-ip")
        }
        // sing-box: ip_version — одно число; массив ядро отвергает при разборе.
        assertFalse(ipRule.containsKey("ip_version"))
        assertEquals("false", (ipRule["rule_set_ip_cidr_match_source"] as JsonPrimitive).content)
        assertEquals(RoutingMatchType.IpRuleSet, result.inspection.rules.single().matchType)

        // Конфиг, сохранённый старой версией, перегенерируется без старой метки.
        val legacy = result.json.replace(
            "\"rule_set_ip_cidr_match_source\": false",
            "\"ip_version\": [4, 6]",
        )
        assertTrue(legacy.contains("\"ip_version\""))
        assertEquals(RoutingMatchType.IpRuleSet, RoutingConfigEditor.inspect(legacy).rules.single().matchType)
        val migrated = RoutingConfigEditor.apply(legacy, RoutingPreset.AllThroughVpn, rules, installed).json
        assertFalse(migrated.contains("ip_version"))
        assertEquals(1, routeRules(migrated).count { it.containsKey("rule_set_ip_cidr_match_source") })
    }

    @Test
    fun `unknown JSON and user remote rule-set survive managed edit`() {
        val source = profileWithRuleSet().replace(
            "\"route\": {",
            "\"extended_unknown\":{\"keep\":42},\"route\": {",
        )
        val result = RoutingConfigEditor.apply(source, RoutingPreset.RussiaDirect, emptyList(), installed)
        val root = parsed(result.json)
        val sets = ((root["route"] as JsonObject)["rule_set"] as JsonArray).map { it as JsonObject }

        assertEquals("42", ((root["extended_unknown"] as JsonObject)["keep"] as JsonPrimitive).content)
        assertTrue(sets.any { it.string("tag") == "user-ip" && it.string("type") == "remote" })
        assertTrue(sets.filter { it.string("tag")?.startsWith("zapret-") == true }
            .all { it.string("type") == "local" })
    }

    @Test
    fun `managed local paths are rebound`() {
        val first = RoutingConfigEditor.apply(profile(), RoutingPreset.RussiaDirect, emptyList(), installed).json
        val moved = InstalledRuleSets(
            1,
            installed.paths.mapValues { (_, path) -> path.replace("/data/user/0/app", "/data/user/10/app") },
        )
        val rebound = RoutingConfigEditor.rebindManagedRuleSetPaths(first, moved)
        val sets = ((parsed(rebound)["route"] as JsonObject)["rule_set"] as JsonArray)
            .map { it as JsonObject }

        assertTrue(sets.all { it.string("path")?.startsWith("/data/user/10/app") == true })
    }

    private fun edit(preset: RoutingPreset): String =
        RoutingConfigEditor.apply(profile(), preset, emptyList(), installed).json

    private fun profile(): String = ManagedProfileFactory.single(
        ManagedServer(
            displayName = "Server",
            identityKey = "server|one",
            outbound = JsonObject(mapOf("type" to JsonPrimitive("direct"))),
        ),
    )

    private fun profileWithRuleSet(): String {
        val root = parsed(profile()).toMutableMap()
        val route = (root["route"] as JsonObject).toMutableMap()
        route["rule_set"] = JsonArray(listOf(JsonObject(mapOf(
            "type" to JsonPrimitive("remote"),
            "tag" to JsonPrimitive("user-ip"),
            "format" to JsonPrimitive("binary"),
            "url" to JsonPrimitive("https://example.invalid/ip.srs"),
        ))))
        root["route"] = JsonObject(route)
        return JsonConfig.format(JsonObject(root))
    }

    private fun parsed(raw: String) = JsonConfig.parse(raw) as JsonObject
    private fun route(raw: String) = parsed(raw)["route"] as JsonObject
    private fun routeRules(raw: String) = (route(raw)["rules"] as JsonArray).map { it as JsonObject }
    private fun ruRule(raw: String) = routeRules(raw).first { rule ->
        val tags = (rule["rule_set"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        "zapret-ru-domains" in tags && "zapret-ru-ip" in tags
    }
    private fun isPrivate(rule: JsonObject) =
        (rule["ip_is_private"] as? JsonPrimitive)?.contentOrNull == "true"
}
