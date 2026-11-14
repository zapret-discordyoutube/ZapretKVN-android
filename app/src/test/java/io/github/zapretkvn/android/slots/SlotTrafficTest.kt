package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.engines.failover.FailoverTarget
import io.github.zapretkvn.runtimelog.FlowSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotTrafficTest {
    private val router = ServiceSlotRouter(SlotCatalog.builtIn())

    private fun flow(id: String, down: Long, pkg: String = "", domain: String = "", dest: String = "203.0.113.5:443") =
        FlowSnap(id, "tcp", domain, dest, pkg, "server", 0, 0, 0, down)

    @Test
    fun `bytes are counted per slot by the same rule the config uses`() {
        val traffic = SlotTraffic(router)
        traffic.onSnapshot(
            active = listOf(
                flow("1", 1_000, pkg = "org.telegram.messenger"),
                flow("2", 5_000, pkg = "app.morphe.android.youtube", domain = "rr1.googlevideo.com"),
                flow("3", 700, pkg = "com.example.other", domain = "example.com"),
                flow("4", 300, dest = "149.154.167.51:443"),
            ),
            closed = emptyList(),
        )
        assertEquals(1_300L, traffic.downlinkOf(SlotIds.TELEGRAM))
        assertEquals(5_000L, traffic.downlinkOf(SlotIds.YOUTUBE))
        assertEquals(700L, traffic.downlinkOf(SlotIds.DEFAULT))
        assertEquals(6_300L, traffic.downlinkOutsideDefault())
        assertEquals(3, traffic.activeOutsideDefault())
    }

    @Test
    fun `a flow is counted by its increments and a closing record adds only what is new`() {
        val traffic = SlotTraffic(router)
        traffic.onSnapshot(listOf(flow("1", 1_000, pkg = "org.telegram.messenger")), emptyList())
        traffic.onSnapshot(listOf(flow("1", 4_000, pkg = "org.telegram.messenger")), emptyList())
        assertEquals(4_000L, traffic.downlinkOf(SlotIds.TELEGRAM))
        traffic.onSnapshot(emptyList(), listOf(flow("1", 4_500, pkg = "org.telegram.messenger")))
        assertEquals(4_500L, traffic.downlinkOf(SlotIds.TELEGRAM))
        assertEquals(0, traffic.activeOutsideDefault())
        // A flow that opened and closed between two snapshots is counted once, in full.
        traffic.onSnapshot(emptyList(), listOf(flow("2", 800, pkg = "org.telegram.messenger")))
        assertEquals(5_300L, traffic.downlinkOf(SlotIds.TELEGRAM))
    }

    @Test
    fun `a flow past the snapshot limit that comes back is not counted twice`() {
        val traffic = SlotTraffic(router)
        traffic.onSnapshot(listOf(flow("1", 1_000, pkg = "org.telegram.messenger")), emptyList())
        traffic.onSnapshot(emptyList(), emptyList())
        traffic.onSnapshot(listOf(flow("1", 1_500, pkg = "org.telegram.messenger")), emptyList())
        assertEquals(1_500L, traffic.downlinkOf(SlotIds.TELEGRAM))
    }

    @Test
    fun `the default slot's own counters leave out what the other slots carried`() {
        assertEquals(10_000L to 4, SlotTraffic.own(10_000, 4, null))
        assertEquals(3_000L to 1, SlotTraffic.own(10_000, 4, 7_000L to 3))
        // Over-subtracted (a slot byte counted before the status counter): the counter steps back, the detector resets.
        assertEquals(-500L to 0, SlotTraffic.own(1_000, 1, 1_500L to 5))
    }

    @Test
    fun `destination hosts are read from host-port and bracketed forms`() {
        assertEquals("203.0.113.5", SlotTraffic.hostOf("203.0.113.5:443"))
        assertEquals("2001:db8::1", SlotTraffic.hostOf("[2001:db8::1]:443"))
        assertEquals("example.com", SlotTraffic.hostOf("example.com:80"))
        assertNull(SlotTraffic.hostOf(" "))
    }

    @Test
    fun `candidates avoid dead servers and servers other slots use while any other server is left`() {
        val members = listOf("A", "B", "C", "D")
        fun ids(list: List<FailoverTarget>) = list.map { it.id }
        assertEquals(listOf("B", "D"), ids(SlotCandidates.targets(members, null, "B", usedByOthers = setOf("A", "C"), dead = emptySet())))
        assertEquals(listOf("A", "B", "C"), ids(SlotCandidates.targets(members, null, "B", usedByOthers = setOf("A", "C", "D"), dead = setOf("D"))))
        // Everything else is dead: better a dead-marked server than none.
        assertEquals(members, ids(SlotCandidates.targets(members, null, "B", usedByOthers = emptySet(), dead = setOf("A", "C", "D"))))
    }

    @Test
    fun `plan flags are kept and maintenance servers are not candidates`() {
        val plan = listOf(FailoverTarget("A", valid = true, maintenance = true), FailoverTarget("B", valid = true, maintenance = false))
        val result = SlotCandidates.targets(listOf("A", "B"), plan, "B", emptySet(), emptySet())
        assertEquals(true, result.first { it.id == "A" }.maintenance)
    }
}
