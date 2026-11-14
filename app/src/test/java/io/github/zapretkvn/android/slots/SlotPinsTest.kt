package io.github.zapretkvn.android.slots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotPinsTest {
    @Test
    fun `pins survive a round trip with emoji, spaces and quotes in the tag`() {
        val pins = mapOf("youtube" to "🇷🇺 Titiru \"fast\"; one=two", "telegram" to "Plain")
        assertEquals(pins, SlotPins.decode(SlotPins.encode(pins)))
    }

    @Test
    fun `unreadable or empty text means no pins`() {
        assertTrue(SlotPins.decode(null).isEmpty())
        assertTrue(SlotPins.decode("").isEmpty())
        assertTrue(SlotPins.decode("not json").isEmpty())
        assertTrue(SlotPins.decode("[1,2]").isEmpty())
        assertEquals(mapOf("ai" to "A"), SlotPins.decode("""{"ai":"A","bad":5,"blank":" "}"""))
    }

    @Test
    fun `with sets and removes, and the default slot is never pinned`() {
        val one = SlotPins.with(emptyMap(), "youtube", "A")
        assertEquals(mapOf("youtube" to "A"), one)
        assertEquals(mapOf("youtube" to "B"), SlotPins.with(one, "youtube", "B"))
        assertTrue(SlotPins.with(one, "youtube", null).isEmpty())
        assertEquals(one, SlotPins.with(one, SlotIds.DEFAULT, "C"))
        assertEquals(one, SlotPins.forSlots(one + (SlotIds.DEFAULT to "C")))
    }

    @Test
    fun `the planner starts a pinned slot on its server and keeps the others apart`() {
        val plan = SlotPlanner.plan(
            SlotCatalog.withPins(mapOf("youtube" to "C", SlotIds.DEFAULT to "B")),
            listOf("A", "B", "C", "D"),
            selectedTag = "A",
        )
        assertEquals("C", plan.specs.first { it.slotId == "youtube" }.defaultMember)
        assertEquals(SlotChoice.Pinned, plan.assignments.first { it.slotId == "youtube" }.choice)
        assertEquals("the default slot keeps the selected server", "A", plan.assignments.first { it.slotId == SlotIds.DEFAULT }.serverId)
        assertEquals(3, plan.specs.map { it.defaultMember }.toSet().size)
    }

    @Test
    fun `a pin on a server the profile does not have falls back to automatic choice`() {
        val plan = SlotPlanner.plan(SlotCatalog.withPins(mapOf("youtube" to "gone")), listOf("A", "B", "C"), selectedTag = "A")
        val youtube = plan.assignments.first { it.slotId == "youtube" }
        assertEquals(SlotChoice.PinUnavailable, youtube.choice)
        assertTrue(youtube.serverId in listOf("A", "B", "C"))
    }

    @Test
    fun `live pinning moves the slot at once, also off a server marked dead, and unpinning moves nothing`() {
        var now = 0L
        val slots = SlotCatalog.builtIn()
        val servers = listOf("A", "B", "C", "D")
        val policy = SlotFailoverPolicy(slots, servers, mapOf("youtube" to "B", "telegram" to "C", "ai" to "D", SlotIds.DEFAULT to "A")) { now }
        val pool = { _: String -> servers }
        val moves = policy.setPins(mapOf("youtube" to "D"), pool)
        assertEquals(listOf(SlotMove("youtube", "B", "D")), moves)
        assertEquals("D", policy.serverOf("youtube"))
        assertTrue("pinning again changes nothing", policy.setPins(mapOf("youtube" to "D"), pool).isEmpty())
        // The user's explicit choice beats the dead mark.
        repeat(3) { now += 1_000; policy.onFailureHint("C", "A") }
        assertTrue("C" in policy.deadServers())
        assertEquals(listOf(SlotMove("youtube", "D", "C")), policy.setPins(mapOf("youtube" to "C"), pool))
        // Unpinned: stays where it is.
        assertTrue(policy.setPins(emptyMap(), pool).isEmpty())
        assertEquals("C", policy.serverOf("youtube"))
        // A pin outside the pool or the profile is ignored.
        assertTrue(policy.setPins(mapOf("youtube" to "Z"), pool).isEmpty())
        assertTrue(policy.setPins(mapOf("youtube" to "A")) { listOf("B", "C") }.isEmpty())
    }

    @Test
    fun `a pinned slot whose server dies moves off it and the pin stays recorded`() {
        var now = 0L
        val servers = listOf("A", "B", "C", "D")
        val policy = SlotFailoverPolicy(SlotCatalog.builtIn(), servers, mapOf("youtube" to "B", "telegram" to "C", "ai" to "D", SlotIds.DEFAULT to "A")) { now }
        policy.setPins(mapOf("youtube" to "B")) { servers }
        var decision: SlotFailoverDecision = SlotFailoverDecision.Ignore("none")
        repeat(3) { now += 1_000; decision = policy.onFailureHint("B", "A") }
        assertTrue(decision is SlotFailoverDecision.Move)
        assertTrue(policy.serverOf("youtube") != "B")
    }
}
