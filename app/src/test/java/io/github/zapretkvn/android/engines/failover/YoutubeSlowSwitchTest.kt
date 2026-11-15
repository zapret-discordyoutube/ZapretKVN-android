package io.github.zapretkvn.android.engines.failover

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        private val hangOnVerify: Boolean = false,
    ) : YoutubeSwitchHost {
        val calls = mutableListOf<String>()
        override suspend fun snapshot() = snapshot
        override suspend fun trialSwitch(currentId: String, candidateId: String): Boolean {
            calls += "trial:$currentId->$candidateId"
            return trialOk
        }
        override suspend fun dropVideoFlows() { calls += "drop" }
        override suspend fun verifyVideo(timeoutMillis: Long): VideoOutcome {
            if (hangOnVerify) {
                calls += "verify:hang"
                awaitCancellation()
            }
            return outcomes.removeAt(0).also { calls += "verify:$it" }
        }
        override suspend fun commit(candidateId: String): Boolean {
            calls += "commit:$candidateId"
            return commitOk
        }
        override suspend fun restore(id: String) { calls += "restore:$id" }
        override fun log(message: String) { calls += "log" }
        override fun notTheServer(original: String, starved: List<String>) {
            calls += "notTheServer:$original<-${starved.joinToString(",")}"
        }
        var moveOk = true
        override suspend fun moveToProven(original: String, serverId: String): Boolean {
            calls += "proven:$original->$serverId"
            return moveOk
        }
        override fun announce(fromId: String, toId: String, restartVideo: Boolean) {
            calls += "announce:$fromId->$toId" + if (restartVideo) ":restart" else ""
        }
    }

    private fun snapshot(current: String, vararg others: String) =
        SlowSwitchSnapshot(current, targets(current, *others), emptyMap(), busy = false)

    private fun engine(host: FakeHost, policy: YoutubeSwitchPolicy = policy(), average: (String) -> Long? = { null }) =
        YoutubeSwitchEngine(policy, host, average)

    /** Архив 0.5.48 (2026-11-14 10:43-10:44): Clermany не ответил на пробу, через 40 с движок пробовал его снова. */
    @Test
    fun `a server the failover just confirmed dead is not tried as a candidate`() = runTest {
        val host = FakeHost(snapshot("Titiru", "Clermany", "Horologium"), mutableListOf(VideoOutcome.Healthy))
        val outcome = YoutubeSwitchEngine(policy(), host, { null }, excludedIds = { setOf("Clermany") }).runEpisode()
        assertEquals(YoutubeSwitchOutcome.Switched("Horologium"), outcome)
        assertTrue(host.calls.none { it.contains("Clermany") })
        val onlyDead = FakeHost(snapshot("Titiru", "Clermany"), mutableListOf())
        assertEquals(
            YoutubeSwitchOutcome.NoCandidates,
            YoutubeSwitchEngine(policy(), onlyDead, { null }, excludedIds = { setOf("Clermany") }).runEpisode(),
        )
    }

    /** Ревью 2026-11-14: ручной выбор сервера или проба по подсказке ядра отменяют эпизод посреди проверки. */
    @Test
    fun `a cancelled episode puts the original server back and ends the episode`() = runTest {
        val host = FakeHost(snapshot("Hipge", "Senge"), mutableListOf(), hangOnVerify = true)
        val policy = policy()
        val job = launch { YoutubeSwitchEngine(policy, host, { null }).runEpisode() }
        runCurrent()
        assertEquals(listOf("trial:Hipge->Senge", "drop", "verify:hang"), host.calls)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        // The restore ran although the coroutine was already cancelled.
        assertTrue(host.calls.toString(), "restore:Hipge" in host.calls)
        assertFalse(host.calls.toString(), host.calls.any { it.startsWith("commit") })
    }

    /** Nothing was moved, so nothing is restored. */
    @Test
    fun `an episode without candidates restores nothing`() = runTest {
        val host = FakeHost(snapshot("Hipge"), mutableListOf())
        val outcome = engine(host).runEpisode()
        assertEquals(YoutubeSwitchOutcome.NoCandidates, outcome)
        assertTrue(host.calls.none { it.startsWith("restore") })
    }

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
    fun `when one answering candidate is slow too and the other is dead, the original server comes back`() = runTest {
        val policy = policy()
        val host = FakeHost(snapshot("Hipge", "A", "B"), mutableListOf(VideoOutcome.Starving, VideoOutcome.Dead))
        val outcome = engine(host, policy).runEpisode()
        assertEquals(YoutubeSwitchOutcome.AllCandidatesSlow, outcome)
        assertEquals("restore:Hipge", host.calls.last())
        assertTrue("two servers are not enough to blame the client", host.calls.none { it.startsWith("notTheServer") })
        assertTrue(policy.isMarkedSlow("Hipge"))
        assertTrue(policy.isMarkedSlow("A"))
        assertTrue("a dead candidate stays marked", policy.isMarkedSlow("B"))
        now += YoutubeSwitchDefaults.COOLDOWN_MILLIS
        assertEquals(YoutubeSwitchGate.Ready, policy.gate(enabled = true, busy = false))
    }

    /**
     * Field 2026-11-15 18:44-18:50 UTC (0.6.19): the video starved on Titiru, then on Hosfinano, Plafi and Platifin, and
     * before that on Finohost, 4vusa and Rusina (which had played 60 MB an hour earlier); six minutes later the same
     * client played on Platifin. Not the servers: their marks go, and the servers are left alone for three minutes.
     */
    @Test
    fun `when the video starves on every answering server it is not the server, marks go and switching pauses`() = runTest {
        val policy = policy()
        val host = FakeHost(
            snapshot("Titiru", "Hosfinano", "Plafi", "Platifin", "D"),
            mutableListOf(VideoOutcome.Starving, VideoOutcome.Starving, VideoOutcome.Starving),
        )
        val outcome = engine(host, policy).runEpisode()
        assertEquals(YoutubeSwitchOutcome.StarvesEverywhere(4), outcome)
        assertEquals("only three candidates per episode", 3, host.calls.count { it.startsWith("trial") })
        assertEquals(
            "not to blame, so the original (a pin too) comes back",
            listOf("notTheServer:Titiru<-Hosfinano,Plafi,Platifin", "restore:Titiru"),
            host.calls.takeLast(2),
        )
        listOf("Titiru", "Hosfinano", "Plafi", "Platifin").forEach { assertFalse(it, policy.isMarkedSlow(it)) }
        now += YoutubeSwitchDefaults.COOLDOWN_MILLIS
        assertEquals(YoutubeSwitchGate.Cooldown, policy.gate(enabled = true, busy = false))
        now += YoutubeSwitchDefaults.CLIENT_STALL_COOLDOWN_MILLIS - YoutubeSwitchDefaults.COOLDOWN_MILLIS
        assertEquals(YoutubeSwitchGate.Ready, policy.gate(enabled = true, busy = false))
    }

    /**
     * Field 2026-11-15 20:12-20:17 UTC (0.6.21): the YouTube app played through Rusina and got nothing through the pinned
     * Finohost four minutes later. Owner decision: when the video starves everywhere, the slot goes to the server that
     * recently proved fast for YouTube, even over a pin, and the user is asked to reopen the video.
     */
    @Test
    fun `when the video starves everywhere the slot goes to the proven server with the best history`() = runTest {
        val host = FakeHost(
            snapshot("Finohost", "4vusa", "4vircosx", "Hosfinano", "Rusina", "Titiru"),
            mutableListOf(VideoOutcome.Starving, VideoOutcome.Starving, VideoOutcome.Starving),
        )
        val average = mapOf("Rusina" to 900 * kb, "Titiru" to 400 * kb, "Finohost" to 2_000 * kb)
        val outcome = YoutubeSwitchEngine(
            policy(), host, { average[it] }, proven = { it in setOf("Rusina", "Titiru", "Finohost") },
        ).runEpisode()
        assertEquals(YoutubeSwitchOutcome.StarvesEverywhere(4, movedTo = "Rusina"), outcome)
        assertEquals("proven:Finohost->Rusina", host.calls.last())
        assertTrue("the original is never the refuge, even when proven", host.calls.none { it == "restore:Finohost" })
        assertNull(YoutubeWedgeNotice.decide(outcome, null, now))
    }

    @Test
    fun `without a proven server, or when the move fails, the original comes back`() = runTest {
        val none = FakeHost(snapshot("A", "B", "C", "D"), mutableListOf(VideoOutcome.Starving, VideoOutcome.Starving, VideoOutcome.Starving))
        assertEquals(YoutubeSwitchOutcome.StarvesEverywhere(4), YoutubeSwitchEngine(policy(), none, { null }).runEpisode())
        assertEquals("restore:A", none.calls.last())
        val failing = FakeHost(snapshot("A", "B", "C", "D", "E"), mutableListOf(VideoOutcome.Starving, VideoOutcome.Starving, VideoOutcome.Starving))
        failing.moveOk = false
        val outcome = YoutubeSwitchEngine(policy(), failing, { null }, proven = { it == "E" }).runEpisode()
        assertEquals(YoutubeSwitchOutcome.StarvesEverywhere(4), outcome)
        assertEquals(listOf("proven:A->E", "restore:A"), failing.calls.takeLast(2))
        val deadProven = FakeHost(snapshot("A", "B", "C", "D", "E"), mutableListOf(VideoOutcome.Starving, VideoOutcome.Starving, VideoOutcome.Starving))
        YoutubeSwitchEngine(policy(), deadProven, { null }, excludedIds = { setOf("E") }, proven = { it == "E" }).runEpisode()
        assertEquals("a proven server the failover saw dead is not used", "restore:A", deadProven.calls.last())
    }

    @Test
    fun `dead candidates are no proof against the client, three servers that answer are`() = runTest {
        val deadOnes = FakeHost(snapshot("A", "B", "C", "D"), mutableListOf(VideoOutcome.Starving, VideoOutcome.Dead, VideoOutcome.Dead))
        assertEquals(YoutubeSwitchOutcome.AllCandidatesSlow, engine(deadOnes).runEpisode())
        val answering = FakeHost(snapshot("A", "B", "C", "D"), mutableListOf(VideoOutcome.Dead, VideoOutcome.Starving, VideoOutcome.Starving))
        assertEquals(YoutubeSwitchOutcome.StarvesEverywhere(3), engine(answering).runEpisode())
        assertEquals(3, YoutubeSwitchDefaults.CLIENT_STALL_MIN_SERVERS)
    }

    /** Field case 2026-11-15 (0.6.14): the two replacements tried first were a slow one and a dead one; proof goes first. */
    @Test
    fun `servers with a recent good result are tried before unknown ones`() {
        val policy = policy()
        val all = targets("Hipge", "A", "B", "C", "D")
        val picked = policy.candidates("Hipge", all, emptyMap(), alive = setOf("C", "D")) { null }
        assertEquals(listOf("C", "D", "A"), picked.map { it.id })
    }

    @Test
    fun `a known good YouTube speed still beats proof of life`() {
        val policy = policy()
        val all = targets("Hipge", "A", "B", "C")
        val average = mapOf("B" to 400 * kb)
        val picked = policy.candidates("Hipge", all, emptyMap(), alive = setOf("C")) { average[it] }
        assertEquals(listOf("B", "C", "A"), picked.map { it.id })
    }

    @Test
    fun `silence means inconclusive and the original server is restored`() = runTest {
        val host = FakeHost(snapshot("Hipge", "Senge"), mutableListOf(VideoOutcome.Silent))
        assertEquals(YoutubeSwitchOutcome.Inconclusive, engine(host).runEpisode())
        assertEquals("restore:Hipge", host.calls.last())
        assertFalse(host.calls.any { it.startsWith("commit") })
    }

    @Test
    fun `silence after a dead cluster keeps the candidate and asks for a video restart`() = runTest {
        val host = FakeHost(snapshot("Hipge", "Senge"), mutableListOf(VideoOutcome.Silent))
        assertEquals(YoutubeSwitchOutcome.SwitchedUnverified("Senge"), engine(host).runEpisode(keepOnSilent = true))
        assertEquals(
            listOf("trial:Hipge->Senge", "drop", "verify:Silent", "commit:Senge", "announce:Hipge->Senge:restart"),
            host.calls,
        )
    }

    @Test
    fun `silence after a dead cluster with a refused commit still restores the original`() = runTest {
        val host = FakeHost(snapshot("Hipge", "Senge"), mutableListOf(VideoOutcome.Silent), commitOk = false)
        assertEquals(YoutubeSwitchOutcome.CommitRefused, engine(host).runEpisode(keepOnSilent = true))
        assertEquals("restore:Hipge", host.calls.last())
        assertFalse(host.calls.any { it.startsWith("announce") })
    }

    @Test
    fun `a starving candidate after a dead cluster still moves on to the next one`() = runTest {
        val host = FakeHost(snapshot("Hipge", "Senge", "Fiege"), mutableListOf(VideoOutcome.Starving, VideoOutcome.Silent))
        val outcome = engine(host).runEpisode(keepOnSilent = true)
        assertTrue(outcome is YoutubeSwitchOutcome.SwitchedUnverified)
        assertEquals(2, host.calls.count { it.startsWith("trial") })
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
        assertEquals("only three candidates per episode, best known first", listOf("Better", "Good", "Unknown"), picked.map { it.id })
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
