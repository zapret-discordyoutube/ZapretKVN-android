package io.github.zapretkvn.android.engines.failover

import io.github.zapretkvn.android.engines.failover.SlowServerSwitchDefaults.FALSE_ALARM_COOLDOWN_MILLIS
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchDefaults.HOUR_MILLIS
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchDefaults.MANUAL_HOLD_MILLIS
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchDefaults.POST_SWITCH_HOLD_MILLIS
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchDefaults.SLOW_MARK_MILLIS
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchDefaults.THRESHOLD_BYTES_PER_SECOND
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowServerSwitchTest {
    private var now = 1_000_000L
    private val policy = SlowServerSwitchPolicy({ now })

    private val slow = 40L * 1024
    private val fast = 600L * 1024

    private class FakeHost(
        var current: String = "a",
        val targets: List<FailoverTarget> = listOf("a", "b", "c", "d").map { FailoverTarget(it, true) },
        val hints: Map<String, LatencyHint> = emptyMap(),
        var busy: Boolean = false,
        val measurements: ArrayDeque<Long?> = ArrayDeque(),
        var commitAccepted: Boolean = true,
    ) : SlowSwitchHost {
        val trials = mutableListOf<String>()
        val commits = mutableListOf<String>()
        var rollbacks = 0
        var measureCalls = 0
        val announcements = mutableListOf<Pair<String, String>>()
        val logs = mutableListOf<String>()
        private var previous: String? = null

        override suspend fun snapshot() = SlowSwitchSnapshot(current, targets, hints, busy)
        override suspend fun measureThroughput(): Long? {
            measureCalls++
            return measurements.removeFirstOrNull()
        }
        override suspend fun trialSwitch(currentId: String, candidateId: String): Boolean {
            trials += candidateId
            previous = current
            current = candidateId
            return true
        }
        override suspend fun commit(candidateId: String): Boolean {
            if (!commitAccepted) return false
            commits += candidateId
            previous = null
            return true
        }
        override suspend fun rollback() {
            previous?.let { current = it }
            previous = null
            rollbacks++
        }
        override fun log(message: String) {
            logs += message
        }
        override fun announceSwitch(fromId: String, toId: String, fromBytesPerSecond: Long, toBytesPerSecond: Long) {
            announcements += fromId to toId
        }
    }

    private fun engine(host: FakeHost, enabled: Boolean = true) =
        SlowServerSwitchEngine(policy, host, enabled = { enabled })

    @Test
    fun `suspicion requires demand and a full continuous window`() {
        val detector = SlowThroughputDetector()
        var t = 0L
        var total = 0L
        fun sample(bytesPerSecond: Long, connections: Int): Boolean {
            t += 5_000
            total += bytesPerSecond * 5
            return detector.onSample(t, total, connections)
        }
        detector.onSample(t, total, 0)
        // Idle tunnel: no connections, or only keepalive chatter — never suspicious.
        repeat(10) { assertFalse(sample(0, 0)) }
        repeat(10) { assertFalse(sample(2 * 1024, 3)) }
        // Traffic flowing but no active connection reported: still not demand.
        repeat(10) { assertFalse(sample(slow, 0)) }
        // Fast transfer is healthy.
        repeat(10) { assertFalse(sample(fast, 4)) }
        // Real demand below threshold: suspicion only after 20 s of continuity.
        assertFalse(sample(slow, 2)) // 5 s
        assertFalse(sample(slow, 2)) // 10 s
        assertFalse(sample(slow, 2)) // 15 s
        assertTrue(sample(slow, 2)) // 20 s
        // One healthy sample breaks the window.
        assertFalse(sample(fast, 2))
        assertFalse(sample(slow, 2))
        assertFalse(sample(slow, 2))
        assertFalse(sample(slow, 2))
        assertTrue(sample(slow, 2))
    }

    @Test
    fun `sleep gap or counter reset restarts the window`() {
        val detector = SlowThroughputDetector()
        detector.onSample(0, 0, 1)
        assertFalse(detector.onSample(5_000, slow * 5, 1))
        assertFalse(detector.onSample(10_000, slow * 10, 1))
        // Device slept for a minute: continuity is unknown.
        assertFalse(detector.onSample(70_000, slow * 70, 1))
        assertFalse(detector.onSample(75_000, slow * 75, 1))
        assertFalse(detector.onSample(80_000, slow * 80, 1))
        assertFalse(detector.onSample(85_000, slow * 85, 1))
        assertTrue(detector.onSample(90_000, slow * 90, 1))
        // New core counters (restart) are not a negative rate.
        assertFalse(detector.onSample(95_000, 0, 1))
    }

    @Test
    fun `false alarm puts a five minute cooldown and never switches`() = runBlocking {
        val host = FakeHost(measurements = ArrayDeque(listOf(fast)))
        assertEquals(SlowSwitchOutcome.FalseAlarm, engine(host).runEpisode())
        assertTrue(host.trials.isEmpty())
        assertEquals(SlowSwitchGate.Cooldown, policy.gate(enabled = true, busy = false))
        now += FALSE_ALARM_COOLDOWN_MILLIS - 1
        assertEquals(SlowSwitchOutcome.Skipped(SlowSwitchGate.Cooldown), engine(host).runEpisode())
        assertEquals(1, host.measureCalls)
        now += 1
        assertEquals(SlowSwitchGate.Ready, policy.gate(enabled = true, busy = false))
    }

    @Test
    fun `failed measurement is inconclusive, not slow`() = runBlocking {
        val host = FakeHost(measurements = ArrayDeque(listOf(null)))
        assertEquals(SlowSwitchOutcome.Inconclusive, engine(host).runEpisode())
        assertTrue(host.trials.isEmpty())
        assertEquals(SlowSwitchGate.Cooldown, policy.gate(enabled = true, busy = false))
    }

    @Test
    fun `confirmed slow and better candidate gives exactly one switch`() = runBlocking {
        val host = FakeHost(
            hints = mapOf(
                "b" to LatencyHint(300, failed = false),
                "c" to LatencyHint(40, failed = false),
                "d" to LatencyHint(10, failed = true),
            ),
            measurements = ArrayDeque(listOf(slow, fast)),
        )
        val outcome = engine(host).runEpisode()
        // Best persisted ping wins; a failed last ping is skipped.
        assertEquals(SlowSwitchOutcome.Switched("a", "c"), outcome)
        assertEquals(listOf("c"), host.trials)
        assertEquals(listOf("c"), host.commits)
        assertEquals(0, host.rollbacks)
        assertEquals(listOf("a" to "c"), host.announcements)
        assertEquals("c", host.current)
        assertTrue("abandoned server is marked slow", policy.isMarkedSlow("a"))
        assertEquals(SlowSwitchGate.PostSwitchHold, policy.gate(enabled = true, busy = false))
    }

    @Test
    fun `candidate below two times current or below threshold is rolled back`() = runBlocking {
        // 2x faster but still below the absolute threshold.
        val belowThreshold = FakeHost(measurements = ArrayDeque(listOf(30L * 1024, 70L * 1024)))
        assertEquals(SlowSwitchOutcome.NoBetter, engine(belowThreshold).runEpisode())
        assertTrue(belowThreshold.commits.isEmpty())
        assertEquals(1, belowThreshold.rollbacks)
        assertEquals("a", belowThreshold.current)
        assertTrue(policy.isMarkedSlow("b"))

        now += POST_SWITCH_HOLD_MILLIS
        // Above threshold but less than 2x the current server.
        val current = THRESHOLD_BYTES_PER_SECOND - 1
        val notTwice = FakeHost(measurements = ArrayDeque(listOf(current, current * 2 - 1)))
        assertEquals(SlowSwitchOutcome.NoBetter, engine(notTwice).runEpisode())
        assertTrue(notTwice.commits.isEmpty())
        assertEquals("c", notTwice.trials.single())
        assertEquals("a", notTwice.current)
    }

    @Test
    fun `no candidate skips the measurement entirely`() = runBlocking {
        val host = FakeHost(
            targets = listOf(
                FailoverTarget("a", true),
                FailoverTarget("m", true, maintenance = true),
                FailoverTarget("x", false),
            ),
            measurements = ArrayDeque(listOf(slow)),
        )
        assertEquals(SlowSwitchOutcome.NoCandidate, engine(host).runEpisode())
        assertEquals(0, host.measureCalls)
        assertTrue(host.trials.isEmpty())
    }

    @Test
    fun `disabled setting or busy lifecycle never measures or switches`() = runBlocking {
        val disabled = FakeHost(measurements = ArrayDeque(listOf(slow, fast)))
        assertEquals(
            SlowSwitchOutcome.Skipped(SlowSwitchGate.Disabled),
            engine(disabled, enabled = false).runEpisode(),
        )
        assertEquals(0, disabled.measureCalls)
        assertTrue(disabled.trials.isEmpty())

        val busy = FakeHost(busy = true, measurements = ArrayDeque(listOf(slow, fast)))
        assertEquals(SlowSwitchOutcome.Skipped(SlowSwitchGate.Busy), engine(busy).runEpisode())
        assertEquals(0, busy.measureCalls)
    }

    @Test
    fun `anti-flap limits hold after a switch and cap three switches per hour`() = runBlocking {
        repeat(3) { index ->
            val host = FakeHost(measurements = ArrayDeque(listOf(slow, slow)))
            assertEquals("episode $index", SlowSwitchOutcome.NoBetter, engine(host).runEpisode())
            assertEquals(SlowSwitchGate.PostSwitchHold, policy.gate(enabled = true, busy = false))
            now += POST_SWITCH_HOLD_MILLIS
        }
        // Three trial switches within the hour: the fourth is refused.
        assertEquals(SlowSwitchGate.HourlyLimit, policy.gate(enabled = true, busy = false))
        val host = FakeHost(measurements = ArrayDeque(listOf(slow, fast)))
        assertEquals(SlowSwitchOutcome.Skipped(SlowSwitchGate.HourlyLimit), engine(host).runEpisode())
        assertEquals(0, host.measureCalls)
        now += HOUR_MILLIS - 3 * POST_SWITCH_HOLD_MILLIS
        assertEquals(SlowSwitchGate.Ready, policy.gate(enabled = true, busy = false))
    }

    @Test
    fun `slow marks expire after thirty minutes and are scoped to the profile`() {
        val targets = listOf("a", "b", "c").map { FailoverTarget(it, true) }
        policy.bindProfile("p1")
        policy.markSlow("b")
        assertEquals(listOf("c"), policy.candidates("a", targets, emptyMap()).map { it.id })
        now += SLOW_MARK_MILLIS
        assertEquals(listOf("b", "c"), policy.candidates("a", targets, emptyMap()).map { it.id })
        policy.markSlow("b")
        policy.bindProfile("p2")
        assertEquals(listOf("b", "c"), policy.candidates("a", targets, emptyMap()).map { it.id })
    }

    @Test
    fun `candidates keep profile order for unknown pings and are limited to three`() {
        val targets = listOf("a", "b", "c", "d", "e").map { FailoverTarget(it, true) }
        val picked = policy.candidates("a", targets, mapOf("e" to LatencyHint(20, failed = false)))
        assertEquals(listOf("e", "b", "c"), picked.map { it.id })
    }

    @Test
    fun `commit taken over by the user is not rolled back`() = runBlocking {
        val host = FakeHost(measurements = ArrayDeque(listOf(slow, fast)), commitAccepted = false)
        assertEquals(SlowSwitchOutcome.Superseded, engine(host).runEpisode())
        assertEquals(0, host.rollbacks)
        assertTrue(host.announcements.isEmpty())
    }

    @Test
    fun `server changed during the current measurement aborts before any switch`() = runBlocking {
        val host = object : SlowSwitchHost by FakeHost() {
            var calls = 0
            var trials = 0
            override suspend fun snapshot(): SlowSwitchSnapshot {
                calls++
                val current = if (calls == 1) "a" else "b"
                return SlowSwitchSnapshot(
                    current,
                    listOf("a", "b", "c").map { FailoverTarget(it, true) },
                    emptyMap(),
                    busy = false,
                )
            }
            override suspend fun measureThroughput(): Long = 10L * 1024
            override suspend fun trialSwitch(currentId: String, candidateId: String): Boolean {
                trials++
                return true
            }
        }
        assertEquals(SlowSwitchOutcome.Superseded, SlowServerSwitchEngine(policy, host, enabled = { true }).runEpisode())
        assertEquals(0, host.trials)
    }

    @Test
    fun `deadline rolls back an unfinished trial and counts as inconclusive`() = runBlocking {
        val hanging = CompletableDeferred<Long?>()
        val fake = FakeHost()
        val host = object : SlowSwitchHost by fake {
            var calls = 0
            override suspend fun measureThroughput(): Long? {
                calls++
                return if (calls == 1) slow else hanging.await()
            }
        }
        val outcome = SlowServerSwitchEngine(policy, host, { true }, deadlineMillis = 200).runEpisode()
        assertEquals(SlowSwitchOutcome.Inconclusive, outcome)
        assertEquals(listOf("b"), fake.trials)
        assertEquals(1, fake.rollbacks)
        assertEquals("a", fake.current)
        assertTrue(fake.commits.isEmpty())
    }

    @Test
    fun `a download without a single byte is inconclusive`() {
        assertEquals(null, io.github.zapretkvn.android.network.probes.ThroughputRate.bytesPerSecondOrNull(0, 6_000))
        assertEquals(1_000L, io.github.zapretkvn.android.network.probes.ThroughputRate.bytesPerSecondOrNull(6_000, 6_000))
    }

    @Test
    fun `manual selection holds smart switching for exactly thirty minutes`() = runBlocking {
        policy.onManualSelection()
        assertEquals(SlowSwitchGate.ManualHold, policy.gate(enabled = true, busy = false))
        val held = FakeHost(measurements = ArrayDeque(listOf(slow, fast)))
        assertEquals(SlowSwitchOutcome.Skipped(SlowSwitchGate.ManualHold), engine(held).runEpisode())
        assertEquals(0, held.measureCalls)
        now += MANUAL_HOLD_MILLIS - 1
        assertEquals(SlowSwitchGate.ManualHold, policy.gate(enabled = true, busy = false))
        now += 1
        assertEquals(SlowSwitchGate.Ready, policy.gate(enabled = true, busy = false))
        val host = FakeHost(measurements = ArrayDeque(listOf(slow, fast)))
        assertEquals(SlowSwitchOutcome.Switched("a", "b"), engine(host).runEpisode())
    }

    @Test
    fun `automatic switches never arm the manual hold`() = runBlocking {
        val host = FakeHost(measurements = ArrayDeque(listOf(slow, fast)))
        assertEquals(SlowSwitchOutcome.Switched("a", "b"), engine(host).runEpisode())
        // Only the 10-minute post-switch hold applies, never the 30-minute manual one.
        now += POST_SWITCH_HOLD_MILLIS
        assertEquals(SlowSwitchGate.Ready, policy.gate(enabled = true, busy = false))
        // A failover commit goes through its own coordinator and leaves the gate alone.
        val failover = OutboundFailoverCoordinator({ now })
        failover.chooseReplacement("b", recoverable = true, orderedTargets = listOf(FailoverTarget("b", true), FailoverTarget("c", true)))
        failover.commitReplacement()
        assertEquals(SlowSwitchGate.Ready, policy.gate(enabled = true, busy = false))
    }

    @Test
    fun `failover still works during the manual hold`() {
        policy.onManualSelection()
        val failover = OutboundFailoverCoordinator({ now })
        val outcome = failover.chooseReplacement(
            "a",
            recoverable = true,
            orderedTargets = listOf(FailoverTarget("a", true), FailoverTarget("b", true)),
        )
        assertEquals(FailoverOutcome.Candidate(FailoverTarget("b", true)), outcome)
        assertEquals(SlowSwitchGate.ManualHold, policy.gate(enabled = true, busy = false))
    }

    @Test
    fun `toggling the setting resets the manual hold`() {
        policy.onManualSelection()
        assertEquals(SlowSwitchGate.Disabled, policy.gate(enabled = false, busy = false))
        policy.resetManualHold()
        assertEquals(SlowSwitchGate.Ready, policy.gate(enabled = true, busy = false))
    }
}
