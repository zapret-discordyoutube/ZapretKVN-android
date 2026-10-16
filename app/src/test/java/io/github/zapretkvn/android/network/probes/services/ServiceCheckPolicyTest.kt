package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.network.probes.ProbeLane
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCheckPolicyTest {
    @Test
    fun tapIsAllowedFirstThenWaitsOutTheCooldown() {
        var now = 1_000L
        val policy = ServiceCheckPolicy { now }
        assertTrue(policy.tapAllowed())
        policy.runFinished()
        now += 14_999
        assertFalse(policy.tapAllowed())
        now += 1
        assertTrue(policy.tapAllowed())
    }


}

class ProbeLaneTest {
    @Test
    fun onlyOneOwnerAtATime() {
        val lane = ProbeLane()
        val first = lane.tryAcquire(ProbeLane.Owner.Services)
        assertNotNull(first)
        assertNull(lane.tryAcquire(ProbeLane.Owner.Services))
        assertEquals(ProbeLane.Owner.Services, lane.heldBy)
        first!!.release()
        first.release() // идемпотентно
        assertNull(lane.heldBy)
        assertNotNull(lane.tryAcquire(ProbeLane.Owner.Latency))
    }

    @Test
    fun serverLatencyCheckPreemptsServiceCheck() = runBlocking {
        val lane = ProbeLane()
        var preempted = false
        lateinit var services: ProbeLane.Lease
        services = lane.tryAcquire(ProbeLane.Owner.Services, onPreempt = {
            preempted = true
            services.release()
        })!!

        val latency = async { lane.acquire(ProbeLane.Owner.Latency) }
        val lease = latency.await()

        assertTrue(preempted)
        assertEquals(ProbeLane.Owner.Latency, lease.owner)
        assertEquals(ProbeLane.Owner.Latency, lane.heldBy)
    }

    @Test
    fun serviceCheckCannotPreemptLatencyAndWaitsForIt() = runBlocking {
        val lane = ProbeLane()
        val latency = lane.tryAcquire(ProbeLane.Owner.Latency)!!
        var preempted = false
        val waiting = async { lane.acquire(ProbeLane.Owner.Services, onPreempt = { preempted = true }) }

        delay(30)
        assertFalse(waiting.isCompleted)
        latency.release()
        val lease = waiting.await()

        assertFalse(preempted)
        assertEquals(ProbeLane.Owner.Services, lease.owner)
        assertNull(lane.tryAcquire(ProbeLane.Owner.Latency))
    }
}
