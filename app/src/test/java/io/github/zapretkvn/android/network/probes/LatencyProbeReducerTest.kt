package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencySample
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.markStale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyProbeReducerTest {
    @Test
    fun beginIsSingleFlightAndMarksNestedGroupsUnsupported() {
        val first = LatencyProbeReducer.begin(groups(), 11, "root", "wifi-1", setOf("leaf"))
        assertTrue(first.started)
        assertTrue(first.groups.single().items.first().relay is LatencyProbeState.Running)
        assertTrue(first.groups.single().items.last().relay is LatencyProbeState.Unsupported)

        val second = LatencyProbeReducer.begin(first.groups, 12, "root", "wifi-1", setOf("leaf"))
        assertFalse(second.started)
        assertEquals(11L, second.groups.single().probeProgress?.requestId)
    }

    @Test
    fun batchesRequireExactRequestGroupAndNetworkEpoch() {
        val running = LatencyProbeReducer.begin(groups(), 11, "root", "wifi-1", setOf("leaf")).groups
        val success = mapOf(
            "leaf" to LatencyProbeState.Success(LatencySample(84, 20_000, "wifi-1")),
        )
        val wrongRequest = LatencyProbeReducer.publishBatch(
            running, 10, "root", "wifi-1", success, emptyMap(),
        )
        val wrongNetwork = LatencyProbeReducer.publishBatch(
            running, 11, "root", "wifi-2", success, emptyMap(),
        )
        assertTrue(wrongRequest.single().items.first().relay is LatencyProbeState.Running)
        assertTrue(wrongNetwork.single().items.first().relay is LatencyProbeState.Running)

        val accepted = LatencyProbeReducer.publishBatch(
            running, 11, "root", "wifi-1", success, emptyMap(),
        )
        assertTrue(accepted.single().items.first().relay is LatencyProbeState.Success)
        assertEquals(2, accepted.single().probeProgress?.relayCompleted)
    }

    @Test
    fun cancellationRestoresPreviousValueAndRejectsLateResult() {
        val previous = LatencySample(21, 10_000, "wifi-1")
        val initial = groups().map { group ->
            group.copy(items = group.items.map { item ->
                if (item.tag == "leaf") item.copy(icmp = LatencyProbeState.Success(previous)) else item
            })
        }
        val running = LatencyProbeReducer.begin(initial, 11, "root", "wifi-1", setOf("leaf")).groups
        val cancelled = LatencyProbeReducer.cancel(running, 11, "root", "wifi-1")
        assertEquals(LatencyProbeState.Success(previous), cancelled.single().items.first().icmp)
        assertEquals(null, cancelled.single().probeProgress)

        val late = LatencyProbeReducer.publishBatch(
            cancelled,
            11,
            "root",
            "wifi-1",
            mapOf("leaf" to LatencyProbeState.Success(LatencySample(99, 30_000, "wifi-1"))),
            emptyMap(),
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
                        item.copy(
                            relay = LatencyProbeState.Success(sample),
                            icmp = LatencyProbeState.Failed(LatencyFailure.NoResponse),
                        )
                    } else {
                        item
                    }
                })
            },
        )
        assertEquals(LatencyProbeState.Stale(sample), stale.single().items.first().relay)
        assertEquals(
            LatencyProbeState.Failed(LatencyFailure.NoResponse),
            stale.single().items.first().icmp,
        )
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
