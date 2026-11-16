package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.network.VpnNetworkProvider
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Итог ручного запроса внешнего IP. */
sealed interface ExternalIpLookup {
    data class Found(val address: String) : ExternalIpLookup

    /**
     * Сервис IP не ответил, а туннель жив: узел нарочно закрывает сервисы определения IP
     * (политика узлов Zapret VPN), поэтому адрес «скрыт сервером», а не «не удалось».
     */
    data object HiddenByServer : ExternalIpLookup

    data object Failed : ExternalIpLookup
}

/** Fetches the public address through the established VPN. This is not a latency probe. */
class VpnExternalIpProbe(
    private val vpnNetworks: VpnNetworkProvider,
    /** true — тот же туннель отвечает на generate_204 (см. [VpnLivenessProbe]). */
    private val tunnelAlive: suspend () -> Boolean = { false },
) {
    /** Адрес; при отказе сервиса IP — проверка туннеля отличает «скрыт сервером» от сбоя. */
    suspend fun lookup(): ExternalIpLookup {
        val address = attempt { fetch() }
        if (address != null) return ExternalIpLookup.Found(address)
        return if (attempt { tunnelAlive() } == true) ExternalIpLookup.HiddenByServer else ExternalIpLookup.Failed
    }

    /**
     * null — не удалось. Тайм-аут собственного withTimeout тоже CancellationException, но это
     * провал запроса, а не отмена вызывающего: узел, молча глотающий запрос к сервису IP,
     * раньше оставлял строку в «определяем…» навсегда.
     */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (timeout: TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

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
