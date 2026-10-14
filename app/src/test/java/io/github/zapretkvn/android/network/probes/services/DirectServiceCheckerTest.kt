package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind
import io.github.zapretkvn.android.network.probes.ProbeLane
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectServiceCheckerTest {
    private class FakeSource(
        var identity: String? = "wifi-1",
        var transport: ServiceProbeTransport? = null,
    ) : DirectNetworkSource {
        var opened = 0
        var closed = 0
        var listener: (() -> Unit)? = null

        override fun open(onChange: () -> Unit): DirectNetworkSession {
            opened++
            listener = onChange
            return object : DirectNetworkSession {
                override val identity: String? get() = this@FakeSource.identity
                override fun transport(): ServiceProbeTransport? = this@FakeSource.transport
                override fun close() {
                    closed++
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lane = ProbeLane()
    private var now = 1_000L

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun checker(
        source: FakeSource,
        services: Set<ServiceId> = ServiceCatalog.defaultIds,
    ) = DirectServiceChecker(
        scope = scope,
        lane = lane,
        source = source,
        definitions = { ServiceCatalog.resolve(services) },
        runner = ServiceProbeRunner(random = Random(5), sleep = {}, clock = { 777L }),
        elapsed = { now },
    )

    private suspend fun DirectServiceChecker.awaitIdle(): ServiceAvailabilityState =
        withTimeout(10_000) { state.first { !it.running && it.items.isNotEmpty() } }

    private suspend fun DirectServiceChecker.awaitRunning() {
        withTimeout(10_000) { state.first { it.running } }
    }

    private suspend fun awaitLaneFree() {
        withTimeout(10_000) { while (lane.heldBy != null) kotlinx.coroutines.yield() }
    }

    private fun ServiceAvailabilityState.snapshots() =
        items.values.map { (it as ServiceAvailability.Result).snapshot }

    @Test
    fun runsOnlyTheDirectPathAgainstPrimaryCatalogTargetsOfTheSelectedServices() = runBlocking<Unit> {
        val transport = FakeTransport { http(200, millis = 31) }
        val checker = checker(FakeSource(transport = transport), setOf(ServiceId.YouTube, ServiceId.Discord))

        checker.check()
        val state = checker.awaitIdle()

        assertEquals(setOf("www.youtube.com", "discord.com"), transport.hosts.toSet())
        assertEquals(2, transport.calls.size)
        assertTrue(ServiceCatalog.allTargets.containsAll(transport.hosts))
        assertEquals(setOf(ServiceId.YouTube, ServiceId.Discord), state.items.keys)
        state.snapshots().forEach { snapshot ->
            assertEquals(Headline.Direct, snapshot.headline)
            assertEquals(ServiceVerdict.Ok, snapshot.direct.verdict)
            assertEquals(ServiceVerdict.NotMeasured, snapshot.tunnel.verdict)
            assertEquals(31, snapshot.direct.latencyMillis)
            assertEquals("wifi-1", snapshot.networkIdentity)
        }
        assertEquals(2, state.lastRun?.requestsDone)
        awaitLaneFree()
    }

    @Test
    fun defaultSelectionFitsTheBudgetWithOneRequestPerService() = runBlocking<Unit> {
        val transport = FakeTransport { http(200) }
        val checker = checker(FakeSource(transport = transport))

        checker.check()
        val state = checker.awaitIdle()

        assertEquals(ServiceCatalog.defaults.size, transport.calls.size)
        assertTrue(transport.calls.none { it is ProbeTarget.Tcp })
        assertTrue(transport.calls.size <= ServiceProbeBudget.MAX_REQUESTS)
        assertTrue(checkNotNull(state.lastRun).bytes <= ServiceProbeBudget.MAX_BYTES)
    }

    @Test
    fun resetAndTimeoutAreBlockedWhileHttpErrorsAreUnreachable() = runBlocking<Unit> {
        val transport = FakeTransport { target ->
            when ((target as ProbeTarget.Https).endpoint.host) {
                "www.youtube.com" -> failure(HttpsProbeFailureKind.Reset)
                "discord.com" -> failure(HttpsProbeFailureKind.Timeout)
                "t.me" -> failure(HttpsProbeFailureKind.Dns)
                else -> http(200)
            }
        }
        val checker = checker(FakeSource(transport = transport))

        checker.check()
        val byService = checker.awaitIdle().snapshots().associateBy { it.service }

        assertEquals(ServiceVerdict.Blocked, byService.getValue(ServiceId.YouTube).direct.verdict)
        assertEquals(HttpsProbeFailureKind.Reset, byService.getValue(ServiceId.YouTube).direct.failure)
        assertEquals(HttpsProbeFailureKind.Timeout, byService.getValue(ServiceId.Discord).direct.failure)
        assertEquals(HttpsProbeFailureKind.Dns, byService.getValue(ServiceId.Telegram).direct.failure)
        assertEquals(Headline.Unavailable, byService.getValue(ServiceId.YouTube).headline)
        assertEquals(Headline.Direct, byService.getValue(ServiceId.Gemini).headline)
    }

    @Test
    fun nothingRunsOnItsOwn() = runBlocking<Unit> {
        val transport = FakeTransport { http(200) }
        val source = FakeSource(transport = transport)
        val checker = checker(source)

        kotlinx.coroutines.delay(300)

        assertEquals(ServiceAvailabilityState.Initial, checker.state.value)
        assertEquals(0, source.opened)
        assertTrue(transport.calls.isEmpty())
        assertNull(lane.heldBy)
    }

    @Test
    fun tapsAreSpacedByTheCooldown() = runBlocking<Unit> {
        val transport = FakeTransport { http(200) }
        val checker = checker(FakeSource(transport = transport), setOf(ServiceId.YouTube))

        checker.check()
        checker.awaitIdle()
        awaitLaneFree()
        now += ServiceCheckPolicy.TAP_COOLDOWN_MILLIS - 1
        checker.check()
        kotlinx.coroutines.delay(300)
        assertEquals(1, transport.calls.size)

        now += 1
        checker.check()
        withTimeout(10_000) { while (transport.calls.size < 2) kotlinx.coroutines.yield() }
        checker.awaitIdle()
    }

    @Test
    fun aSecondTapWhileRunningDoesNotStartAnotherRun() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport { gate.await(); http(200) }
        val checker = checker(FakeSource(transport = transport), setOf(ServiceId.YouTube))

        checker.check()
        checker.awaitRunning()
        checker.check()
        gate.complete(Unit)
        checker.awaitIdle()

        assertEquals(1, transport.calls.size)
    }

    @Test
    fun notOfferedWhileTheVpnIsNotStopped() = runBlocking<Unit> {
        val transport = FakeTransport { http(200) }
        val source = FakeSource(transport = transport)
        val checker = checker(source)

        checker.onVpnIdle(false)
        checker.check()
        kotlinx.coroutines.delay(300)
        assertEquals(0, source.opened)
        assertTrue(transport.calls.isEmpty())

        checker.onVpnIdle(true)
        checker.check()
        assertNotNull(checker.awaitIdle().lastRun)
    }

    @Test
    fun connectingTheVpnClearsResultsAndClosesTheNetworkObservation() = runBlocking<Unit> {
        val source = FakeSource(transport = FakeTransport { http(200) })
        val checker = checker(source, setOf(ServiceId.YouTube))
        checker.check()
        checker.awaitIdle()
        assertEquals(1, source.opened)
        assertEquals(0, source.closed)

        checker.onVpnIdle(false)

        assertEquals(ServiceAvailabilityState.Initial, checker.state.value)
        assertEquals(1, source.closed)
    }

    @Test
    fun connectingTheVpnCancelsARunningCheckAndFreesTheLane() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val checker = checker(FakeSource(transport = FakeTransport { gate.await(); http(200) }), setOf(ServiceId.YouTube))
        checker.check()
        checker.awaitRunning()

        checker.onVpnIdle(false)
        awaitLaneFree()

        assertEquals(ServiceAvailabilityState.Initial, checker.state.value)
        gate.complete(Unit)
        kotlinx.coroutines.delay(300)
        assertEquals(ServiceAvailabilityState.Initial, checker.state.value)
    }

    @Test
    fun aNetworkChangeMarksResultsStaleWithoutRerunning() = runBlocking<Unit> {
        val transport = FakeTransport { http(200) }
        val source = FakeSource(transport = transport)
        val checker = checker(source, setOf(ServiceId.YouTube))
        checker.check()
        checker.awaitIdle()

        source.listener!!.invoke()

        assertTrue(checker.state.value.items.values.all { it is ServiceAvailability.Stale })
        kotlinx.coroutines.delay(300)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun aNetworkChangeDuringARunCancelsItAndNeverPublishesItsResultsAsFresh() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(transport = FakeTransport { gate.await(); http(200) })
        val checker = checker(source, setOf(ServiceId.YouTube))
        checker.check()
        checker.awaitRunning()

        source.listener!!.invoke()
        awaitLaneFree()
        gate.complete(Unit)
        kotlinx.coroutines.delay(300)

        val state = checker.state.value
        assertFalse(state.running)
        assertTrue(state.items.values.none { it is ServiceAvailability.Result })
    }

    @Test
    fun withoutAPhysicalNetworkItSaysSoAndKeepsNoCallbacks() = runBlocking<Unit> {
        val transport = FakeTransport { http(200) }
        val source = FakeSource(identity = null, transport = null)
        val checker = checker(source)

        checker.check()
        withTimeout(10_000) { checker.state.first { it.noNetwork } }

        assertTrue(checker.state.value.items.isEmpty())
        assertEquals(source.opened, source.closed)
        assertNull(lane.heldBy)
        assertTrue(transport.calls.isEmpty())

        // Сеть появилась: следующее нажатие работает и убирает «нет интернета».
        source.identity = "wifi-2"
        source.transport = transport
        checker.check()
        val state = checker.awaitIdle()
        assertFalse(state.noNetwork)
        assertTrue(transport.calls.isNotEmpty())
    }

    @Test
    fun aBusyLaneMeansNoRun() = runBlocking<Unit> {
        val transport = FakeTransport { http(200) }
        val checker = checker(FakeSource(transport = transport), setOf(ServiceId.YouTube))
        val connect = lane.acquire(ProbeLane.Owner.Connect)

        checker.check()
        kotlinx.coroutines.delay(300)
        assertTrue(transport.calls.isEmpty())
        assertEquals(ServiceAvailabilityState.Initial, checker.state.value)

        connect.release()
        checker.check()
        checker.awaitIdle()
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun theConnectPipelineTakesTheLaneAndKeepsThePreviousResults() = runBlocking<Unit> {
        var block = false
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport { if (block) gate.await(); http(200) }
        val checker = checker(FakeSource(transport = transport), setOf(ServiceId.YouTube))
        checker.check()
        val first = checker.awaitIdle()
        awaitLaneFree()
        now += ServiceCheckPolicy.TAP_COOLDOWN_MILLIS
        block = true
        checker.check()
        checker.awaitRunning()

        val connect = lane.acquire(ProbeLane.Owner.Connect)

        assertEquals(ProbeLane.Owner.Connect, lane.heldBy)
        val state = withTimeout(10_000) { checker.state.first { !it.running } }
        assertEquals(first.items.keys, state.items.keys)
        assertTrue(state.items.values.all { it.snapshot() != null })
        connect.release()
        gate.complete(Unit)
    }
}
