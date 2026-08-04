package io.github.zapretkvn.android.vpn

import java.nio.charset.StandardCharsets

data class NetworkAutomationSettings(
    val enabled: Boolean = false,
    val useVpnOnWifi: Boolean = true,
    val useVpnOnCellular: Boolean = true,
    val useVpnOnEthernet: Boolean = true,
    val useVpnOnOther: Boolean = true,
    val pauseOnTrustedWifi: Boolean = true,
    val trustedWifiSsids: Set<String> = emptySet(),
)

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

object TrustedWifiName {
    const val MAX_NETWORKS = 32
    private const val MAX_UTF8_BYTES = 32
    private const val MAX_HEX_CHARS = MAX_UTF8_BYTES * 2
    private val HEX_SSID = Regex("[0-9a-fA-F]{$MAX_HEX_CHARS}")

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        val unquoted = if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.lastIndex)
        } else {
            trimmed
        }
        if (unquoted.isBlank() || unquoted == "<unknown ssid>") return null
        val validLength = unquoted.toByteArray(StandardCharsets.UTF_8).size <= MAX_UTF8_BYTES ||
            HEX_SSID.matches(unquoted)
        return unquoted.takeIf { validLength }
    }
}

internal fun NetworkPauseReason.userMessage(): String = when (this) {
    NetworkPauseReason.WifiDisabled -> "KVN отключён для Wi‑Fi"
    NetworkPauseReason.TrustedWifi -> "KVN не нужен в доверенном Wi‑Fi"
    NetworkPauseReason.CellularDisabled -> "KVN отключён для мобильной сети"
    NetworkPauseReason.EthernetDisabled -> "KVN отключён для Ethernet"
    NetworkPauseReason.OtherDisabled -> "KVN отключён для этой сети"
}
