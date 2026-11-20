package io.github.zapretkvn.clientreports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchDeliveryTest {
    @Test
    fun theClockAheadIsCorrectedOnlyWhenItIsReal() {
        val clock = ReportClock()
        assertEquals(0, clock.applied())
        clock.learn(serverMillis = 1_000_000, localMillis = 1_030_000)      // 30 s: Date rounding and the round trip
        assertEquals(0, clock.applied())
        clock.learn(serverMillis = 1_000_000, localMillis = 1_000_000 + 3 * 3_600_000)   // PC three hours ahead
        assertEquals(-3 * 3_600_000L, clock.applied())
        clock.learn(serverMillis = 0, localMillis = 5)                       // no Date header: keep what we know
        assertEquals(-3 * 3_600_000L, clock.applied())
    }

    @Test
    fun aRefusedBatchLosesOnlyTheRefusedReport() {
        val bad = 7
        val accepted = mutableListOf<Int>()
        val dropped = mutableListOf<Int>()
        var requests = 0
        val result = BatchDelivery.deliver(
            (0 until 20).toList(),
            send = { part -> requests++; if (bad in part) SendAction.Drop else SendAction.Remove },
            resolved = { part, action -> (if (action == SendAction.Remove) accepted else dropped) += part },
        )
        assertNull(result)
        assertEquals(listOf(bad), dropped)
        assertEquals((0 until 20).filter { it != bad }, accepted.sorted())
        assertEquals(true, requests <= 1 + BatchDelivery.SPLIT_BUDGET)
    }

    @Test
    fun aSplitStopsWithinItsBudgetAndOnNoAnswer() {
        var requests = 0
        val dropped = mutableListOf<Int>()
        BatchDelivery.deliver((0 until 50).toList(), { requests++; SendAction.Drop }, { part, _ -> dropped += part })
        assertEquals((0 until 50).toList(), dropped.sorted())
        assertEquals(true, requests <= 1 + BatchDelivery.SPLIT_BUDGET)

        val kept = mutableListOf<Int>()
        val stop = BatchDelivery.deliver(
            (0 until 4).toList(),
            send = { part -> if (part.size == 4) SendAction.Drop else if (0 in part) SendAction.Remove else SendAction.TryOtherPath },
            resolved = { part, _ -> kept += part },
        )
        assertEquals(SendAction.TryOtherPath, stop)
        assertEquals(listOf(0, 1), kept)
    }
}
