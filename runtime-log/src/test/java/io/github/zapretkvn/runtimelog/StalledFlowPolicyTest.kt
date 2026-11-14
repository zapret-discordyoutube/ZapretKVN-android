package io.github.zapretkvn.runtimelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StalledFlowPolicyTest {
    private fun flow(
        id: String,
        domain: String = "rr2.sn-x.googlevideo.com",
        network: String = "tcp",
        created: Long = 1_000,
        up: Long = 900,
        down: Long = 647,
    ) = FlowSnap(id, network, domain, "74.125.11.74:443", "app.youtube", "hy2", created, 0, up, down)

    private val now = 20_000L

    @Test
    fun `a googlevideo tcp flow that is old, sent a request and received almost nothing is picked`() {
        val picked = StalledFlowPolicy().pick(now, listOf(flow("a")))
        assertEquals(listOf("a"), picked.map { it.id })
    }

    @Test
    fun `young flows healthy flows and flows without a request are left alone`() {
        val policy = StalledFlowPolicy()
        val picked = policy.pick(
            now,
            listOf(
                flow("young", created = now - 7_999),
                flow("downloading", down = 5_000_000),
                flow("nothing-sent", up = 0),
                flow("exactly-at-the-limit", down = 4_096, created = 1_000),
            ),
        )
        assertEquals(listOf("exactly-at-the-limit"), picked.map { it.id })
    }

    /** Замер узла 2026-11-14: кластер принимает TCP и молчит; здоровый поток получает ServerHello за 0.1-0.3 с. */
    @Test
    fun `a flow that got zero server bytes is reset after 3 seconds, one with a few bytes still after 8`() {
        val policy = StalledFlowPolicy()
        val picked = policy.pick(
            now,
            listOf(
                flow("silent-3s", created = now - 3_000, down = 0),
                flow("silent-2-9s", created = now - 2_999, down = 0),
                flow("some-bytes-5s", created = now - 5_000, down = 647),
                flow("some-bytes-8s", created = now - 8_000, down = 647),
                flow("silent-but-nothing-sent", created = now - 9_000, up = 0, down = 0),
            ),
        )
        assertEquals(setOf("silent-3s", "some-bytes-8s"), picked.map { it.id }.toSet())
    }

    @Test
    fun `only googlevideo names and only tcp are touched`() {
        val picked = StalledFlowPolicy().pick(
            now,
            listOf(
                flow("udp", network = "udp"),
                flow("other-site", domain = "sponsor.ajay.app"),
                flow("no-name", domain = ""),
                flow("lookalike", domain = "notgooglevideo.com"),
                flow("bare", domain = "googlevideo.com"),
                flow("upper-with-dot", domain = "RR1.SN-X.GoogleVideo.com."),
            ),
        )
        assertEquals(listOf("bare", "upper-with-dot"), picked.map { it.id }.sorted())
    }

    @Test
    fun `at most three flows per snapshot, the oldest first`() {
        val picked = StalledFlowPolicy().pick(
            now,
            (1..6).map { flow("f$it", created = 1_000L + it * 10) },
        )
        assertEquals(listOf("f1", "f2", "f3"), picked.map { it.id })
    }

    private fun trickling(id: String, up: Long, down: Long, domain: String = "rr2.sn-x.googlevideo.com") =
        FlowSnap(id, "tcp", domain, "74.125.162.225:443", "app.youtube", "hy2", 1_000, 0, up, down)

    @Test
    fun `a flow that keeps sending more than it receives is reset after two such windows`() {
        val policy = StalledFlowPolicy()
        // Данные диагностики 0.5.38: поток к 74.125.162.225 за 25 с отправил 203 КБ и получил 81 КБ.
        assertTrue("first sight is only remembered", policy.pick(20_000, listOf(trickling("t", 10_000, 30_000))).isEmpty())
        assertTrue("one bad window is not enough", policy.pick(25_000, listOf(trickling("t", 50_000, 50_000))).isEmpty())
        val picked = policy.pick(30_000, listOf(trickling("t", 90_000, 70_000)))
        assertEquals(listOf("t"), picked.map { it.id })
    }

    @Test
    fun `a healthy download an idle flow and a player pause are not treated as trickling`() {
        val policy = StalledFlowPolicy()
        val flows = { up: Long, down: Long ->
            listOf(trickling("fast", up, down), trickling("idle", 500, 20_000), trickling("tiny-up", up / 20, down / 20))
        }
        policy.pick(20_000, flows(10_000, 200_000))
        for (step in 1..4) {
            val picked = policy.pick(20_000L + step * 5_000, flows(10_000L + step * 40_000, 200_000L + step * 400_000))
            assertTrue("step $step: ${picked.map { it.id }}", picked.isEmpty())
        }
    }

    @Test
    fun `a recovering flow resets the trickle streak and a flow that already moved megabytes is left alone`() {
        val policy = StalledFlowPolicy()
        policy.pick(20_000, listOf(trickling("r", 10_000, 30_000), trickling("big", 10_000, 4_000_000)))
        policy.pick(25_000, listOf(trickling("r", 50_000, 50_000), trickling("big", 60_000, 4_010_000)))
        policy.pick(30_000, listOf(trickling("r", 60_000, 900_000), trickling("big", 110_000, 4_020_000))) // r recovered
        val picked = policy.pick(35_000, listOf(trickling("r", 100_000, 920_000), trickling("big", 160_000, 4_030_000)))
        assertTrue("recovered flow must not be reset: ${picked.map { it.id }}", picked.isEmpty())
    }

    @Test
    fun `trickle detection ignores other hosts and the session reset forgets the memory`() {
        val policy = StalledFlowPolicy()
        val other = { up: Long, down: Long -> listOf(trickling("o", up, down, domain = "sponsor.ajay.app")) }
        policy.pick(20_000, other(10_000, 30_000))
        policy.pick(25_000, other(50_000, 50_000))
        assertTrue(policy.pick(30_000, other(90_000, 70_000)).isEmpty())
        val googlevideo = StalledFlowPolicy()
        googlevideo.pick(20_000, listOf(trickling("t", 10_000, 30_000)))
        googlevideo.pick(25_000, listOf(trickling("t", 50_000, 50_000)))
        googlevideo.reset()
        assertTrue("memory cleared, first sight again", googlevideo.pick(30_000, listOf(trickling("t", 90_000, 70_000))).isEmpty())
    }

    @Test
    fun `a cache node that was slow before is cut after a single trickling window and sooner`() {
        val known = StalledFlowPolicy(knownSlow = { server, domain -> server == "hy2" && domain.contains("sn-x") })
        val young = { up: Long, down: Long -> listOf(FlowSnap("k", "tcp", "rr2.sn-x.googlevideo.com", "1.2.3.4:443", "app", "hy2", 1_000, 0, up, down)) }
        known.pick(8_000, young(10_000, 30_000))                       // первое появление: только запоминается
        val picked = known.pick(13_000, young(50_000, 50_000))         // возраст 12 с, окно одно
        assertEquals(listOf("k"), picked.map { it.id })
        // тот же поток для неизвестного узла после одного окна не сбрасывается
        val unknown = StalledFlowPolicy()
        unknown.pick(8_000, young(10_000, 30_000))
        assertTrue(unknown.pick(13_000, young(50_000, 50_000)).isEmpty())
        // и молодой поток (меньше 6 с) не трогается даже на известном плохом узле
        val fresh = StalledFlowPolicy(knownSlow = { _, _ -> true })
        fresh.pick(3_000, young(10_000, 30_000))
        assertTrue(fresh.pick(5_000, young(50_000, 50_000)).isEmpty())
    }

    @Test
    fun `the cap stops resets inside the window and reset starts over`() {
        val policy = StalledFlowPolicy(maxPerSnapshot = 10, maxPerWindow = 4)
        assertEquals(3, policy.pick(now, (1..3).map { flow("a$it") }).size)
        assertEquals("only one more is allowed", 1, policy.pick(now, (1..3).map { flow("b$it") }).size)
        assertTrue(policy.pick(now, listOf(flow("c"))).isEmpty())
        policy.reset()
        assertEquals(1, policy.pick(now, listOf(flow("d"))).size)
    }

    @Test
    fun `resets leave the window and room comes back (archive 0 5 43 hit the old per session cap after 40 minutes)`() {
        val policy = StalledFlowPolicy(maxPerSnapshot = 10, maxPerWindow = 4, capWindowMillis = 60_000)
        assertEquals(4, policy.pick(now, (1..4).map { flow("a$it") }).size)
        assertTrue("window still full", policy.pick(now + 59_999, listOf(flow("b"))).isEmpty())
        assertEquals("the first resets are older than the window", 1, policy.pick(now + 60_000, listOf(flow("c"))).size)
    }
}
