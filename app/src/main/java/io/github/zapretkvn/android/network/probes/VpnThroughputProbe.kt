package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.network.VpnNetworkProvider
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Реальная скорость загрузки через туннель для «умной проверки».
 *
 * Короткая ограниченная загрузка (до [MAX_BYTES] или [MAX_TRANSFER_MILLIS])
 * того же тестового файла, что у ПК-клиента (SPEED_TEST_DEFAULT_URL): не
 * российский адрес, иначе RU-наборы маршрутизировали бы его напрямую мимо
 * туннеля; не DoH и не сервис определения IP, которые политика узлов
 * отклоняет. Запрос идёт по маршрутам профиля через выбранный сервер.
 *
 * Каждый замер открывает новое соединение с `Connection: close`: иначе замер
 * после пробного переключения мог бы пойти по keep-alive соединению старого
 * сервера.
 */
class VpnThroughputProbe(
    private val vpnNetworks: VpnNetworkProvider,
) {
    /**
     * Байт в секунду; null — замер не удался (сеть, TLS, HTTP или не получено
     * ни байта). Это «неясно», а не «медленно»: переключения по нему нет.
     */
    suspend fun measure(): Long? {
        val network = try {
            withTimeout(NETWORK_WAIT_MILLIS) { vpnNetworks.awaitActive() }
        } catch (_: TimeoutCancellationException) {
            return null
        }
        if (runCatching { vpnNetworks.requireActive(network) }.isFailure) return null
        return withContext(Dispatchers.IO) {
            val connection = try {
                network.openConnection(URL(DOWNLOAD_URL)) as HttpsURLConnection
            } catch (_: IOException) {
                return@withContext null
            }
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Connection", "close")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.requestMethod = "GET"
                if (connection.responseCode != 200) return@withContext null
                connection.inputStream.use { input -> readRate(input) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Скорость считается с первого байта тела, без времени TLS-рукопожатия.
     * Если сервер начал отдавать, но затих до тайм-аута чтения, скорость по
     * уже полученным байтам и есть результат: сервер действительно медленный.
     */
    private suspend fun readRate(input: InputStream): Long? {
        val buffer = ByteArray(BUFFER_BYTES)
        val started = System.nanoTime()
        var total = 0L
        try {
            while (total < MAX_BYTES) {
                currentCoroutineContext().ensureActive()
                if (elapsedMillis(started) >= MAX_TRANSFER_MILLIS) break
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_BYTES - total).toInt())
                if (read < 0) break
                total += read
            }
        } catch (_: IOException) {
            // Тайм-аут чтения посреди загрузки — это результат, а не сбой замера.
        }
        return ThroughputRate.bytesPerSecondOrNull(total, elapsedMillis(started))
    }

    private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    internal companion object {
        /** Совпадает с SPEED_TEST_DEFAULT_URL ПК-клиента; читается не больше [MAX_BYTES]. */
        const val DOWNLOAD_URL = "https://fra.download.datapacket.com/100mb.bin"
        const val MAX_BYTES = 3L * 1024 * 1024
        const val MAX_TRANSFER_MILLIS = 6_000L
        const val NETWORK_WAIT_MILLIS = 5_000L
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 3_000
        const val BUFFER_BYTES = 32 * 1024
    }
}

internal object ThroughputRate {
    /** Ни одного байта — «неясно» (null), а не нулевая скорость. */
    fun bytesPerSecondOrNull(bytes: Long, elapsedMillis: Long): Long? =
        if (bytes <= 0) null else bytes * 1_000 / elapsedMillis.coerceAtLeast(1)
}
