package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.network.NetworkAutomationDecision
import io.github.zapretkvn.android.network.NetworkAutomationPolicy
import io.github.zapretkvn.android.network.NetworkAutomationSettings
import io.github.zapretkvn.android.network.NetworkPauseReason
import io.github.zapretkvn.android.network.TrustedWifiName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkAutomationPolicyTest {
    @Test
    fun `disabled automation always keeps user-started vpn running`() {
        assertEquals(
            NetworkAutomationDecision.RunVpn,
            decide(NetworkAutomationSettings(enabled = false, useVpnOnWifi = false), "wifi"),
        )
    }

    @Test
    fun `every transport can be enabled or paused independently`() {
        val settings = NetworkAutomationSettings(
            enabled = true,
            useVpnOnWifi = false,
            useVpnOnCellular = false,
            useVpnOnEthernet = false,
            useVpnOnOther = false,
        )

        assertPause(NetworkPauseReason.WifiDisabled, decide(settings, "wifi"))
        assertPause(NetworkPauseReason.CellularDisabled, decide(settings, "cellular"))
        assertPause(NetworkPauseReason.EthernetDisabled, decide(settings, "ethernet"))
        assertPause(NetworkPauseReason.OtherDisabled, decide(settings, "satellite"))
    }

    @Test
    fun `trusted wifi pauses only on an exact recognized ssid`() {
        val settings = NetworkAutomationSettings(
            enabled = true,
            trustedWifiSsids = setOf("Office", "Home"),
        )

        assertPause(NetworkPauseReason.TrustedWifi, decide(settings, "wifi", "Office"))
        assertEquals(NetworkAutomationDecision.RunVpn, decide(settings, "wifi", "office"))
        assertEquals(NetworkAutomationDecision.RunVpn, decide(settings, "wifi", null))
    }

    @Test
    fun `trusted wifi list can be disabled without deleting it`() {
        val settings = NetworkAutomationSettings(
            enabled = true,
            pauseOnTrustedWifi = false,
            trustedWifiSsids = setOf("Office"),
        )

        assertEquals(NetworkAutomationDecision.RunVpn, decide(settings, "wifi", "Office"))
    }

    @Test
    fun `paused automation waits for a real network instead of guessing a transport`() {
        val result = NetworkAutomationPolicy.decide(
            settings = NetworkAutomationSettings(enabled = true),
            networkAvailable = false,
            transport = "other",
            wifiSsid = null,
        )

        assertEquals(NetworkAutomationDecision.WaitForNetwork, result)
    }

    @Test
    fun `wifi names accept android quoted and hex forms but reject invalid values`() {
        assertEquals("Office", TrustedWifiName.normalize("  \"Office\"  "))
        assertEquals("a".repeat(64), TrustedWifiName.normalize("a".repeat(64)))
        assertNull(TrustedWifiName.normalize(""))
        assertNull(TrustedWifiName.normalize("ю".repeat(17)))
        assertNull(TrustedWifiName.normalize("x".repeat(64)))
    }

    private fun decide(
        settings: NetworkAutomationSettings,
        transport: String,
        wifiSsid: String? = null,
    ): NetworkAutomationDecision = NetworkAutomationPolicy.decide(
        settings = settings,
        networkAvailable = true,
        transport = transport,
        wifiSsid = wifiSsid,
    )

    private fun assertPause(
        reason: NetworkPauseReason,
        decision: NetworkAutomationDecision,
    ) {
        assertEquals(NetworkAutomationDecision.PauseVpn(reason), decision)
    }
}
