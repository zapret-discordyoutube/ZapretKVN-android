package io.github.zapretkvn.android.network

import java.nio.charset.StandardCharsets

enum class NetworkPauseReason {
    WifiDisabled,
    TrustedWifi,
    CellularDisabled,
    EthernetDisabled,
    OtherDisabled,
}

sealed interface NetworkAutomationDecision {
    data object RunVpn : NetworkAutomationDecision
    data object WaitForNetwork : NetworkAutomationDecision
    data class PauseVpn(val reason: NetworkPauseReason) : NetworkAutomationDecision
}

internal object NetworkAutomationPolicy {
    fun decide(
        settings: NetworkAutomationSettings,
        networkAvailable: Boolean,
        transport: String,
        wifiSsid: String?,
    ): NetworkAutomationDecision {
        if (!settings.enabled) return NetworkAutomationDecision.RunVpn
        if (!networkAvailable) return NetworkAutomationDecision.WaitForNetwork
        return when (transport) {
            "wifi" -> when {
                !settings.useVpnOnWifi ->
                    NetworkAutomationDecision.PauseVpn(NetworkPauseReason.WifiDisabled)
                settings.pauseOnTrustedWifi &&
                    wifiSsid != null &&
                    wifiSsid in settings.trustedWifiSsids ->
                    NetworkAutomationDecision.PauseVpn(NetworkPauseReason.TrustedWifi)
                else -> NetworkAutomationDecision.RunVpn
            }
            "cellular" -> if (settings.useVpnOnCellular) {
                NetworkAutomationDecision.RunVpn
            } else {
                NetworkAutomationDecision.PauseVpn(NetworkPauseReason.CellularDisabled)
            }
            "ethernet" -> if (settings.useVpnOnEthernet) {
                NetworkAutomationDecision.RunVpn
            } else {
                NetworkAutomationDecision.PauseVpn(NetworkPauseReason.EthernetDisabled)
            }
            else -> if (settings.useVpnOnOther) {
                NetworkAutomationDecision.RunVpn
            } else {
                NetworkAutomationDecision.PauseVpn(NetworkPauseReason.OtherDisabled)
            }
        }
    }
}

internal fun NetworkPauseReason.userMessage(): String = when (this) {
    NetworkPauseReason.WifiDisabled -> "KVN отключён для Wi‑Fi"
    NetworkPauseReason.TrustedWifi -> "KVN не нужен в доверенном Wi‑Fi"
    NetworkPauseReason.CellularDisabled -> "KVN отключён для мобильной сети"
    NetworkPauseReason.EthernetDisabled -> "KVN отключён для Ethernet"
    NetworkPauseReason.OtherDisabled -> "KVN отключён для этой сети"
}
