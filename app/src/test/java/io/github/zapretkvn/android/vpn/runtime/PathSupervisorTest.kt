package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.engines.failover.FailoverTarget
import io.github.zapretkvn.android.engines.failover.LatencyHint
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSupervisorTest {
    private var now = 1_000L
    private val supervisor = PathSupervisor { now }

    private fun plan(current: String, type: String = "vless", vararg ids: String = arrayOf("a", "b", "c")) =
        FailoverPlan("proxy", current, type, ids.map { FailoverTarget(it, valid = true) })

    private fun timeout(tag: String, type: String = "vless") =
        PathHint(tag, type, HysteriaFailureCode.TARGET_NETWORK_TIMEOUT)

    @Test
    fun `single log line only probes, never switches`() {
        val decision = supervisor.onHint(timeout("a")) { plan("a") }
        assertTrue(decision is PathDecision.Probe)
        assertTrue(supervisor.busy)
    }

    @Test
    fun `alive probe is a false alarm and mutes the server`() {
        val probe = supervisor.onHint(timeout("a")) { plan("a") } as PathDecision.Probe
        val result = supervisor.onProbeResult(probe.episode, alive = true, plan("a"), emptyMap())
        assertTrue(result is PathDecision.Ignore)
        assertFalse(supervisor.busy)
        // Повтор той же строки через 10 с не запускает новую пробу.
        now += 10_000
        assertTrue(supervisor.onHint(timeout("a")) { plan("a") } is PathDecision.Ignore)
        now += PathSupervisor.FALSE_ALARM_MUTE_MILLIS
        assertTrue(supervisor.onHint(timeout("a")) { plan("a") } is PathDecision.Probe)
    }

    @Test
    fun `dead probe switches to best latency candidate`() {
        val probe = supervisor.onHint(timeout("a")) { plan("a") } as PathDecision.Probe
        val hints = mapOf("b" to LatencyHint(300, false), "c" to LatencyHint(80, false))
        val decision = supervisor.onProbeResult(probe.episode, alive = false, plan("a"), hints)
        assertEquals(PathDecision.Switch(probe.episode, "proxy", "a", "c"), decision)
    }

    @Test
    fun `hints about a non-selected server are ignored`() {
        assertTrue(supervisor.onHint(timeout("b")) { plan("a") } is PathDecision.Ignore)
        assertTrue(supervisor.onHint(timeout("a")) { null } is PathDecision.Ignore)
    }

    @Test
    fun `hints are coalesced while an episode runs`() {
        supervisor.onHint(timeout("a")) { plan("a") }
        assertTrue(supervisor.onHint(timeout("a")) { plan("a") } is PathDecision.Ignore)
    }

    @Test
    fun `failed replacement means network-wide failure and stops ping-pong`() {
        val probe = supervisor.onHint(timeout("a")) { plan("a") } as PathDecision.Probe
        val switch = supervisor.onProbeResult(probe.episode, false, plan("a"), emptyMap()) as PathDecision.Switch
        supervisor.onSwitchResult(switch.episode, committed = false)
        assertFalse(supervisor.busy)
        now += 30_000
        assertTrue(supervisor.onHint(timeout("a")) { plan("a") } is PathDecision.Ignore)
        now += PathSupervisor.NETWORK_WIDE_HOLD_MILLIS
        assertTrue(supervisor.onHint(timeout("a")) { plan("a") } is PathDecision.Probe)
    }

    @Test
    fun `stale lines right after a commit are fenced`() {
        val probe = supervisor.onHint(timeout("a")) { plan("a") } as PathDecision.Probe
        val switch = supervisor.onProbeResult(probe.episode, false, plan("a"), emptyMap()) as PathDecision.Switch
        supervisor.onSwitchResult(switch.episode, committed = true)
        assertTrue(supervisor.onHint(timeout("b")) { plan("b") } is PathDecision.Ignore)
        now += PathSupervisor.COMMIT_FENCE_MILLIS
        assertTrue(supervisor.onHint(timeout("b")) { plan("b") } is PathDecision.Probe)
    }

    @Test
    fun `dead server is not offered as candidate during cooldown`() {
        val first = supervisor.onHint(timeout("a")) { plan("a") } as PathDecision.Probe
        val switch = supervisor.onProbeResult(first.episode, false, plan("a"), emptyMap()) as PathDecision.Switch
        assertEquals("b", switch.toId)
        supervisor.onSwitchResult(switch.episode, committed = true)
        now += PathSupervisor.COMMIT_FENCE_MILLIS
        val second = supervisor.onHint(timeout("b")) { plan("b") } as PathDecision.Probe
        val next = supervisor.onProbeResult(second.episode, false, plan("b"), emptyMap()) as PathDecision.Switch
        assertEquals("c", next.toId)
    }

    @Test
    fun `switch rate is limited per window`() {
        val ids = arrayOf("a", "b", "c", "d", "e")
        var current = "a"
        repeat(PathSupervisor.MAX_SWITCHES_PER_WINDOW) {
            val probe = supervisor.onHint(timeout(current)) { plan(current, "vless", *ids) } as PathDecision.Probe
            val switch = supervisor.onProbeResult(probe.episode, false, plan(current, "vless", *ids), emptyMap())
                as PathDecision.Switch
            supervisor.onSwitchResult(switch.episode, committed = true)
            current = switch.toId
            now += PathSupervisor.COMMIT_FENCE_MILLIS
        }
        val probe = supervisor.onHint(timeout(current)) { plan(current, "vless", *ids) } as PathDecision.Probe
        assertTrue(
            supervisor.onProbeResult(probe.episode, false, plan(current, "vless", *ids), emptyMap())
                is PathDecision.Ignore,
        )
    }

    @Test
    fun `hysteria without candidate is terminal, other protocols keep the tunnel`() {
        val hy = supervisor.onHint(timeout("a", "hysteria2")) { plan("a", "hysteria2", "a") } as PathDecision.Probe
        val terminal = supervisor.onProbeResult(hy.episode, false, plan("a", "hysteria2", "a"), emptyMap())
        assertEquals(HysteriaFailureCode.NO_COMPATIBLE_FALLBACK, (terminal as PathDecision.Terminate).code)

        val other = PathSupervisor { now }
        val probe = other.onHint(timeout("a")) { plan("a", "vless", "a") } as PathDecision.Probe
        assertTrue(other.onProbeResult(probe.episode, false, plan("a", "vless", "a"), emptyMap()) is PathDecision.Ignore)
    }

    @Test
    fun `security failure terminates hysteria only`() {
        val tls = PathHint("a", "hysteria2", HysteriaFailureCode.TARGET_TLS_REJECTED)
        assertTrue(supervisor.onHint(tls) { plan("a", "hysteria2") } is PathDecision.Terminate)
        val vless = PathHint("a", "vless", HysteriaFailureCode.TARGET_TLS_REJECTED)
        assertTrue(supervisor.onHint(vless) { plan("a") } is PathDecision.Ignore)
    }

    @Test
    fun `stale probe results are ignored after abandon`() {
        val probe = supervisor.onHint(timeout("a")) { plan("a") } as PathDecision.Probe
        supervisor.abandon()
        assertTrue(supervisor.onProbeResult(probe.episode, false, plan("a"), emptyMap()) is PathDecision.Ignore)
    }

    @Test
    fun `server changed during probe cancels the episode`() {
        val probe = supervisor.onHint(timeout("a")) { plan("a") } as PathDecision.Probe
        assertTrue(supervisor.onProbeResult(probe.episode, false, plan("b"), emptyMap()) is PathDecision.Ignore)
        assertFalse(supervisor.busy)
    }

    @Test
    fun `record-only and unknown codes never probe`() {
        val closed = PathHint("a", "vless", HysteriaFailureCode.TARGET_CONNECTION_CLOSED)
        assertTrue(supervisor.onHint(closed) { plan("a") } is PathDecision.Ignore)
    }
}
