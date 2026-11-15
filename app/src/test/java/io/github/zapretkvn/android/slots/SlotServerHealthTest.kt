package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.network.probes.PersistedProbeResult
import io.github.zapretkvn.android.network.probes.PersistedServerLatency
import io.github.zapretkvn.android.vpn.LatencyFailure
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotServerHealthTest {
    private val now = 100_000_000L
    private val profile = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"
    private val hour = 60 * 60 * 1_000L

    private fun ok(ago: Long) = PersistedServerLatency(null, PersistedProbeResult(40, null, now - ago))
    private fun failed(ago: Long) = PersistedServerLatency(null, PersistedProbeResult(null, LatencyFailure.NoResponse, now - ago))

    private fun health(
        saved: Map<String, PersistedServerLatency> = emptyMap(),
        marks: Map<String, Long> = emptyMap(),
        foreign: List<ForeignServer> = emptyList(),
        foreignSaved: Map<String, PersistedServerLatency> = emptyMap(),
        videoMarks: Set<String> = emptySet(),
    ) = slotServerHealth(
        { id -> if (id == profile) saved else if (id == other) foreignSaved else emptyMap() },
        marks, profile, listOf("A", "B", "C"), foreign, now, videoMarks,
    )

    @Test
    fun `a fresh failure is failed and a recent good result is alive`() {
        val h = health(saved = mapOf("A" to failed(60_000), "B" to ok(60_000), "C" to ok(7 * hour)))
        assertEquals(setOf("A"), h.failed)
        assertEquals("a result older than six hours proves nothing", setOf("B"), h.alive)
    }

    @Test
    fun `a slot mark makes a server failed although its ping is fine`() {
        val mark = ServerRef(profile, "B").encode()
        val h = health(saved = mapOf("B" to ok(2 * hour)), marks = mapOf(mark to now - 1_000))
        assertTrue("B" in h.failed)
    }

    @Test
    fun `a good result newer than the mark overrules it`() {
        val mark = ServerRef(profile, "B").encode()
        val h = health(saved = mapOf("B" to ok(1_000)), marks = mapOf(mark to now - 60_000))
        assertTrue("B" !in h.failed)
        assertTrue("B" in h.alive)
    }

    @Test
    fun `a server of another profile is judged by its own profile and answers with its runtime tag`() {
        val server = ForeignServer(ServerRef(other, "X"), "Other", "X@abcd1234", buildJsonObject { })
        val marked = health(foreign = listOf(server), marks = mapOf(ServerRef(other, "X").encode() to now - 1_000))
        assertEquals(setOf("X@abcd1234"), marked.failed)
        val alive = health(foreign = listOf(server), foreignSaved = mapOf("X" to ok(1_000)))
        assertEquals(setOf("X@abcd1234"), alive.alive)
    }

    /** 0.6.23, field 2026-11-15 20:35: a "video starved" mark on Rusina kept the user's pin off it at connect. */
    @Test
    fun `a pin beats a video mark but never a real failure`() {
        val video = ServerRef(profile, "B").encode()
        val failure = ServerRef(profile, "C").encode()
        val h = health(
            saved = mapOf("A" to failed(60_000)),
            marks = mapOf(video to now - 1_000, failure to now - 1_000),
            videoMarks = setOf(video),
        )
        assertEquals(setOf("A", "B", "C"), h.failed)
        assertEquals(setOf("B"), h.videoOnly)
        assertEquals("pinned B starts there, unpinned servers stay avoided", setOf("A", "C"), h.blockedFor(listOf("B")))
        assertEquals("a pin on a failed server or a failure mark does not help", setOf("A", "B", "C"), h.blockedFor(listOf("A", "C", "D")))
        assertEquals(setOf("A", "C"), h.blockedFor(listOf("A", "B", "C")))
    }
}
