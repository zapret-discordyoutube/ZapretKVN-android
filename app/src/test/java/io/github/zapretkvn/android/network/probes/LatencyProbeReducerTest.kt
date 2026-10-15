package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencySample
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.inOrderOf
import io.github.zapretkvn.android.vpn.markStale
import io.github.zapretkvn.android.vpn.sortedByLatency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyProbeReducerTest {
    @Test
    fun networkChangeDuringFirstProbeDoesNotLeaveServerProbingForever() {
        // Раунд оборван сменой сети, прежнего замера у сервера не было:
        // «Проверяется» без идущего раунда — зависшая строка.
        val running = LatencyProbeReducer.begin(groups(), 11, "root", "wifi-1").groups
        assertTrue(running.single().items.first().relay is LatencyProbeState.Running)

        val stale = LatencyProbeReducer.markStale(running)

        assertEquals(null, stale.single().probeProgress)
        assertEquals(LatencyProbeState.NotTested, stale.single().items.first().relay)
    }

    @Test
    fun frozenOrderKeepsRowsInPlaceAndAppendsNewServers() {
        val items = listOf("a", "b", "c", "new").map {
            RuntimeOutboundItem(tag = it, type = "vless", endpoint = null)
        }

        val shown = items.inOrderOf(listOf("c", "a", "b", "gone"))

        assertEquals(listOf("c", "a", "b", "new"), shown.map(RuntimeOutboundItem::tag))
    }

    @Test
    fun fastestFirstPutsKnownLatencyOnTopAndKeepsTheRestInOrder() {
        fun item(tag: String, relay: LatencyProbeState) =
            RuntimeOutboundItem(tag = tag, type = "vless", endpoint = null, relay = relay)
        fun ok(millis: Int) = LatencyProbeState.Success(LatencySample(millis, 1L, "wifi-1"))

        val sorted = listOf(
            item("untested-1", LatencyProbeState.NotTested),
            item("slow", ok(300)),
            item("failed", LatencyProbeState.Failed(LatencyFailure.NoResponse)),
            item("fast", ok(25)),
            item("stale", LatencyProbeState.Stale(LatencySample(120, 1L, "wifi-1"))),
            item("untested-2", LatencyProbeState.NotTested),
        ).sortedByLatency()

        assertEquals(
            listOf("fast", "stale", "slow", "untested-1", "failed", "untested-2"),
            sorted.map(RuntimeOutboundItem::tag),
        )
    }

    @Test
    fun beginIsSingleFlightAndMarksNestedGroupsUnsupported() {
        val first = LatencyProbeReducer.begin(groups(), 11, "root", "wifi-1")
        assertTrue(first.started)
        assertTrue(first.groups.single().items.first().relay is LatencyProbeState.Running)
        assertTrue(first.groups.single().items.last().relay is LatencyProbeState.Unsupported)

        val second = LatencyProbeReducer.begin(first.groups, 12, "root", "wifi-1")
        assertFalse(second.started)
        assertEquals(11L, second.groups.single().probeProgress?.requestId)
    }

    @Test
    fun batchesRequireExactRequestGroupAndNetworkEpoch() {
        val running = LatencyProbeReducer.begin(groups(), 11, "root", "wifi-1").groups
        val success = mapOf(
            "leaf" to LatencyProbeState.Success(LatencySample(84, 20_000, "wifi-1")),
        )
        val wrongRequest = LatencyProbeReducer.publishBatch(
            running, 10, "root", "wifi-1", success,
        )
        val wrongNetwork = LatencyProbeReducer.publishBatch(
            running, 11, "root", "wifi-2", success,
        )
        assertTrue(wrongRequest.single().items.first().relay is LatencyProbeState.Running)
        assertTrue(wrongNetwork.single().items.first().relay is LatencyProbeState.Running)

        val accepted = LatencyProbeReducer.publishBatch(
            running, 11, "root", "wifi-1", success,
        )
        assertTrue(accepted.single().items.first().relay is LatencyProbeState.Success)
        assertEquals(2, accepted.single().probeProgress?.relayCompleted)
    }

    @Test
    fun cancellationRestoresPreviousValueAndRejectsLateResult() {
        val previous = LatencySample(21, 10_000, "wifi-1")
        val initial = groups().map { group ->
            group.copy(items = group.items.map { item ->
                if (item.tag == "leaf") item.copy(relay = LatencyProbeState.Success(previous)) else item
            })
        }
        val running = LatencyProbeReducer.begin(initial, 11, "root", "wifi-1").groups
        val cancelled = LatencyProbeReducer.cancel(running, 11, "root", "wifi-1")
        assertEquals(LatencyProbeState.Success(previous), cancelled.single().items.first().relay)
        assertEquals(null, cancelled.single().probeProgress)

        val late = LatencyProbeReducer.publishBatch(
            cancelled,
            11,
            "root",
            "wifi-1",
            mapOf("leaf" to LatencyProbeState.Success(LatencySample(99, 30_000, "wifi-1"))),
        )
        assertEquals(cancelled, late)
    }

    @Test
    fun networkChangeMakesOnlySuccessfulHistoryStale() {
        val sample = LatencySample(42, 10_000, "wifi-1")
        val stale = LatencyProbeReducer.markStale(
            groups().map { group ->
                group.copy(items = group.items.map { item ->
                    if (item.tag == "leaf") {
                        item.copy(relay = LatencyProbeState.Success(sample))
                    } else {
                        item
                    }
                })
            },
        )
        assertEquals(LatencyProbeState.Stale(sample), stale.single().items.first().relay)
    }

    private fun groups(): List<RuntimeSelectorGroup> = listOf(
        RuntimeSelectorGroup(
            tag = "root",
            type = "selector",
            selected = "leaf",
            selectable = true,
            items = listOf(
                RuntimeOutboundItem("leaf", "vless", "vpn.example:443"),
                RuntimeOutboundItem("nested", "selector", null),
            ),
        ),
    )
}
