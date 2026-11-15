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

    @Test
    fun `a mark counts for an hour and not longer, a mark from the future is dropped`() {
        val now = 10 * hour
        val live = SlotMarkRules.live(
            mapOf("fresh" to now - 1_000, "edge" to now - hour, "old" to now - hour - 1, "future" to now + 5_000),
            now,
        )
        assertEquals(setOf("fresh", "edge"), live.keys)
    }

    @Test
    fun `the newest entries survive when there are too many`() {
        val now = 10 * hour
        val many = (0 until SlotMarkRules.MAX_ENTRIES + 10).associate { "s$it" to now - it }
        val live = SlotMarkRules.live(many, now)
        assertEquals(SlotMarkRules.MAX_ENTRIES, live.size)
        assertTrue("s0" in live && "s${SlotMarkRules.MAX_ENTRIES + 9}" !in live)
    }

    @Test
    fun `marks survive encoding with emoji and punctuation in the key, garbage means no marks`() {
        val marks = mapOf("0123456789abcdef0123456789abcdef|\uD83C\uDDE9\uD83C\uDDEA Clermany, \"x\"" to 42L)
        assertEquals(marks, SlotMarkRules.decode(SlotMarkRules.encode(marks)))
        assertTrue(SlotMarkRules.decode("not json").isEmpty())
        assertTrue(SlotMarkRules.decode(null).isEmpty())
        assertTrue(SlotMarkRules.decode("""{"a":"text"}""").isEmpty())
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
}
