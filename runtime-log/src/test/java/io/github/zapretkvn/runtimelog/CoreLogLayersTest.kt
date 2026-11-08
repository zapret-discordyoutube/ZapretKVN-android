package io.github.zapretkvn.runtimelog

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreLogLayersTest {
    @Test
    fun `core lines are assigned to the layer they belong to`() {
        val cases = listOf(
            "INFO[0840] [3650259972 91ms] dns: exchanged example.org. NOERROR 197" to LogLayer.Dns,
            "INFO[0857] [2726465836 5.0s] dns/fallback[zapret-secure-dns]: resolve failed for server x" to LogLayer.Dns,
            "INFO[0820] [687738589 0ms] inbound/tun[tun-in]: inbound packet connection from 10.0.0.2:47188" to LogLayer.Tun,
            "INFO[0820] [687738589 0ms] outbound/hysteria2[DE]: outbound packet connection to 1.2.3.4:443" to LogLayer.Outbound,
            "ERROR[2951] [1 278ms] outbound/hysteria2[DE]: connection closed: received a stateless reset" to LogLayer.Outbound,
            "INFO[0820] [201528347 0ms] router: found package name: com.google.android.youtube" to LogLayer.Routing,
            "INFO[0001] [1 0ms] network: default interface changed to wlan0" to LogLayer.Network,
            "INFO[0001] [1 0ms] started in 1.2s" to LogLayer.Core,
        )
        cases.forEach { (line, layer) -> assertEquals(line, layer, CoreLogLayers.classify(line)) }
    }

    @Test
    fun `level comes from the name at the start of the line`() {
        assertEquals(2, CoreLogLayers.levelOf("ERROR[0200] [1 5s] dns: x"))
        assertEquals(6, CoreLogLayers.levelOf("TRACE[0830] [1 1m23s] connection: packet upload closed"))
        assertEquals(4, CoreLogLayers.levelOf("something else"))
    }
}
