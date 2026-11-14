package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotRoutesTest {
    private val youtube = SlotRouteSpec(
        slotId = "youtube",
        members = listOf("server-b", "server-c"),
        defaultMember = "server-b",
        packages = listOf("app.morphe.android.youtube", "com.google.android.youtube"),
        domainSuffixes = listOf("googlevideo.com", "youtube.com"),
    )
    private val telegram = SlotRouteSpec(
        slotId = "telegram",
        members = listOf("server-a", "server-c"),
        defaultMember = "server-c",
        packages = listOf("org.telegram.messenger"),
        domainSuffixes = listOf("t.me"),
        cidrs = listOf("149.154.160.0/20"),
    )
    private val userRule = """{"domain_suffix":["example.ru"],"action":"route","outbound":"direct"}"""

    private fun config(managed: Boolean = true): String = """
        {
          "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
          "outbounds":[
            {"type":"direct","tag":"server-a"},
            {"type":"direct","tag":"server-b"},
            {"type":"direct","tag":"server-c"},
            ${if (managed) """{"type":"selector","tag":"zapret-proxy","outbounds":["server-a","server-b","server-c"],"default":"server-a"},""" else ""}
            {"type":"direct","tag":"direct"}
          ],
          "route":{"auto_detect_interface":true,"final":"${if (managed) "zapret-proxy" else "server-a"}","rules":[$userRule]}
        }
    """.trimIndent()

    private fun build(
        slots: List<SlotRouteSpec>,
        mode: DnsMode = DnsMode.Automatic,
        blockQuic: Boolean = true,
        raw: String = config(),
    ): JsonObject {
        val built = RuntimeConfigBuilder.build(
            raw,
            options = RuntimeConfigOptions(dnsMode = mode, blockQuic = blockQuic, slotRoutes = slots),
        ) as RuntimeConfigResult.Ready
        return JsonConfig.parse(built.json) as JsonObject
    }

    private fun JsonObject.rules() = ((this["route"] as JsonObject)["rules"] as JsonArray).map { it as JsonObject }
    private fun JsonObject.outbounds() = (this["outbounds"] as JsonArray).map { it as JsonObject }
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.strings(key: String) = (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()

    @Test
    fun `each slot gets its own selector over its pool and the main selector stays as it was`() {
        val root = build(listOf(youtube, telegram))
        val byTag = root.outbounds().associateBy { it.str("tag") }
        val yt = byTag.getValue("zapret-slot-youtube")
        assertEquals("selector", yt.str("type"))
        assertEquals(listOf("server-b", "server-c"), yt.strings("outbounds"))
        assertEquals("server-b", yt.str("default"))
        assertEquals("a slot switch cuts only that slot's connections", true, (yt["interrupt_exist_connections"] as JsonPrimitive).boolean)
        assertEquals("server-c", byTag.getValue("zapret-slot-telegram").str("default"))
        val main = byTag.getValue(ConfigAnalyzer.MANAGED_SELECTOR_TAG)
        assertEquals(listOf("server-a", "server-b", "server-c"), main.strings("outbounds"))
        assertEquals("every server is still defined once", 1, root.outbounds().count { it.str("tag") == "server-b" })
    }

    @Test
    fun `slot rules sit after the QUIC reject, before the forced rule and the user rules, apps before names before addresses`() {
        for (mode in listOf(DnsMode.Automatic, DnsMode.Secure, DnsMode.Android)) {
            val rules = build(listOf(youtube, telegram), mode).rules()
            val quic = rules.indexOfFirst { it.str("action") == "reject" && "port" in it }
            val forced = rules.indexOfFirst(ForcedProxyDomains::isForcedRule)
            val user = rules.indexOfFirst { it.strings("domain_suffix") == listOf("example.ru") }
            val slotRules = rules.withIndex().filter { (it.value.str("outbound") ?: "").startsWith(SlotSelectors.PREFIX) }
            assertEquals("mode $mode: package rules for both slots, names for both, one address rule", 5, slotRules.size)
            assertTrue("mode $mode: QUIC reject stays above every slot rule", quic in 0 until slotRules.first().index)
            assertTrue("mode $mode: slot rules above the forced rule", slotRules.last().index < forced)
            assertTrue("mode $mode: forced rule above the user rule", forced < user)
            val kinds = slotRules.map { entry -> listOf("package_name", "domain_suffix", "ip_cidr").first { it in entry.value } }
            assertEquals(listOf("package_name", "package_name", "domain_suffix", "domain_suffix", "ip_cidr"), kinds)
            assertEquals("the app of a slot beats the name it connects to", "zapret-slot-youtube", slotRules.first().value.str("outbound"))
        }
    }

    @Test
    fun `no slots means exactly the old config and a rebuild does not multiply the rules`() {
        val plain = RuntimeConfigBuilder.build(config(), options = RuntimeConfigOptions(blockQuic = true)) as RuntimeConfigResult.Ready
        val none = RuntimeConfigBuilder.build(config(), options = RuntimeConfigOptions(blockQuic = true, slotRoutes = emptyList()))
            as RuntimeConfigResult.Ready
        assertEquals(plain.json, none.json)
        assertFalse("zapret-slot-" in plain.json)
        val once = build(listOf(youtube))
        val twice = build(listOf(youtube))
        assertEquals(once, twice)
        assertEquals(1, once.rules().count { it.str("outbound") == "zapret-slot-youtube" && "package_name" in it })
    }

    @Test
    fun `unknown pool members are dropped, a broken default is repaired and an empty slot falls through to the main selector`() {
        val sloppy = youtube.copy(members = listOf("server-b", "ghost", "server-b"), defaultMember = "ghost")
        val gone = telegram.copy(members = listOf("ghost-1", "ghost-2"))
        val root = build(listOf(sloppy, gone))
        val yt = root.outbounds().first { it.str("tag") == "zapret-slot-youtube" }
        assertEquals(listOf("server-b"), yt.strings("outbounds"))
        assertEquals("server-b", yt.str("default"))
        assertTrue("a slot without members is not created", root.outbounds().none { it.str("tag") == "zapret-slot-telegram" })
        assertTrue("and has no rules", root.rules().none { it.str("outbound") == "zapret-slot-telegram" })
    }

    @Test
    fun `a profile without the managed selector ignores slots and the stored profile is never edited`() {
        val raw = config(managed = false)
        val root = build(listOf(youtube), mode = DnsMode.FromJson, raw = raw)
        assertTrue(root.outbounds().none { (it.str("tag") ?: "").startsWith(SlotSelectors.PREFIX) })
        assertFalse("the stored profile carries no slot", "zapret-slot" in raw)
        val managedRaw = config()
        build(listOf(youtube), raw = managedRaw)
        assertFalse("zapret-slot" in managedRaw)
    }

    @Test
    fun `slot selector tags are recognised and never collide with the main selector`() {
        assertTrue(SlotSelectors.isSlotSelector(SlotSelectors.tag("youtube")))
        assertFalse(SlotSelectors.isSlotSelector(ConfigAnalyzer.MANAGED_SELECTOR_TAG))
        assertFalse(SlotSelectors.isSlotSelector(null))
    }

    @Test
    fun `managed servers are read from the managed selector only`() {
        val managed = SlotRoutes.managedServers(JsonConfig.parse(config()) as JsonObject)!!
        assertEquals(listOf("server-a", "server-b", "server-c"), managed.tags)
        assertEquals("server-a", managed.selected)
        assertEquals(null, SlotRoutes.managedServers(JsonConfig.parse(config(managed = false)) as JsonObject))
        assertEquals(null, SlotRoutes.managedServers(null))
    }

    private fun JsonObject.dns() = this["dns"] as JsonObject
    private fun JsonObject.dnsServers() = (dns()["servers"] as JsonArray).map { it as JsonObject }
    private fun JsonObject.dnsRules() = (dns()["rules"] as JsonArray).map { it as JsonObject }

    @Test
    fun `a slot resolves its names through the node it leaves through, with the core cache off`() {
        for (mode in listOf(DnsMode.Automatic, DnsMode.Secure)) {
            val root = build(listOf(youtube, telegram), mode)
            val byTag = root.dnsServers().associateBy { it.str("tag") }
            val udp = byTag.getValue("zapret-slot-dns-youtube-udp")
            assertEquals("mode $mode", "zapret-slot-youtube", udp.str("detour"))
            assertEquals("zapret-slot-youtube", byTag.getValue("zapret-slot-dns-youtube-tcp").str("detour"))
            val race = byTag.getValue("zapret-slot-dns-youtube")
            assertEquals("fallback", race.str("type"))
            assertEquals(listOf("zapret-slot-dns-youtube-udp", "zapret-slot-dns-youtube-tcp"), race.strings("servers"))
            assertEquals("zapret-slot-telegram", byTag.getValue("zapret-slot-dns-telegram-udp").str("detour"))
            assertEquals("the main node DNS stays on the main selector", ConfigAnalyzer.MANAGED_SELECTOR_TAG, byTag.getValue("zapret-node-dns-udp").str("detour"))

            val slotRules = root.dnsRules().filter { (it.str("server") ?: "").startsWith("zapret-slot-dns-") }
            assertEquals("mode $mode: apps then names for both slots", 4, slotRules.size)
            assertEquals(listOf("package_name", "package_name", "domain_suffix", "domain_suffix"),
                slotRules.map { r -> listOf("package_name", "domain_suffix").first { it in r } })
            assertTrue(slotRules.all { (it["disable_cache"] as JsonPrimitive).boolean })
            assertEquals("zapret-slot-dns-youtube", slotRules.first().str("server"))
            val firstSlot = root.dnsRules().indexOfFirst { (it.str("server") ?: "").startsWith("zapret-slot-dns-") }
            val firstOther = root.dnsRules().indexOfFirst {
                val server = it.str("server") ?: return@indexOfFirst false
                server.startsWith("zapret-") && !server.startsWith("zapret-slot-dns-") && server != "zapret-dns-override"
            }
            assertTrue("mode $mode: slot DNS rules come before the main node DNS rules", firstOther == -1 || firstSlot < firstOther)
        }
    }

    @Test
    fun `no slots or the system DNS mode add no slot DNS and a rebuild does not multiply it`() {
        val plain = build(emptyList())
        assertTrue(plain.dnsServers().none { (it.str("tag") ?: "").startsWith("zapret-slot-dns-") })
        assertTrue(plain.dnsRules().none { (it.str("server") ?: "").startsWith("zapret-slot-dns-") })
        val fromJson = build(listOf(youtube), DnsMode.FromJson)
        assertTrue("the profile's own DNS is not touched", fromJson.dnsServers().none { (it.str("tag") ?: "").startsWith("zapret-slot-dns-") })
        val once = build(listOf(youtube))
        val twice = build(listOf(youtube))
        assertEquals(once, twice)
        assertEquals(1, once.dnsServers().count { it.str("tag") == "zapret-slot-dns-youtube" })
    }

    // ---- Review of 2026-11-14 ----

    @Test
    fun `a runtime copy fed back in is rebuilt without doubling slot selectors or slot rules`() {
        val options = RuntimeConfigOptions(dnsMode = DnsMode.Automatic, blockQuic = true, slotRoutes = listOf(youtube))
        val first = RuntimeConfigBuilder.build(config(), options = options) as RuntimeConfigResult.Ready
        val second = RuntimeConfigBuilder.build(first.json, options = options) as RuntimeConfigResult.Ready
        val parsed = JsonConfig.parse(second.json) as JsonObject
        assertEquals(1, parsed.outbounds().count { it.str("tag") == "zapret-slot-youtube" })
        val slotRules = { root: JsonObject -> root.rules().filter { it.str("outbound")?.startsWith("zapret-slot-") == true } }
        assertEquals(slotRules(JsonConfig.parse(first.json) as JsonObject), slotRules(parsed))
    }

    private fun userDnsConfig(final: String = "zapret-proxy"): String = config().replace(
        """"route":{""",
        """"dns":{"servers":[{"type":"udp","tag":"corp-dns","server":"10.0.0.1"}],"rules":[{"domain_suffix":["corp.example"],"action":"route","server":"corp-dns"}]},"route":{""",
    ).replace("\"final\":\"zapret-proxy\"", "\"final\":\"$final\"")

    @Test
    fun `the user's own dns rule is not shadowed by the generated catch-all`() {
        for (mode in listOf(DnsMode.Automatic, DnsMode.Secure)) {
            val rules = build(emptyList(), mode = mode, raw = userDnsConfig()).dnsRules()
            val user = rules.indexOfFirst { it.strings("domain_suffix") == listOf("corp.example") }
            assertTrue("mode $mode: the user's dns rule is kept", user >= 0)
            val catchAll = rules.indexOfFirst { it.keys.all { k -> k == "action" || k == "server" || k == "strategy" } }
            if (catchAll >= 0) assertTrue("mode $mode: the catch-all stays last", user < catchAll)
            assertEquals("mode $mode: the catch-all closes the list", rules.size - 1, catchAll.takeIf { it >= 0 } ?: (rules.size - 1))
        }
    }

    @Test
    fun `names that always go through the VPN are resolved by the node also when the final route is direct`() {
        val forced = ForcedProxyDomains.suffixes
        val direct = build(emptyList(), mode = DnsMode.Automatic, raw = userDnsConfig(final = "direct")).dnsRules()
        val rule = direct.single { it.strings("domain_suffix") == forced }
        assertEquals("zapret-secure-dns", rule.str("server"))
        // A rebuild does not multiply it.
        assertEquals(1, build(emptyList(), mode = DnsMode.Automatic, raw = userDnsConfig(final = "direct")).dnsRules()
            .count { it.strings("domain_suffix") == forced })
        // The user's own DNS stays untouched in "From JSON".
        val fromJson = build(emptyList(), mode = DnsMode.FromJson, raw = userDnsConfig(final = "direct")).dnsRules()
        assertTrue(fromJson.none { it.strings("domain_suffix") == forced })
    }
}
