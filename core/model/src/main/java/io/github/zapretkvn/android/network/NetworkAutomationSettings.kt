package io.github.zapretkvn.android.network

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
