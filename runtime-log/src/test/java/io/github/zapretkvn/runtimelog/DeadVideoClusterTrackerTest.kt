package io.github.zapretkvn.runtimelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadVideoClusterTrackerTest {
    private val dead = "rr2---sn-8ph2xajvh-3a4l.googlevideo.com"

    /** Закрытый поток: создан за [lived] мс до закрытия в [closedAt]. */
    private fun closed(
        id: String,
        closedAt: Long,
        domain: String = dead,
        lived: Long = 8_000,
        up: Long = 2_048,
        down: Long = 0,
        network: String = "tcp",
    ) = FlowSnap(id, network, domain, "2.27.31.124:443", "app.youtube", "hy2-titiru", closedAt - lived, closedAt, up, down)

    private fun active(id: String, domain: String, down: Long, now: Long = 100_000) =
        FlowSnap(id, "tcp", domain, "1.2.3.4:443", "app.youtube", "hy2-titiru", now - 5_000, 0, 1_000, down)

    @Test
    fun `the cluster is read from the cache node name`() {
        assertEquals("sn-8ph2xajvh", DeadVideoClusterTracker.clusterOf("rr2---sn-8ph2xajvh-3a4l.googlevideo.com"))
        assertEquals("sn-8ph2xajvh", DeadVideoClusterTracker.clusterOf("r6---sn-8ph2xajvh-n8vs.googlevideo.com"))
        assertEquals("sn-2oi5h5", DeadVideoClusterTracker.clusterOf("RR5---SN-2OI5H5-5J.GoogleVideo.com."))
        assertEquals("sn-gxuo03g", DeadVideoClusterTracker.clusterOf("rr1---sn-gxuo03g.googlevideo.com"))
        // Поток получает имя по ответу DNS (CNAME на форму с точкой): архив 0.5.47, 2026-11-13 23:02:51.
        assertEquals("sn-8ph2xajvh", DeadVideoClusterTracker.clusterOf("rr2.sn-8ph2xajvh-3a4l.googlevideo.com"))
        assertEquals("sn-8ph2xajvh", DeadVideoClusterTracker.clusterOf("rr1.sn-8ph2xajvh-3a4s.googlevideo.com"))
        assertEquals("sn-gxuo03g", DeadVideoClusterTracker.clusterOf("rr1.sn-gxuo03g.googlevideo.com."))
        assertNull(DeadVideoClusterTracker.clusterOf("redirector.googlevideo.com"))
        assertNull(DeadVideoClusterTracker.clusterOf("googlevideo.com"))
        assertNull(DeadVideoClusterTracker.clusterOf("rr2---sn-8ph2xajvh-3a4l.example.com"))
        assertNull(DeadVideoClusterTracker.clusterOf("rr2.sn-8ph2xajvh-3a4l.example.com"))
        assertNull(DeadVideoClusterTracker.clusterOf("sn-8ph2xajvh.googlevideo.com"))
        assertNull(DeadVideoClusterTracker.clusterOf(""))
    }

    @Test
    fun `the archive 0-5-47 pattern with dotted names makes the cluster dead`() {
        // 23:02:51: два потока к rr2 и один к rr1 того же кластера, по 8.1 с, вверх 3 КБ, вниз 948 Б.
        val tracker = DeadVideoClusterTracker()
        val at = 1_731_538_971_000L
        val events = tracker.onClosed(
            at,
            listOf(
                closed("a", at, "rr2.sn-8ph2xajvh-3a4l.googlevideo.com", lived = 8_100, up = 3_072, down = 948),
                closed("b", at, "rr1.sn-8ph2xajvh-3a4s.googlevideo.com", lived = 8_100, up = 3_072, down = 948),
                closed("c", at, "rr2.sn-8ph2xajvh-3a4l.googlevideo.com", lived = 8_100, up = 3_072, down = 948),
            ),
        )
        assertEquals(listOf(DeadVideoClusterTracker.Event.Dead("sn-8ph2xajvh", 3, 0, "hy2-titiru")), events)
        assertEquals(listOf("sn-8ph2xajvh"), tracker.activeDeadClusters(at))
    }

    @Test
    fun `three silent flows to one cluster inside the window make it dead once`() {
        val tracker = DeadVideoClusterTracker()
        assertTrue(tracker.onClosed(100_000, listOf(closed("a", 100_000), closed("b", 101_000))).isEmpty())
        assertTrue(tracker.activeDeadClusters(101_000).isEmpty())
        val events = tracker.onClosed(102_000, listOf(closed("c", 102_000)))
        assertEquals(listOf(DeadVideoClusterTracker.Event.Dead("sn-8ph2xajvh", 3, 2_000, "hy2-titiru")), events)
        assertEquals(listOf("sn-8ph2xajvh"), tracker.activeDeadClusters(102_000))
        assertTrue("one event per episode", tracker.onClosed(103_000, listOf(closed("d", 103_000))).isEmpty())
    }

    @Test
    fun `the same flow delivered twice is counted once`() {
        val tracker = DeadVideoClusterTracker()
        repeat(3) { tracker.onClosed(100_000, listOf(closed("same", 100_000))) }
        assertTrue(tracker.activeDeadClusters(100_000).isEmpty())
    }

    @Test
    fun `flows spread wider than the window do not add up`() {
        val tracker = DeadVideoClusterTracker(windowMillis = 90_000)
        tracker.onClosed(100_000, listOf(closed("a", 100_000)))
        tracker.onClosed(150_000, listOf(closed("b", 150_000)))
        val events = tracker.onClosed(200_000, listOf(closed("c", 200_000)))
        assertTrue("a is 100 s old at the third flow", events.isEmpty())
        assertTrue(tracker.activeDeadClusters(200_000).isEmpty())
    }

    @Test
    fun `clusters are judged independently and names without a cluster are ignored`() {
        val tracker = DeadVideoClusterTracker()
        val other = "rr1---sn-gxuo03g-aaaa.googlevideo.com"
        tracker.onClosed(
            100_000,
            listOf(
                closed("a", 100_000), closed("b", 100_100), closed("x", 100_200, domain = other),
                closed("r1", 100_300, domain = "redirector.googlevideo.com"),
                closed("r2", 100_400, domain = "redirector.googlevideo.com"),
                closed("r3", 100_500, domain = "redirector.googlevideo.com"),
            ),
        )
        assertTrue(tracker.activeDeadClusters(100_500).isEmpty())
        tracker.onClosed(101_000, listOf(closed("c", 101_000)))
        assertEquals(listOf("sn-8ph2xajvh"), tracker.activeDeadClusters(101_000))
    }

    @Test
    fun `only silent flows count, not instant resets, flows with data, udp or flows that sent nothing`() {
        val tracker = DeadVideoClusterTracker()
        tracker.onClosed(
            100_000,
            listOf(
                closed("instant", 100_000, lived = 300),
                closed("with-data", 100_000, down = 20_000),
                closed("udp", 100_000, network = "udp"),
                closed("nothing-sent", 100_000, up = 0),
                closed("exactly-at-the-limit", 100_000, down = 4_096),
                closed("one-over-the-limit", 100_000, down = 4_097),
            ),
        )
        // only the flow at exactly 4096 bytes is silent: one flow is not a verdict
        assertTrue(tracker.activeDeadClusters(100_000).isEmpty())
        tracker.onClosed(100_500, listOf(closed("e", 100_500), closed("f", 100_600)))
        assertEquals(listOf("sn-8ph2xajvh"), tracker.activeDeadClusters(100_600))
    }

    @Test
    fun `a healthy flow of the cluster clears the verdict and reports the recovery`() {
        val tracker = DeadVideoClusterTracker()
        tracker.onClosed(100_000, listOf(closed("a", 100_000), closed("b", 100_100), closed("c", 100_200)))
        val events = tracker.onActive(130_000, listOf(active("ok", dead, down = 2_000_000, now = 130_000)))
        assertEquals(listOf(DeadVideoClusterTracker.Event.Recovered("sn-8ph2xajvh", 29_800)), events)
        assertTrue(tracker.activeDeadClusters(130_000).isEmpty())
        // the count starts over: two more silent flows are not enough
        tracker.onClosed(131_000, listOf(closed("d", 131_000), closed("e", 131_100)))
        assertTrue(tracker.activeDeadClusters(131_100).isEmpty())
    }

    @Test
    fun `a healthy closed flow also clears the count before the verdict`() {
        val tracker = DeadVideoClusterTracker()
        tracker.onClosed(100_000, listOf(closed("a", 100_000), closed("b", 100_100)))
        assertTrue(tracker.onClosed(101_000, listOf(closed("ok", 101_000, down = 5_000_000))).isEmpty())
        tracker.onClosed(102_000, listOf(closed("c", 102_000)))
        assertTrue("a and b were forgotten", tracker.activeDeadClusters(102_000).isEmpty())
    }

    @Test
    fun `the verdict fades when the player stops retrying and returns when it retries`() {
        val tracker = DeadVideoClusterTracker(windowMillis = 90_000)
        tracker.onClosed(100_000, listOf(closed("a", 100_000), closed("b", 100_100), closed("c", 100_200)))
        assertEquals(listOf("sn-8ph2xajvh"), tracker.activeDeadClusters(190_200))
        assertTrue("90 s after the last silent flow", tracker.activeDeadClusters(190_201).isEmpty())
        tracker.onClosed(300_000, listOf(closed("d", 300_000)))
        assertEquals("still dead, no new event needed", listOf("sn-8ph2xajvh"), tracker.activeDeadClusters(300_000))
    }

    @Test
    fun `reset forgets everything and the cluster needs a fresh run of silent flows`() {
        val tracker = DeadVideoClusterTracker()
        tracker.onClosed(100_000, listOf(closed("a", 100_000), closed("b", 100_100), closed("c", 100_200)))
        tracker.reset()
        assertTrue(tracker.activeDeadClusters(100_300).isEmpty())
        tracker.onClosed(101_000, listOf(closed("d", 101_000), closed("e", 101_100)))
        assertTrue("two flows are not enough after a reset", tracker.activeDeadClusters(101_100).isEmpty())
        val events = tracker.onClosed(101_200, listOf(closed("f", 101_200)))
        assertEquals(1, events.size)
        assertEquals(listOf("sn-8ph2xajvh"), tracker.activeDeadClusters(101_200))
    }

    @Test
    fun `the verdict field of the detector reports dead clusters`() {
        assertTrue(!SlowVideoDetector.Verdict.Idle.deadCluster)
        val verdict = SlowVideoDetector.Verdict.Idle.copy(deadClusters = listOf("sn-8ph2xajvh"))
        assertTrue(verdict.deadCluster)
    }
}
