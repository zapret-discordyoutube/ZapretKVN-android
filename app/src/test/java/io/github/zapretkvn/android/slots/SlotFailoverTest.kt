package io.github.zapretkvn.android.slots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotFailoverTest {
    private val servers = listOf("A", "B", "C", "D")
    private val slots = listOf(
        SlotDefinition(SlotIds.YOUTUBE, "YouTube"),
        SlotDefinition(SlotIds.TELEGRAM, "Telegram"),
        SlotDefinition(SlotIds.DEFAULT, "Everything else"),
    )
    private var now = 1_000_000L
    private fun policy(
        youtube: String = "B",
        telegram: String = "C",
        main: String = "A",
        list: List<SlotDefinition> = slots,
        pool: List<String> = servers,
    ) = SlotFailoverPolicy(
        list,
        pool,
        mapOf(SlotIds.YOUTUBE to youtube, SlotIds.TELEGRAM to telegram, SlotIds.DEFAULT to main),
    ) { now }

    private fun SlotFailoverPolicy.fail(server: String, times: Int, main: String = "A", dead: Set<String> = emptySet()): SlotFailoverDecision {
        var last: SlotFailoverDecision = SlotFailoverDecision.Ignore("none")
        repeat(times) {
            last = onFailureHint(server, main, dead)
            now += 1_000
        }
        return last
    }

    @Test
    fun `one or two hints are noise, the third within a minute moves the slot`() {
        val p = policy()
        assertTrue(p.fail("B", 2) is SlotFailoverDecision.Ignore)
        val decision = p.fail("B", 1)
        assertTrue(decision is SlotFailoverDecision.Move)
        decision as SlotFailoverDecision.Move
        assertEquals(1, decision.moves.size)
        assertEquals(SlotMove(SlotIds.YOUTUBE, "B", "D"), decision.moves.single())
        assertEquals("D", p.assignments().getValue(SlotIds.YOUTUBE))
    }

    @Test
    fun `hints spread over more than a minute never add up`() {
        val p = policy()
        repeat(6) {
            assertTrue(p.onFailureHint("B", "A") is SlotFailoverDecision.Ignore)
            now += 40_000
        }
        assertEquals("B", p.assignments().getValue(SlotIds.YOUTUBE))
    }

    @Test
    fun `only the slots on the failed server move, the other slot and the default stay`() {
        val p = policy()
        p.fail("B", 3)
        assertEquals("C", p.assignments().getValue(SlotIds.TELEGRAM))
        assertEquals("A", p.assignments().getValue(SlotIds.DEFAULT))
    }

    @Test
    fun `a replacement avoids the servers other slots and the main selector use while another is free`() {
        val p = policy(youtube = "B", telegram = "C", main = "A")
        val move = (p.fail("B", 3) as SlotFailoverDecision.Move).moves.single()
        assertEquals("the unused server D is taken", "D", move.to)
    }

    @Test
    fun `a hint for a server only the main selector uses is not the slots' business`() {
        val p = policy()
        val decision = p.fail("A", 5)
        assertTrue(decision is SlotFailoverDecision.Ignore)
        assertEquals("no slot uses A", (decision as SlotFailoverDecision.Ignore).reason)
    }

    @Test
    fun `servers the main supervisor confirmed dead are avoided too`() {
        val p = policy()
        val move = (p.fail("B", 3, dead = setOf("D")) as SlotFailoverDecision.Move).moves.single()
        assertEquals("D is dead for the main selector, so the slot shares a live server", "A", move.to)
    }

    @Test
    fun `with no usable replacement the slot stays where it is`() {
        val tiny = policy(youtube = "B", telegram = "A", main = "A", pool = listOf("A", "B"))
        val decision = tiny.fail("B", 3)
        assertTrue(decision is SlotFailoverDecision.Move)
        assertEquals("the only other server is better than a dead one", "A", tiny.assignments().getValue(SlotIds.YOUTUBE))
        val none = policy(youtube = "B", telegram = "B", main = "B", pool = listOf("B"))
        assertTrue(none.fail("B", 3) is SlotFailoverDecision.Ignore)
        assertEquals("B", none.assignments().getValue(SlotIds.YOUTUBE))
    }

    @Test
    fun `stale lines of the server a slot just left are ignored`() {
        val p = policy()
        p.fail("B", 3)
        now -= 1_000
        val late = p.onFailureHint("B", "A")
        assertTrue(late is SlotFailoverDecision.Ignore)
    }

    @Test
    fun `a network-wide outage cannot make the slots hop forever`() {
        val p = policy()
        var moves = 0
        val names = listOf("B", "D", "C", "A", "B", "D")
        for (name in names) {
            val holder = p.assignments().entries.firstOrNull { it.key != SlotIds.DEFAULT && it.value == name } ?: continue
            if (p.fail(name, 3) is SlotFailoverDecision.Move) moves++
            now += 1_000
            assertTrue(holder.key.isNotEmpty())
        }
        assertTrue("at most ${SlotFailoverPolicy.MAX_MOVES} moves in the window, got $moves", moves <= SlotFailoverPolicy.MAX_MOVES)
    }

    @Test
    fun `a refused command puts the slot back and the dead mark expires`() {
        val p = policy()
        val move = (p.fail("B", 3) as SlotFailoverDecision.Move).moves.single()
        p.moveFailed(move)
        assertEquals("B", p.assignments().getValue(SlotIds.YOUTUBE))
        assertTrue("B" in p.deadServers())
        now += SlotFailoverPolicy.DEAD_MILLIS + 1
        assertFalse("B" in p.deadServers())
    }

    @Test
    fun `slot servers exclude the default slot`() {
        val p = policy()
        assertEquals(setOf("B", "C"), p.slotServers())
    }

    @Test
    fun `a pinned slot whose server fails moves off it until the server is back`() {
        val pinned = listOf(
            SlotDefinition(SlotIds.YOUTUBE, "YouTube", pinned = "B"),
            SlotDefinition(SlotIds.TELEGRAM, "Telegram"),
            SlotDefinition(SlotIds.DEFAULT, "Everything else"),
        )
        val p = policy(list = pinned)
        val move = (p.fail("B", 3) as SlotFailoverDecision.Move).moves.single()
        assertEquals(SlotIds.YOUTUBE, move.slotId)
        assertTrue(move.to != "B")
    }
}
