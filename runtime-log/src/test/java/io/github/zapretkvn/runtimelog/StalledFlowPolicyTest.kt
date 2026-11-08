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

    @Test
    fun `the session cap stops resets and reset starts a new session`() {
        val policy = StalledFlowPolicy(maxPerSnapshot = 10, maxPerSession = 4)
        assertEquals(3, policy.pick(now, (1..3).map { flow("a$it") }).size)
        assertEquals("only one more is allowed", 1, policy.pick(now, (1..3).map { flow("b$it") }).size)
        assertTrue(policy.pick(now, listOf(flow("c"))).isEmpty())
        policy.reset()
        assertEquals(1, policy.pick(now, listOf(flow("d"))).size)
    }
}
