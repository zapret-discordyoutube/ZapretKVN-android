package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.config.ManagedHealthProbe
import io.github.zapretkvn.android.network.VpnNetworkProvider
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface LivenessResult {
    data class Alive(val endpoint: String, val elapsedMillis: Long) : LivenessResult

    data class Dead(val detail: String) : LivenessResult

    /** VPN-сеть Android недоступна: о сервере это ничего не говорит. */
    data object Unknown : LivenessResult
}

/**
 * Подтверждение живости текущего сервера перед автоматической сменой.
 *
 * Запрос идёт тем же маршрутом, что health-check подключения: хосты
 * [ManagedHealthProbe] у пакета приложения направлены в селектор, то есть в
 * сервер, выбранный прямо сейчас. Только generate_204 — политика узлов
 * отклоняет DoH и сервисы определения IP.
 *
 * Эндпоинты перебираются подряд: второй запрос и есть повтор. После обрыва
 * QUIC Hysteria переподключается на следующем потоке, поэтому один
 * неудачный запрос сервер мёртвым не делает.
 */
class VpnLivenessProbe(
    private val vpnNetworks: VpnNetworkProvider,
) {
    internal suspend fun check(): LivenessResult {
        val network = withTimeoutOrNull(NETWORK_WAIT_MILLIS) { vpnNetworks.awaitActive() }
            ?: return LivenessResult.Unknown
        if (runCatching { vpnNetworks.requireActive(network) }.isFailure) return LivenessResult.Unknown
        val failures = mutableListOf<String>()
        val result = withTimeoutOrNull(BUDGET_MILLIS) {
            for (endpoint in ManagedHealthProbe.endpoints.take(MAX_ATTEMPTS)) {
                val started = System.nanoTime()
                val outcome = withContext(Dispatchers.IO) { request(network, endpoint.url) }
                if (outcome == null) {
                    return@withTimeoutOrNull LivenessResult.Alive(
                        endpoint.code,
                        (System.nanoTime() - started) / 1_000_000,
                    )
                }
                failures += "${endpoint.code}:$outcome"
            }
            null
        }
        return result ?: LivenessResult.Dead(failures.joinToString(" ").ifBlank { "timeout" })
    }

    /** null — ответ получен; иначе короткое имя причины отказа. */
    private fun request(network: android.net.Network, url: String): String? {
        val connection = try {
            network.openConnection(URL(url)) as HttpsURLConnection
        } catch (error: IOException) {
            return error.javaClass.simpleName
        }
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            val code = connection.responseCode
            if (code in 200..399) null else "http$code"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            error.javaClass.simpleName
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val NETWORK_WAIT_MILLIS = 3_000L
        const val CONNECT_TIMEOUT_MILLIS = 4_000
        const val READ_TIMEOUT_MILLIS = 4_000
        const val MAX_ATTEMPTS = 2
        const val BUDGET_MILLIS = 12_000L
    }
}
