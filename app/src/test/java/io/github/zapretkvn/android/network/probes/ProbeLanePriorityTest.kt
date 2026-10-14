package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.network.probes.ProbeLane.Owner
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeLanePriorityTest {
    @Test
    fun priorityTableIsConnectOverEverythingAndLatencyOrThroughputOverServices() {
        val expected = mapOf(
            (Owner.Connect to Owner.Latency) to true,
            (Owner.Connect to Owner.Throughput) to true,
            (Owner.Connect to Owner.Services) to true,
            (Owner.Connect to Owner.Connect) to false,
            (Owner.Latency to Owner.Services) to true,
            (Owner.Throughput to Owner.Services) to true,
            (Owner.Latency to Owner.Throughput) to false,
            (Owner.Throughput to Owner.Latency) to false,
            (Owner.Latency to Owner.Connect) to false,
            (Owner.Throughput to Owner.Connect) to false,
            (Owner.Services to Owner.Latency) to false,
            (Owner.Services to Owner.Throughput) to false,
            (Owner.Services to Owner.Connect) to false,
        )
        expected.forEach { (pair, value) ->
            assertEquals("${pair.first} -> ${pair.second}", value, ProbeLane.mayPreempt(pair.first, pair.second))
        }
    }

    @Test
    fun tryAcquireIsExclusiveAndReleaseIsIdempotent() {
        val lane = ProbeLane()
        val first = lane.tryAcquire(Owner.Services)
        assertNotNull(first)
        assertEquals(Owner.Services, lane.heldBy)
        assertNull(lane.tryAcquire(Owner.Latency))
        first!!.release()
        first.release()
        assertNull(lane.heldBy)
        assertNotNull(lane.tryAcquire(Owner.Latency))
    }

    @Test
    fun connectSignalsAHolderAndWaitsUntilItReleases() = runTest {
        for (holder in listOf(Owner.Services, Owner.Latency, Owner.Throughput)) {
            val lane = ProbeLane()
            val signalled = AtomicInteger()
            val held = lane.acquire(holder) { signalled.incrementAndGet() }
            val connect = async { lane.acquire(Owner.Connect) }
            runCurrent()
            assertEquals("$holder must be asked to yield", 1, signalled.get())
            assertFalse(connect.isCompleted)

            held.release()
            val lease = connect.await()
            assertEquals(Owner.Connect, lane.heldBy)
            lease.release()
            assertNull(lane.heldBy)
        }
    }

    @Test
    fun nobodyAsksConnectToYield() = runTest {
        val lane = ProbeLane()
        val signalled = AtomicInteger()
        val connect = lane.acquire(Owner.Connect) { signalled.incrementAndGet() }
        val others = listOf(Owner.Latency, Owner.Throughput, Owner.Services).map { owner ->
            async { lane.acquire(owner) }
        }
        runCurrent()
        assertEquals(0, signalled.get())
        assertTrue(others.none { it.isCompleted })
        connect.release()
        runCurrent()
        // Полосу получает один из ожидающих, остальные ждут своей очереди.
        assertEquals(1, others.count { it.isCompleted })
        others.forEach { it.cancel() }
        runCurrent()
    }

    @Test
    fun latencyAndThroughputDoNotPreemptEachOtherAndServicesPreemptNobody() = runTest {
        val lane = ProbeLane()
        val signalled = AtomicInteger()
        val latency = lane.acquire(Owner.Latency) { signalled.incrementAndGet() }
        val throughput = async { lane.acquire(Owner.Throughput) }
        val services = async { lane.acquire(Owner.Services) }
        runCurrent()
        assertEquals(0, signalled.get())
        assertFalse(throughput.isCompleted)
        assertFalse(services.isCompleted)
        latency.release()
        throughput.await().release()
        services.await().release()
        assertNull(lane.heldBy)
    }

    @Test
    fun latencyThatTookOverFromServicesIsAskedToYieldWhenConnectArrives() = runTest {
        val lane = ProbeLane()
        lateinit var services: ProbeLane.Lease
        services = lane.acquire(Owner.Services) { services.release() }
        val latencySignalled = AtomicInteger()
        val latency = async { lane.acquire(Owner.Latency) { latencySignalled.incrementAndGet() } }
        runCurrent()
        // Latency вытеснил Services и получил полосу; подключение приходит следом.
        assertEquals(Owner.Latency, lane.heldBy)
        assertEquals(0, latencySignalled.get())
        val connect = async { lane.acquire(Owner.Connect) }
        runCurrent()
        assertEquals(1, latencySignalled.get())
        latency.await().release()
        connect.await().release()
    }

    @Test
    fun connectQueuedBehindLatencyStillGetsLatencyToYield() = runTest {
        val lane = ProbeLane()
        lateinit var services: ProbeLane.Lease
        val servicesSignalled = AtomicInteger()
        services = lane.acquire(Owner.Services) {
            servicesSignalled.incrementAndGet()
        }
        val latencySignalled = AtomicInteger()
        val latency = async { lane.acquire(Owner.Latency) { latencySignalled.incrementAndGet() } }
        val connect = async { lane.acquire(Owner.Connect) }
        runCurrent()
        assertEquals(2, servicesSignalled.get())
        services.release()
        runCurrent()
        // Latency стоял первым и получил полосу, но подключение уже ждёт: Latency сразу просят уступить.
        assertEquals(1, latencySignalled.get())
        assertEquals(Owner.Latency, lane.heldBy)
        latency.await().release()
        connect.await().release()
        assertNull(lane.heldBy)
    }

    @Test
    fun connectWaitsOnlyForTheLimitAndNeverDeadlocks() = runTest {
        val lane = ProbeLane()
        val stubborn = lane.acquire(Owner.Throughput) // не умеет уступать
        val connect = async { lane.acquireOrNull(Owner.Connect, ProbeLane.CONNECT_WAIT_MILLIS) }
        runCurrent()
        advanceTimeBy(ProbeLane.CONNECT_WAIT_MILLIS - 1)
        assertFalse(connect.isCompleted)
        advanceTimeBy(2)
        assertNull(connect.await())
        // Ожидание не оставило следов: полоса работает как раньше.
        assertEquals(Owner.Throughput, lane.heldBy)
        stubborn.release()
        assertNotNull(lane.tryAcquire(Owner.Latency))
    }

    @Test
    fun acquireOrNullReturnsTheLeaseOnceTheHolderYields() = runTest {
        val lane = ProbeLane()
        lateinit var services: ProbeLane.Lease
        services = lane.acquire(Owner.Services) { services.release() }
        val lease = lane.acquireOrNull(Owner.Connect, ProbeLane.CONNECT_WAIT_MILLIS)
        assertNotNull(lease)
        assertEquals(Owner.Connect, lane.heldBy)
    }

    @Test
    fun runPreemptibleReturnsTheValueAndFreesTheLane() = runTest {
        val lane = ProbeLane()
        val run = lane.runPreemptible(Owner.Throughput, 1_000) { 42 }
        assertEquals(LaneRun.Done(42), run)
        assertNull(lane.heldBy)
    }

    @Test
    fun runPreemptibleIsCancelledWhenConnectNeedsTheLane() = runTest {
        val lane = ProbeLane()
        val started = CompletableDeferred<Unit>()
        val run = async {
            lane.runPreemptible(Owner.Throughput, 1_000) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        val connect = async { lane.acquire(Owner.Connect) }
        runCurrent()
        assertSame(LaneRun.Preempted, run.await())
        connect.await().release()
        assertNull(lane.heldBy)
    }

    @Test
    fun runPreemptibleGivesUpAfterTheWaitWithoutRunningTheBlock() = runTest {
        val lane = ProbeLane()
        val holder = lane.acquire(Owner.Latency) // ни Throughput, ни Latency друг друга не вытесняют
        var ran = false
        val run = async { lane.runPreemptible(Owner.Throughput, 5_000) { ran = true } }
        runCurrent()
        advanceTimeBy(5_001)
        assertSame(LaneRun.Timeout, run.await())
        assertFalse(ran)
        holder.release()
    }

    @Test
    fun runPreemptibleDoesNotSwallowRealCancellation() = runTest {
        val lane = ProbeLane()
        val started = CompletableDeferred<Unit>()
        val job = async {
            lane.runPreemptible(Owner.Throughput, 1_000) {
                started.complete(Unit)
                delay(60_000)
            }
        }
        started.await()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertNull(lane.heldBy)
    }

    @Test
    fun acquireSettledHoldsTheLaneThroughThePauseAndReturnsIt() = runTest {
        val lane = ProbeLane()
        val signalled = AtomicInteger()
        val result = async {
            lane.acquireSettled(Owner.Services, { signalled.incrementAndGet() }) { delay(2_000) }
        }
        runCurrent()
        assertEquals(Owner.Services, lane.heldBy)
        advanceTimeBy(2_001)
        val lease = result.await()
        assertNotNull(lease)
        assertEquals(0, signalled.get())
        assertEquals(Owner.Services, lane.heldBy)
        lease!!.release()
    }

    @Test
    fun acquireSettledYieldsDuringThePauseAndReleasesTheLane() = runTest {
        val lane = ProbeLane()
        val signalled = AtomicInteger()
        val result = async {
            lane.acquireSettled(Owner.Services, { signalled.incrementAndGet() }) { delay(60_000) }
        }
        runCurrent()
        val connect = async { lane.acquire(Owner.Connect) }
        runCurrent()
        // Пауза прервана сразу, не через минуту.
        assertNull(result.await())
        assertEquals(1, signalled.get())
        assertEquals(Owner.Connect, connect.await().owner)
        connect.await().release()
        assertNull(lane.heldBy)
    }

    @Test
    fun acquireSettledReleasesTheLaneWhenTheCallerIsCancelled() = runTest {
        val lane = ProbeLane()
        val result = async { lane.acquireSettled(Owner.Services, {}) { delay(60_000) } }
        runCurrent()
        assertEquals(Owner.Services, lane.heldBy)
        result.cancel()
        runCurrent()
        assertNull(lane.heldBy)
    }
}
