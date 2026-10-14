package io.github.zapretkvn.android.network.probes.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceAvailabilityReducerTest {
    private val ids = listOf(ServiceId.YouTube, ServiceId.Telegram)
    private val summary = ServiceRunSummary(ServiceCheckTrigger.Tap, 1_000, 10, 10, 20_000, 5L)

    private fun begun(
        from: ServiceAvailabilityState = ServiceAvailabilityState.Initial,
        requestId: Long = 1,
    ) = ServiceAvailabilityReducer.begin(from, requestId, "wifi-1", ids, ServiceCheckTrigger.Tap).state

    @Test
    fun beginMarksEveryServiceCheckingAndKeepsPreviousResult() {
        val before = ServiceAvailabilityState(
            items = mapOf(ServiceId.YouTube to ServiceAvailability.Result(snapshot(ServiceId.YouTube))),
        )

        val state = begun(before)

        assertTrue(state.running)
        val youtube = state.items.getValue(ServiceId.YouTube) as ServiceAvailability.Checking
        assertEquals(snapshot(ServiceId.YouTube), youtube.previous)
        assertEquals(ServiceAvailability.Checking(null), state.items.getValue(ServiceId.Telegram))
    }

    @Test
    fun secondBeginWhileRunningIsRefused() {
        val running = begun()
        val again = ServiceAvailabilityReducer.begin(running, 2, "wifi-1", ids, ServiceCheckTrigger.Auto)
        assertFalse(again.started)
        assertEquals(running, again.state)
    }

    @Test
    fun batchPublishesResultsForMatchingRequestAndNetworkOnly() {
        val running = begun()
        val batch = mapOf(ServiceId.YouTube to snapshot(ServiceId.YouTube))

        val wrongRequest = ServiceAvailabilityReducer.publishBatch(running, 99, "wifi-1", batch)
        val wrongNetwork = ServiceAvailabilityReducer.publishBatch(running, 1, "cell-1", batch)
        val accepted = ServiceAvailabilityReducer.publishBatch(running, 1, "wifi-1", batch)

        assertEquals(running, wrongRequest)
        assertEquals(running, wrongNetwork)
        assertEquals(ServiceAvailability.Result(snapshot(ServiceId.YouTube)), accepted.items[ServiceId.YouTube])
        assertTrue(accepted.items[ServiceId.Telegram] is ServiceAvailability.Checking)
    }

    @Test
    fun notCheckedSnapshotDoesNotEraseThePreviousResult() {
        val old = ServiceAvailability.Result(snapshot(ServiceId.YouTube, Headline.ViaVpn))
        val running = begun(ServiceAvailabilityState(items = mapOf(ServiceId.YouTube to old)))

        val state = ServiceAvailabilityReducer.publishBatch(
            running, 1, "wifi-1",
            mapOf(ServiceId.YouTube to snapshot(ServiceId.YouTube, Headline.NotChecked)),
        )

        assertTrue(state.items[ServiceId.YouTube] is ServiceAvailability.Checking)
    }

    @Test
    fun completeSettlesLeftoversAndStoresTheSummary() {
        val old = snapshot(ServiceId.Telegram, Headline.Direct)
        val running = begun(ServiceAvailabilityState(items = mapOf(ServiceId.Telegram to ServiceAvailability.Result(old))))
        val withResult = ServiceAvailabilityReducer.publishBatch(
            running, 1, "wifi-1", mapOf(ServiceId.YouTube to snapshot(ServiceId.YouTube)),
        )

        val done = ServiceAvailabilityReducer.complete(withResult, 1, "wifi-1", summary)

        assertFalse(done.running)
        assertEquals(summary, done.lastRun)
        assertTrue(done.items[ServiceId.YouTube] is ServiceAvailability.Result)
        // Telegram не получил нового результата: прежний остаётся, но устаревший.
        assertEquals(ServiceAvailability.Stale(old), done.items[ServiceId.Telegram])
    }

    @Test
    fun completeIgnoresAForeignRequest() {
        val running = begun()
        assertEquals(running, ServiceAvailabilityReducer.complete(running, 5, "wifi-1", summary))
        assertEquals(running, ServiceAvailabilityReducer.complete(running, 1, "cell-1", summary))
    }

    @Test
    fun cancelRestoresPreviousResultsAndNeverFakesFreshness() {
        val fresh = snapshot(ServiceId.YouTube)
        val stale = snapshot(ServiceId.Telegram)
        val before = ServiceAvailabilityState(
            items = mapOf(
                ServiceId.YouTube to ServiceAvailability.Result(fresh),
                ServiceId.Telegram to ServiceAvailability.Stale(stale),
            ),
        )

        val cancelled = ServiceAvailabilityReducer.cancel(begun(before), 1, "wifi-1")

        assertFalse(cancelled.running)
        assertEquals(ServiceAvailability.Result(fresh), cancelled.items[ServiceId.YouTube])
        assertEquals(ServiceAvailability.Stale(stale), cancelled.items[ServiceId.Telegram])
    }

    @Test
    fun cancelWithoutPreviousResultReturnsToNotChecked() {
        val cancelled = ServiceAvailabilityReducer.cancel(begun(), 1, "wifi-1")
        assertEquals(ServiceAvailability.NotChecked, cancelled.items[ServiceId.YouTube])
    }

    @Test
    fun markStaleTurnsResultsStaleAndStopsTheRun() {
        val running = ServiceAvailabilityReducer.publishBatch(
            begun(), 1, "wifi-1", mapOf(ServiceId.YouTube to snapshot(ServiceId.YouTube)),
        )

        val stale = ServiceAvailabilityReducer.markStale(running)

        assertFalse(stale.running)
        assertEquals(ServiceAvailability.Stale(snapshot(ServiceId.YouTube)), stale.items[ServiceId.YouTube])
        // Без прежнего результата «проверяется» не зависает.
        assertEquals(ServiceAvailability.NotChecked, stale.items[ServiceId.Telegram])
    }

    @Test
    fun batchAfterStaleIsIgnored() {
        val stale = ServiceAvailabilityReducer.markStale(begun())
        val late = ServiceAvailabilityReducer.publishBatch(
            stale, 1, "wifi-1", mapOf(ServiceId.YouTube to snapshot(ServiceId.YouTube)),
        )
        assertEquals(stale, late)
    }

    @Test
    fun noNetworkIsFlaggedOnlyWhenIdleAndClearedByTheNextRunOrStale() {
        val idle = ServiceAvailabilityReducer.noNetwork(ServiceAvailabilityState.Initial)
        assertTrue(idle.noNetwork)

        val running = begun()
        assertEquals(running, ServiceAvailabilityReducer.noNetwork(running))

        assertFalse(begun(idle).noNetwork)
        assertFalse(ServiceAvailabilityReducer.markStale(idle).noNetwork)
    }

    @Test
    fun noNetworkKeepsThePreviousResults() {
        val before = ServiceAvailabilityState(
            items = mapOf(ServiceId.YouTube to ServiceAvailability.Result(snapshot(ServiceId.YouTube))),
        )
        val state = ServiceAvailabilityReducer.noNetwork(before)
        assertEquals(before.items, state.items)
    }
}
