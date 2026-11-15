package io.github.zapretkvn.android.slots

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SlotServerMarksTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val hour = SlotMarkRules.TTL_MILLIS

    private fun mark(at: Long, strikes: Int = 1) = SlotMarkRules.Mark(at, strikes)

    @Test
    fun `a first mark counts for an hour and not longer, a mark from the future is dropped`() {
        val now = 10 * hour
        val live = SlotMarkRules.live(
            mapOf("fresh" to mark(now - 1_000), "edge" to mark(now - hour), "old" to mark(now - hour - 1), "future" to mark(now + 5_000)),
            now,
        )
        assertEquals(setOf("fresh", "edge"), live.keys)
    }

    @Test
    fun `a server that fails again after its mark ran out gets a longer mark, up to eight hours`() {
        var marks = emptyMap<String, SlotMarkRules.Mark>()
        var now = 100 * hour
        marks = SlotMarkRules.add(marks, "a", now)
        assertEquals(1, marks.getValue("a").strikes)
        // failing again while the mark still runs (the slot had already left it) is not a repeat
        marks = SlotMarkRules.add(marks, "a", now + hour / 2)
        assertEquals(1, marks.getValue("a").strikes)
        now += hour / 2
        val expected = listOf(2, 3, 4, 4)
        for (strikes in expected) {
            now += SlotMarkRules.ttl(marks.getValue("a").strikes) + 1_000
            marks = SlotMarkRules.add(marks, "a", now)
            assertEquals(strikes, marks.getValue("a").strikes)
        }
        assertEquals("the ceiling is eight hours", 8 * hour, SlotMarkRules.ttl(marks.getValue("a").strikes))
        assertTrue("a" in SlotMarkRules.live(marks, now + 7 * hour))
        assertTrue("a" !in SlotMarkRules.live(marks, now + 8 * hour + 1))
    }

    @Test
    fun `a repeat is forgotten after a day and the server starts again from one hour`() {
        var marks = SlotMarkRules.add(emptyMap(), "a", 0L)
        marks = SlotMarkRules.add(marks, "a", 2 * hour)
        assertEquals(2, marks.getValue("a").strikes)
        marks = SlotMarkRules.add(marks, "a", 2 * hour + SlotMarkRules.MEMORY_MILLIS + 1)
        assertEquals(1, marks.getValue("a").strikes)
    }

    @Test
    fun `the newest entries survive when there are too many`() {
        val now = 10 * hour
        val many = (0 until SlotMarkRules.MAX_ENTRIES + 10).associate { "s$it" to mark(now - it) }
        val kept = SlotMarkRules.remembered(many, now)
        assertEquals(SlotMarkRules.MAX_ENTRIES, kept.size)
        assertTrue("s0" in kept && "s${SlotMarkRules.MAX_ENTRIES + 9}" !in kept)
    }

    @Test
    fun `marks survive encoding with emoji and punctuation in the key, old files and garbage are read safely`() {
        val marks = mapOf(
            "0123456789abcdef0123456789abcdef|\uD83C\uDDE9\uD83C\uDDEA Clermany, \"x\"" to mark(42L),
            "second" to mark(43L, strikes = 3),
        )
        assertEquals(marks, SlotMarkRules.decode(SlotMarkRules.encode(marks)))
        assertEquals("a file of 0.6.13 is one strike", mapOf("a" to mark(7L)), SlotMarkRules.decode("""{"a":7}"""))
        assertEquals("strikes are clamped", mapOf("a" to mark(7L, SlotMarkRules.MAX_STRIKES)), SlotMarkRules.decode("""{"a":[7,99]}"""))
        assertTrue(SlotMarkRules.decode("not json").isEmpty())
        assertTrue(SlotMarkRules.decode(null).isEmpty())
        assertTrue(SlotMarkRules.decode("""{"a":"text","b":[1],"c":{}}""").isEmpty())
    }

    @Test
    fun `a mark is written to the file and a new store reads it, until it expires`() {
        val file = File(folder.root, "slot-server-marks.json")
        var now = 1_000_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = SlotServerMarks(file, scope, { now }, Dispatchers.Unconfined)
        first.mark("server-a")
        assertEquals(setOf("server-a"), first.active().keys)
        val second = SlotServerMarks(file, scope, { now }, Dispatchers.Unconfined)
        assertEquals(setOf("server-a"), second.active().keys)
        now += hour + 1
        assertTrue("an hour later the mark is gone", second.active().isEmpty())
    }

    /** 0.6.20: the video starved on every server, so the mark the episode set on the first one is taken back exactly. */
    @Test
    fun `a forgiven mark goes back to what it was, a later mark is not touched`() {
        val now = 100 * hour
        val before = mark(now - 3 * hour, 2)
        val set = mark(now, 3)
        assertEquals(mapOf("a" to before), SlotMarkRules.undo(mapOf("a" to set), "a", before, set))
        assertTrue(SlotMarkRules.undo(mapOf("a" to set), "a", null, set).isEmpty())
        val later = mark(now + 1_000, 3)
        assertEquals(mapOf("a" to later), SlotMarkRules.undo(mapOf("a" to later), "a", before, set))
    }

    @Test
    fun `forgive undoes the last mark in the store and in the file, a repeat strike included`() {
        val file = File(folder.root, "slot-server-marks.json")
        var now = 100 * hour
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val marks = SlotServerMarks(file, scope, { now }, Dispatchers.Unconfined)
        marks.mark("fresh")
        marks.forgive("fresh")
        assertTrue(marks.active().isEmpty())
        // a repeat after the first mark ran out would double the next one: forgiving it keeps the old strike count
        marks.mark("repeat")
        now += hour + 1
        marks.mark("repeat")
        marks.forgive("repeat")
        assertTrue("the old mark has run out, nothing keeps the slot away", marks.active().isEmpty())
        now += 1
        marks.mark("repeat")
        assertEquals("still the second strike, not the third", 1, marks.active().size)
        now += hour + hour / 2
        assertTrue("a second strike lasts two hours: still live after 1.5 h", "repeat" in marks.active())
        val reread = SlotServerMarks(file, scope, { now }, Dispatchers.Unconfined)
        assertEquals(setOf("repeat"), reread.active().keys)
        marks.forgive("nothing-here")
    }

    /** 0.6.23: a "video starved" mark is softer than a failure mark, and survives the file. */
    @Test
    fun `a video mark stays video unless a real failure comes, and the file keeps the kind`() {
        val now = 100 * hour
        var marks = SlotMarkRules.add(emptyMap(), "a", now, video = true)
        assertEquals(setOf("a"), SlotMarkRules.liveVideoOnly(marks, now))
        marks = SlotMarkRules.add(marks, "a", now + 1_000)
        assertTrue("a failure turns it into a failure mark", SlotMarkRules.liveVideoOnly(marks, now + 1_000).isEmpty())
        marks = SlotMarkRules.add(marks, "a", now + 2_000, video = true)
        assertTrue("a video mark does not soften a running failure mark", SlotMarkRules.liveVideoOnly(marks, now + 2_000).isEmpty())
        val video = SlotMarkRules.add(emptyMap(), "v", now, video = true) + SlotMarkRules.add(emptyMap(), "f", now)
        val decoded = SlotMarkRules.decode(SlotMarkRules.encode(video))
        assertEquals(video, decoded)
        assertEquals(setOf("v"), SlotMarkRules.liveVideoOnly(decoded, now))
        assertEquals("old files read as failure marks", SlotMarkRules.Mark(5L, 2), SlotMarkRules.decode("""{"x":[5,2]}""")["x"])
    }
}
