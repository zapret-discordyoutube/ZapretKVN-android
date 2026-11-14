package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.apps.PopularAppSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceSlotsTest {
    private val router = ServiceSlotRouter(SlotCatalog.builtIn())

    /** Archive 18 (2026-11-14): ReVanced is `app.morphe.android.youtube`; the Telegram dev fork is `org.zastogram.messenger.dev`. */
    @Test
    fun `every client of a service lands in the same slot`() {
        assertEquals(SlotIds.YOUTUBE, router.slotFor("app.morphe.android.youtube", null, null))
        assertEquals(SlotIds.YOUTUBE, router.slotFor("com.google.android.youtube", null, null))
        assertEquals(SlotIds.YOUTUBE, router.slotFor("app.revanced.android.apps.youtube.music", null, null))
        assertEquals(SlotIds.TELEGRAM, router.slotFor("org.telegram.messenger", null, null))
        assertEquals(SlotIds.TELEGRAM, router.slotFor("org.thunderdog.challegram", null, null))
        assertEquals(SlotIds.TELEGRAM, router.slotFor("org.zastogram.messenger.dev", null, null))
        assertEquals(SlotIds.AI, router.slotFor("com.openai.chatgpt", null, null))
        assertEquals(SlotIds.AI, router.slotFor("com.google.android.googlequicksearchbox", null, null))
    }

    @Test
    fun `a browser is sorted by where it goes, an unknown app and host fall to the default slot`() {
        assertEquals(SlotIds.YOUTUBE, router.slotFor("com.android.chrome", "rr1---sn-8ph2xajvh-3a4s.googlevideo.com", null))
        assertEquals(SlotIds.YOUTUBE, router.slotFor("com.android.chrome", "WWW.YouTube.com.", null))
        assertEquals(SlotIds.AI, router.slotFor("org.mozilla.firefox", "chatgpt.com", null))
        assertEquals(SlotIds.TELEGRAM, router.slotFor("com.android.chrome", "t.me", null))
        assertEquals(SlotIds.DEFAULT, router.slotFor("com.android.chrome", "example.org", "93.184.216.34"))
        assertEquals(SlotIds.DEFAULT, router.slotFor(null, null, null))
        assertEquals("a name that merely ends like a suffix is not a match", SlotIds.DEFAULT, router.slotFor(null, "notyoutube.com", null))
    }

    @Test
    fun `the app that owns a package wins over the domain it connects to`() {
        assertEquals(SlotIds.TELEGRAM, router.slotFor("org.telegram.messenger", "www.youtube.com", null))
        assertEquals(SlotIds.YOUTUBE, router.slotFor("app.morphe.android.youtube", "t.me", "149.154.167.35"))
    }

    @Test
    fun `an unknown Telegram fork is caught by the datacentre address`() {
        assertEquals(SlotIds.TELEGRAM, router.slotFor("com.example.unknownfork", null, "149.154.167.35"))
        assertEquals(SlotIds.TELEGRAM, router.slotFor("com.example.unknownfork", null, "91.108.56.130"))
        assertEquals(SlotIds.TELEGRAM, router.slotFor("com.example.unknownfork", null, "2001:67c:4e8:f002::7"))
        assertEquals("just outside 149.154.160.0/20", SlotIds.DEFAULT, router.slotFor("com.example.unknownfork", null, "149.154.176.1"))
        assertEquals(SlotIds.DEFAULT, router.slotFor("com.example.unknownfork", null, "2001:67c:4e9::1"))
        assertEquals("a host name is never resolved as an address", SlotIds.DEFAULT, router.slotFor(null, null, "example.org"))
    }

    @Test
    fun `a user slot listed first takes a package away from the built-in one`() {
        val mine = SlotDefinition("mine", "My Telegram", packages = setOf("org.telegram.messenger"))
        val custom = ServiceSlotRouter(listOf(mine) + SlotCatalog.builtIn())
        assertEquals("mine", custom.slotFor("org.telegram.messenger", null, null))
        assertEquals(SlotIds.TELEGRAM, custom.slotFor("org.thunderdog.challegram", null, null))
    }

    @Test
    fun `cidr arithmetic handles odd prefixes and the two address families`() {
        val net = Cidr.parse("91.108.4.0/22")!!
        assertTrue(net.contains(Cidr.literal("91.108.7.255")!!))
        assertFalse(net.contains(Cidr.literal("91.108.8.0")!!))
        assertFalse("an IPv4 address is not inside an IPv6 range", Cidr.parse("2a0a:f280::/32")!!.contains(Cidr.literal("10.0.0.1")!!))
        assertNull(Cidr.parse("10.0.0.0/33"))
        assertNull(Cidr.parse("10.0.0.0"))
        assertNull(Cidr.parse("example.org/24"))
        assertTrue(Cidr.parse("0.0.0.0/0")!!.contains(Cidr.literal("8.8.8.8")!!))
    }

    @Test
    fun `the catalogue stays consistent with the app picker and has no package in two slots`() {
        val slots = SlotCatalog.builtIn()
        val all = slots.flatMap { it.packages }
        assertEquals("a package is in one slot only", all.size, all.toSet().size)
        val unknown = all.filter { it !in PopularAppSuggestions.packageNames }
        assertTrue("slot packages missing from the picker catalogue: $unknown", unknown.isEmpty())
        assertEquals(SlotIds.DEFAULT, slots.last().id)
        assertEquals(listOf(SlotIds.AI, SlotIds.YOUTUBE, SlotIds.TELEGRAM, SlotIds.DEFAULT), slots.map { it.id })
        slots.forEach { slot -> slot.cidrs.forEach { assertTrue("bad range $it", Cidr.parse(it) != null) } }
    }

    private val slots = SlotCatalog.builtIn()
    private fun servers(vararg ids: String, dead: Set<String> = emptySet()) = ids.map { SlotServer(it, usable = it !in dead) }
    private fun List<SlotAssignment>.server(id: String) = first { it.slotId == id }.serverId
    private fun List<SlotAssignment>.choice(id: String) = first { it.slotId == id }.choice

    @Test
    fun `slots get different servers while the profile has enough`() {
        val result = SlotServerAssigner.assign(slots, servers("A", "B", "C", "D"))
        assertEquals(listOf("A", "B", "C", "D"), result.map { it.serverId })
        assertTrue(result.all { it.choice == SlotChoice.Chosen })
    }

    @Test
    fun `a small profile shares servers instead of leaving a slot without one`() {
        val result = SlotServerAssigner.assign(slots, servers("A", "B"))
        assertEquals(listOf("A", "B", "A", "B"), result.map { it.serverId })
        val single = SlotServerAssigner.assign(slots, servers("A"))
        assertTrue(single.all { it.serverId == "A" })
    }

    @Test
    fun `a pinned server wins and a slot keeps the server it already has`() {
        val pinned = slots.map { if (it.id == SlotIds.YOUTUBE) it.copy(pinned = "D") else it }
        val result = SlotServerAssigner.assign(pinned, servers("A", "B", "C", "D"), previous = mapOf(SlotIds.TELEGRAM to "B"))
        assertEquals("D", result.server(SlotIds.YOUTUBE))
        assertEquals(SlotChoice.Pinned, result.choice(SlotIds.YOUTUBE))
        assertEquals("B", result.server(SlotIds.TELEGRAM))
        assertEquals(SlotChoice.Kept, result.choice(SlotIds.TELEGRAM))
        assertEquals("the AI slot takes the first server nobody holds", "A", result.server(SlotIds.AI))
        assertEquals("C", result.server(SlotIds.DEFAULT))
    }

    /** Field 2026-11-14: the YouTube cluster was dead on Tiraru; only the YouTube slot may move. */
    @Test
    fun `when a server fails only the slots that used it move`() {
        val before = SlotServerAssigner.assign(slots, servers("A", "B", "C", "D"))
        val previous = before.associate { it.slotId to it.serverId!! }
        val after = SlotServerAssigner.assign(slots, servers("A", "B", "C", "D", dead = setOf("B")), previous)
        assertEquals(before.server(SlotIds.AI), after.server(SlotIds.AI))
        assertEquals(before.server(SlotIds.TELEGRAM), after.server(SlotIds.TELEGRAM))
        assertEquals(before.server(SlotIds.DEFAULT), after.server(SlotIds.DEFAULT))
        assertFalse("B" == after.server(SlotIds.YOUTUBE))
        assertEquals(SlotChoice.Chosen, after.choice(SlotIds.YOUTUBE))
    }

    @Test
    fun `a pinned server that is down is replaced until it comes back`() {
        val pinned = slots.map { if (it.id == SlotIds.TELEGRAM) it.copy(pinned = "C") else it }
        val down = SlotServerAssigner.assign(pinned, servers("A", "B", "C", dead = setOf("C")))
        assertFalse("C" == down.server(SlotIds.TELEGRAM))
        assertEquals(SlotChoice.PinUnavailable, down.choice(SlotIds.TELEGRAM))
        val back = SlotServerAssigner.assign(pinned, servers("A", "B", "C"), previous = down.associate { it.slotId to it.serverId!! })
        assertEquals("C", back.server(SlotIds.TELEGRAM))
        assertEquals(SlotChoice.Pinned, back.choice(SlotIds.TELEGRAM))
    }

    @Test
    fun `a slot pool limits the choice and an empty one gives no server`() {
        val limited = slots.map { if (it.id == SlotIds.AI) it.copy(pool = listOf("C")) else it }
        val result = SlotServerAssigner.assign(limited, servers("A", "B", "C"))
        assertEquals("C", result.server(SlotIds.AI))
        val gone = SlotServerAssigner.assign(limited, servers("A", "B", "C", dead = setOf("C")))
        assertNull(gone.server(SlotIds.AI))
        assertEquals(SlotChoice.None, gone.choice(SlotIds.AI))
        assertTrue("other slots are not affected", gone.server(SlotIds.YOUTUBE) != null)
        assertTrue(SlotServerAssigner.assign(slots, emptyList()).all { it.serverId == null })
    }

    @Test
    fun `the result is deterministic and follows the profile order`() {
        val a = SlotServerAssigner.assign(slots, servers("Z", "Y", "X", "W"))
        val b = SlotServerAssigner.assign(slots, servers("Z", "Y", "X", "W"))
        assertEquals(a, b)
        assertEquals(listOf("Z", "Y", "X", "W"), a.map { it.serverId })
    }

    @Test
    fun `the plan keeps the selected server for the default slot and sends the others elsewhere`() {
        val plan = SlotPlanner.plan(slots, listOf("A", "B", "C", "D"), selectedTag = "A")
        assertEquals(listOf("ai", "youtube", "telegram"), plan.specs.map { it.slotId })
        assertEquals("the main selector keeps A, so the AI slot starts on B", "B", plan.specs.first { it.slotId == "ai" }.defaultMember)
        assertEquals("C", plan.specs.first { it.slotId == "youtube" }.defaultMember)
        assertEquals("D", plan.specs.first { it.slotId == "telegram" }.defaultMember)
        assertTrue("every slot may use every server of the profile", plan.specs.all { it.members == listOf("A", "B", "C", "D") })
        assertEquals("A", plan.assignments.first { it.slotId == SlotIds.DEFAULT }.serverId)
    }

    @Test
    fun `a profile with one server gets no slots and an unknown selected server is ignored`() {
        assertTrue(SlotPlanner.plan(slots, listOf("A"), "A").specs.isEmpty())
        assertTrue(SlotPlanner.plan(slots, emptyList(), null).specs.isEmpty())
        val plan = SlotPlanner.plan(slots, listOf("A", "B"), selectedTag = "ghost")
        assertEquals(3, plan.specs.size)
    }

    @Test
    fun `installed packages with a fork prefix join the Telegram slot and the rest of the spec is complete`() {
        val installed = setOf("org.zastogram.messenger.dev", "com.android.chrome", "org.zastogram.messenger.beta")
        val telegram = SlotPlanner.plan(slots, listOf("A", "B"), "A", installed).specs.first { it.slotId == SlotIds.TELEGRAM }
        assertTrue("org.zastogram.messenger.dev" in telegram.packages)
        assertFalse("com.android.chrome" in telegram.packages)
        assertEquals("a package listed twice is sent once", telegram.packages.size, telegram.packages.toSet().size)
        assertTrue(telegram.domainSuffixes.isNotEmpty() && telegram.cidrs.isNotEmpty())
        assertEquals(telegram.packages.sorted(), telegram.packages)
    }
}
