package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.network.VpnNetworkProvider
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Fetches the public address through the established VPN. This is not a latency probe. */
class VpnExternalIpProbe(
    private val vpnNetworks: VpnNetworkProvider,
) {
    suspend fun fetch(): String = withTimeout(HTTPS_TIMEOUT_MILLIS.toLong()) {
        val vpnNetwork = vpnNetworks.awaitActive()
        vpnNetworks.requireActive(vpnNetwork)
        withContext(Dispatchers.IO) {
            val connection = vpnNetwork.openConnection(URL(EXTERNAL_IP_URL)) as HttpsURLConnection
            try {
                connection.connectTimeout = HTTPS_TIMEOUT_MILLIS
                connection.readTimeout = HTTPS_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Connection", "close")
                connection.requestMethod = "GET"
                val status = connection.responseCode
                if (status != 200) error("IP endpoint вернул HTTP $status.")
                val value = connection.inputStream.use(::readBounded)
                ExternalIpParser.parse(value) ?: error("IP endpoint вернул некорректный адрес.")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readBounded(input: InputStream): String {
        val buffer = ByteArray(MAX_EXTERNAL_IP_CHARS + 1)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        require(count <= MAX_EXTERNAL_IP_CHARS) { "IP endpoint вернул слишком длинный ответ." }
        return buffer.decodeToString(0, count)
    }

    private companion object {
        const val EXTERNAL_IP_URL = "https://api64.ipify.org"
        const val HTTPS_TIMEOUT_MILLIS = 5_000
        const val MAX_EXTERNAL_IP_CHARS = 45
    }
}
