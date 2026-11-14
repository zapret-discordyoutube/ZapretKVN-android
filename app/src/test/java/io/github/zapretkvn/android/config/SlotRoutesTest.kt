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
}
