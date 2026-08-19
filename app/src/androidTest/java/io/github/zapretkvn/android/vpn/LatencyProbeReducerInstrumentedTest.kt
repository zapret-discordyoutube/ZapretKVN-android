package io.github.zapretkvn.android.vpn

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyProbeReducerInstrumentedTest {
    @Test
    fun probeUpdatesRequireExactRequestAndCancellationRestoresPendingValues() {
        val controller = VpnController(ApplicationProvider.getApplicationContext())
        val generation = controller.nextGeneration()
        controller.publish(
            generation,
            VpnConnectionState.Connected("profile", "Profile", 1L),
        )
        val previous = LatencySample(21, 10_000, "wifi")
        controller.publishGroups(
            generation,
            listOf(
                RuntimeSelectorGroup(
                    tag = "root",
                    type = "selector",
                    selected = "leaf",
                    selectable = true,
                    items = listOf(
                        RuntimeOutboundItem(
                            tag = "leaf",
                            type = "vless",
                            endpoint = "vpn.example:443",
                            icmp = LatencyProbeState.Success(previous),
                        ),
                        RuntimeOutboundItem("nested", "selector", null),
                    ),
                ),
            ),
        )

        assertTrue(controller.beginLatencyProbe(generation, 7, "root", "wifi", setOf("leaf")))
        val running = controller.selectorGroups.value.single()
        assertEquals(1, running.probeProgress?.relayCompleted)
        assertEquals(1, running.probeProgress?.icmpCompleted)
        assertTrue(running.items.first().icmp is LatencyProbeState.Running)
        assertTrue(running.items.last().relay is LatencyProbeState.Unsupported)

        controller.publishLatencyBatch(
            generation,
            requestId = 6,
            groupTag = "root",
            networkIdentity = "wifi",
            relay = mapOf("leaf" to LatencyProbeState.Success(LatencySample(50, 20_000, "wifi"))),
        )
        assertTrue(controller.selectorGroups.value.single().items.first().relay is LatencyProbeState.Running)

        controller.publishLatencyBatch(
            generation,
            requestId = 7,
            groupTag = "root",
            networkIdentity = "wifi",
            relay = mapOf("leaf" to LatencyProbeState.Success(LatencySample(50, 20_000, "wifi"))),
        )
        assertTrue(controller.selectorGroups.value.single().items.first().relay is LatencyProbeState.Success)

        controller.cancelLatencyProbe(generation, 7, "root", "wifi")
        val cancelled = controller.selectorGroups.value.single()
        assertEquals(LatencyProbeState.Success(previous), cancelled.items.first().icmp)
        assertTrue(cancelled.items.first().relay is LatencyProbeState.Success)
        assertFalse(cancelled.probeProgress?.running == true)
    }

    @Test
    fun staleGenerationCannotUpdateCurrentProbe() {
        val controller = VpnController(ApplicationProvider.getApplicationContext())
        val oldGeneration = controller.nextGeneration()
        val currentGeneration = controller.nextGeneration()
        controller.publish(
            currentGeneration,
            VpnConnectionState.Connected("profile", "Profile", 1L),
        )
        controller.publishGroups(
            currentGeneration,
            listOf(
                RuntimeSelectorGroup(
                    "root",
                    "selector",
                    "leaf",
                    true,
                    listOf(RuntimeOutboundItem("leaf", "vless", "vpn.example:443")),
                ),
            ),
        )

        assertFalse(controller.beginLatencyProbe(oldGeneration, 1, "root", "wifi", setOf("leaf")))
        assertEquals(LatencyProbeState.NotTested, controller.selectorGroups.value.single().items.single().relay)
    }
}
