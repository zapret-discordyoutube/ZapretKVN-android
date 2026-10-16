package io.github.zapretkvn.networkbootstrap

import android.net.Network
import java.io.IOException

/**
 * Способ отправить один DoH-запрос (RFC 8484, POST application/dns-message) к
 * резолверу по IP. Приложение подставляет браузерный транспорт ядра: TLS и HTTP/2
 * Chrome вместо Conscrypt и HTTP/1.1, по которым клиент узнаётся среди браузеров.
 */
fun interface DohTransport {
    /**
     * @return тело ответа резолвера.
     * @throws DohTransportUnavailable транспорта в этой сборке нет — запрос уходит прежним путём.
     */
    fun post(network: Network, server: String, query: ByteArray, timeoutMillis: Int): ByteArray
}

/** Транспорт недоступен целиком (а не отказал этот запрос): дальше — прежний путь. */
class DohTransportUnavailable(message: String) : IOException(message)

object DohTransports {
    /** Предпочтительный транспорт процесса; null — HttpsURLConnection по сети запроса. */
    @Volatile
    var preferred: DohTransport? = null
}
