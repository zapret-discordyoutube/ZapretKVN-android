package io.github.zapretkvn.android.network.probes

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Вид отказа одной HTTPS-пробы через VPN. Компактный [token] пишется в
 * technicalDetail VPN-200 и в диагностику, а общий [HttpsProbeVerdict]
 * выбирает понятное объяснение на главном экране.
 */
internal enum class HttpsProbeFailureKind(val token: String) {
    /** Соединение с TUN открылось, но ответа не было: туннель не пропускает данные. */
    Timeout("timeout"),

    /** TLS не сошёлся: сертификат проверочного сайта подменён или отвергнут. */
    Tls("tls"),

    /** Соединение сброшено или отклонено по пути. */
    Reset("reset"),

    /** Адрес проверочного сайта не разрешился через VPN. */
    Dns("dns"),

    /** Сайт ответил неожиданным HTTP-статусом. */
    HttpStatus("http"),

    Other("other"),
    ;

    companion object {
        fun of(error: Throwable): HttpsProbeFailureKind {
            val chain = generateSequence(error) { it.cause }.take(8).toList()
            return when {
                chain.any { it is SocketTimeoutException } -> Timeout
                chain.any { it is SSLException } -> Tls
                chain.any { it is UnknownHostException } -> Dns
                chain.any { it is ConnectException } -> Reset
                chain.any { it is SocketException && it.isResetMessage() } -> Reset
                chain.any { it.message.orEmpty().startsWith("HTTP ") } -> HttpStatus
                else -> Other
            }
        }

        private fun Throwable.isResetMessage(): Boolean {
            val text = message.orEmpty().lowercase()
            return "reset" in text || "refused" in text || "broken pipe" in text
        }
    }
}

/** Итог всех HTTPS-проб одной проверки: одинаковый вид отказа или смешанный. */
internal enum class HttpsProbeVerdict(val token: String) {
    Timeout("timeout"),
    Tls("tls"),
    Reset("reset"),
    Dns("dns"),
    HttpStatus("http"),
    Mixed("mixed"),
    ;

    companion object {
        const val DETAIL_PREFIX = "verdict="

        fun of(kinds: List<HttpsProbeFailureKind>): HttpsProbeVerdict {
            val distinct = kinds.toSet()
            return when (distinct.singleOrNull()) {
                HttpsProbeFailureKind.Timeout -> Timeout
                HttpsProbeFailureKind.Tls -> Tls
                HttpsProbeFailureKind.Reset -> Reset
                HttpsProbeFailureKind.Dns -> Dns
                HttpsProbeFailureKind.HttpStatus -> HttpStatus
                else -> Mixed
            }
        }

        /** Читает вердикт из начала technicalDetail; усечение хвоста ему не мешает. */
        fun parse(technicalDetail: String?): HttpsProbeVerdict? {
            val token = technicalDetail
                ?.trim()
                ?.takeIf { it.startsWith(DETAIL_PREFIX) }
                ?.removePrefix(DETAIL_PREFIX)
                ?.takeWhile { !it.isWhitespace() }
                ?: return null
            return entries.firstOrNull { it.token == token }
        }
    }
}
