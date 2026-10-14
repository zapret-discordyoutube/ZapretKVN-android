package io.github.zapretkvn.android.config

import java.net.IDN
import java.util.Locale

data class DnsOverride(
    val enabled: Boolean = true,
    val hostname: String = DEFAULT_HOSTNAME,
    val ipv4Address: String = DEFAULT_IPV4_ADDRESS,
) {
    companion object {
        const val DEFAULT_HOSTNAME = "ntc.party"
        const val DEFAULT_IPV4_ADDRESS = "130.255.77.28"

        fun normalizedOrNull(
            hostname: String,
            ipv4Address: String,
            enabled: Boolean = true,
        ): DnsOverride? {
            val normalizedHost = normalizeHostname(hostname) ?: return null
            val normalizedAddress = normalizeIpv4(ipv4Address) ?: return null
            return DnsOverride(enabled, normalizedHost, normalizedAddress)
        }

        fun validationMessage(hostname: String, ipv4Address: String): String? = when {
            normalizeHostname(hostname) == null ->
                "Введите точный домен, например ntc.party."
            normalizeIpv4(ipv4Address) == null ->
                "Введите корректный IPv4-адрес."
            else -> null
        }

        private fun normalizeHostname(value: String): String? {
            val candidate = value.trim().trimEnd('.')
            if (candidate.isEmpty()) return null
            val ascii = runCatching {
                IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES)
            }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
            val labels = ascii.split('.')
            return ascii.takeIf {
                it.length in 3..253 && labels.size >= 2 &&
                    labels.all { label -> label.length in 1..63 }
            }
        }

        private fun normalizeIpv4(value: String): String? {
            val candidate = value.trim()
            val octets = candidate.split('.')
            if (octets.size != 4) return null
            if (octets.any { octet ->
                    octet.isEmpty() || octet.length > 3 ||
                        octet.any { !it.isDigit() } ||
                        (octet.length > 1 && octet.startsWith('0')) ||
                        octet.toIntOrNull() !in 0..255
                }
            ) {
                return null
            }
            return octets.joinToString(".")
        }
    }
}
