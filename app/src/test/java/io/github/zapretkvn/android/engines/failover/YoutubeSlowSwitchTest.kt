package io.github.zapretkvn.android.engines.failover

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeSlowSwitchTest {
    private val kb = 1024L
    private var now = 1_000_000L

    private fun policy() = YoutubeSwitchPolicy { now }

    private fun targets(vararg ids: String) = ids.map { FailoverTarget(it, valid = true) }

    private class FakeHost(
        private val snapshot: SlowSwitchSnapshot?,
        private val outcomes: MutableList<VideoOutcome>,
        private val trialOk: Boolean = true,
        private val commitOk: Boolean = true,
    ) : YoutubeSwitchHost {
        val calls = mutableListOf<String>()
        override suspend fun snapshot() = snapshot
        override suspend fun trialSwitch(currentId: String, candidateId: String): Boolean {
            calls += "trial:$currentId->$candidateId"
            return trialOk
        }
        override suspend fun dropVideoFlows() { calls += "drop" }
        override suspend fun verifyVideo(timeoutMillis: Long): VideoOutcome = outcomes.removeAt(0).also { calls += "verify:$it" }
        override suspend fun commit(candidateId: String): Boolean {
            calls += "commit:$candidateId"
            return commitOk
        }
        override suspend fun restore(id: String) { calls += "restore:$id" }
        override fun log(message: String) { calls += "log" }
        override fun announce(fromId: String, toId: String) { calls += "announce:$fromId->$toId" }
    }

    private fun snapshot(current: String, vararg others: String) =
        SlowSwitchSnapshot(current, targets(current, *others), emptyMap(), busy = false)

    private fun engine(host: FakeHost, policy: YoutubeSwitchPolicy = policy(), average: (String) -> Long? = { null }) =
        YoutubeSwitchEngine(policy, host, average)

    @Test
    fun `a healthy video on the candidate commits the switch`() = runTest {
        val host = FakeHost(snapshot("Hipge", "Senge"), mutableListOf(VideoOutcome.Healthy))
        val outcome = engine(host).runEpisode()
        assertEquals(YoutubeSwitchOutcome.Switched("Senge"), outcome)
        assertEquals(
            listOf("trial:Hipge->Senge", "drop", "verify:Healthy", "commit:Senge", "announce:Hipge->Senge"),
            host.calls,
        )
    }

    @Test
    fun `a starving candidate is marked and the next one is tried, the original server is not restored in between`() = runTest {
        val policy = policy()
        val host = FakeHost(snapshot("Hipge", "Senge", "Tinl"), mutableListOf(VideoOutcome.Starving, VideoOutcome.Healthy))
        val outcome = engine(host, policy, average = { id -> if (id == "Senge") 300 * kb else 100 * kb }).runEpisode()
        assertEquals(YoutubeSwitchOutcome.Switched("Tinl"), outcome)
        assertTrue(policy.isMarkedSlow("Senge"))
        assertTrue(policy.isMarkedSlow("Hipge"))
        assertEquals("trial:Hipge->Senge", host.calls.first { it.startsWith("trial") })
        assertTrue("second trial starts from the first candidate", "trial:Senge->Tinl" in host.calls)
        assertFalse(host.calls.any { it.startsWith("restore") })
    }

    @Test
    fun `when every candidate is slow the original server comes back`() = runTest {
        val host = FakeHost(snapshot("Hipge", "A", "B", "C"), mutableListOf(VideoOutcome.Starving, VideoOutcome.Starving))
        val outcome = engine(host).runEpisode()
        assertEquals(YoutubeSwitchOutcome.AllCandidatesSlow, outcome)
        assertEquals("only two candidates per episode", 2, host.calls.count { it.startsWith("trial") })
        assertEquals("restore:Hipge", host.calls.last())
    }

    @Test
    fun `silence means inconclusive and the original server is restored`() = runTest {
        val host = FakeHost(snapshot("Hipge", "Senge"), mutableListOf(VideoOutcome.Silent))
        assertEquals(YoutubeSwitchOutcome.Inconclusive, engine(host).runEpisode())
        assertEquals("restore:Hipge", host.calls.last())
        assertFalse(host.calls.any { it.startsWith("commit") })
    }

    @Test
    fun `a refused commit restores the original server and a failed trial too`() = runTest {
        val refused = FakeHost(snapshot("Hipge", "Senge"), mutableListOf(VideoOutcome.Healthy), commitOk = false)
        assertEquals(YoutubeSwitchOutcome.CommitRefused, engine(refused).runEpisode())
        assertEquals("restore:Hipge", refused.calls.last())
        val failed = FakeHost(snapshot("Hipge", "Senge"), mutableListOf(), trialOk = false)
        assertEquals(YoutubeSwitchOutcome.TrialFailed, engine(failed).runEpisode())
        assertEquals("restore:Hipge", failed.calls.last())
    }

    @Test
    fun `no usable candidate and a missing session end the episode quietly`() = runTest {
        assertEquals(YoutubeSwitchOutcome.Gone, engine(FakeHost(null, mutableListOf())).runEpisode())
        val host = FakeHost(snapshot("Hipge"), mutableListOf())
        assertEquals(YoutubeSwitchOutcome.NoCandidates, engine(host).runEpisode())
        assertFalse(host.calls.any { it.startsWith("trial") })
    }

    @Test
    fun `candidates skip bad history, slow marks, failed pings, maintenance and invalid targets and prefer the best known`() {
        val policy = policy()
        policy.markSlow("Marked")
        val all = listOf(
            FailoverTarget("Hipge", valid = true),
            FailoverTarget("Marked", valid = true),
            FailoverTarget("BadHistory", valid = true),
            FailoverTarget("PingFailed", valid = true),
            FailoverTarget("Maintenance", valid = true, maintenance = true),
            FailoverTarget("Invalid", valid = false),
            FailoverTarget("Unknown", valid = true),
            FailoverTarget("Good", valid = true),
            FailoverTarget("Better", valid = true),
        )
        val hints = mapOf("PingFailed" to LatencyHint(null, failed = true), "Unknown" to LatencyHint(40, failed = false))
        val average = mapOf("BadHistory" to 10 * kb, "Good" to 300 * kb, "Better" to 700 * kb)
        val picked = policy.candidates("Hipge", all, hints) { average[it] }
        assertEquals("only two candidates per episode, best known first", listOf("Better", "Good"), picked.map { it.id })
        val withoutKnown = policy.candidates("Hipge", all, hints) { null }
        // без истории: известный по пингу первым, затем в порядке профиля; плохие и помеченные не берутся никогда
        assertEquals("Unknown", withoutKnown.first().id)
        assertTrue(withoutKnown.map { it.id }.none { it in setOf("Marked", "PingFailed", "Maintenance", "Invalid", "Hipge") })
    }

    @Test
    fun `the gate enforces cooldown, the window limit and the switch setting`() {
        val policy = policy()
        assertEquals(YoutubeSwitchGate.Disabled, policy.gate(enabled = false, busy = false))
        assertEquals(YoutubeSwitchGate.Busy, policy.gate(enabled = true, busy = true))
        assertEquals(YoutubeSwitchGate.Ready, policy.gate(enabled = true, busy = false))
        policy.onEpisodeEnd()
        assertEquals(YoutubeSwitchGate.Cooldown, policy.gate(enabled = true, busy = false))
        now += YoutubeSwitchDefaults.COOLDOWN_MILLIS
        assertEquals(YoutubeSwitchGate.Ready, policy.gate(enabled = true, busy = false))
        repeat(YoutubeSwitchDefaults.MAX_SWITCHES_PER_WINDOW) { policy.onSwitch() }
        assertEquals(YoutubeSwitchGate.Limit, policy.gate(enabled = true, busy = false))
        now += YoutubeSwitchDefaults.WINDOW_MILLIS
        assertEquals(YoutubeSwitchGate.Ready, policy.gate(enabled = true, busy = false))
    }

    @Test
    fun `slow marks expire and belong to one profile`() {
        val policy = policy()
        policy.bindProfile("p1")
        policy.markSlow("Hipge")
        assertTrue(policy.isMarkedSlow("Hipge"))
        now += YoutubeSwitchDefaults.SLOW_MARK_MILLIS
        assertFalse(policy.isMarkedSlow("Hipge"))
        policy.markSlow("Hipge")
        policy.bindProfile("p2")
        assertFalse("another profile has other tags", policy.isMarkedSlow("Hipge"))
    }
}
