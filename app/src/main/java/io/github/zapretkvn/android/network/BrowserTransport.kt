package io.github.zapretkvn.android.network

import android.net.Network
import android.net.VpnService
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.networkbootstrap.DohTransport
import io.github.zapretkvn.networkbootstrap.DohTransportUnavailable
import io.github.zapretkvn.networkbootstrap.ServerNameRegistry
import io.github.zapretkvn.networkbootstrap.TrustedDnsOutcome
import io.github.zapretkvn.networkbootstrap.TrustedDohResolver
import io.nekohasekai.libbox.BrowserResolver
import io.nekohasekai.libbox.BrowserSocketProtector
import io.nekohasekai.libbox.Libbox
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Прямые HTTPS-запросы приложения через сетевой стек Chromium в ядре (патч 0007):
 * TLS, HTTP/2 и порядок заголовков — как у Chrome, а не Conscrypt/HTTP 1.1, по
 * которым клиент узнаётся среди браузеров. Сокеты движка решаются [SocketGuard]:
 * пока VPN поднят, они защищены от собственного туннеля.
 */
internal object BrowserTransport {
    class Response(
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
        val truncated: Boolean,
        /** Адрес перенаправления: движок не следует за ним сам. */
        val redirectLocation: String?,
    ) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    /** Движка Chromium в этой сборке нет: вызывающий идёт прежним путём. */
    class UnavailableException(message: String) : IOException(message)

    private val guardInstalled = AtomicBoolean(false)

    /** Основная (не VPN) сеть для резолва имён; задаёт приложение при старте. */
    @Volatile
    var underlyingNetwork: () -> Network? = { null }

    /** Блокирующий запрос; [IOException] — отказ этого запроса, [UnavailableException] — транспорта нет. */
    fun fetch(
        url: String,
        method: String = "GET",
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        timeoutMillis: Int,
        maxBytes: Long,
    ): Response {
        installGuard()
        val request = buildJsonObject {
            put("method", method)
            put("url", url)
            if (headers.isNotEmpty()) {
                put(
                    "headers",
                    buildJsonArray { headers.forEach { (name, value) -> add(JsonArray(listOf(JsonPrimitive(name), JsonPrimitive(value)))) } },
                )
            }
            body?.let { put("body", Base64.getEncoder().encodeToString(it)) }
            put("timeout_ms", timeoutMillis)
            put("max_bytes", maxBytes)
        }
        val raw = try {
            Libbox.browserFetch(request.toString())
        } catch (error: Throwable) {
            throw UnavailableException("browserFetch: ${error.javaClass.simpleName}")
        }
        val reply = JsonConfig.parse(raw) as? JsonObject ?: throw IOException("browserFetch: bad reply")
        val error = (reply["error"] as? JsonPrimitive)?.contentOrNull
        if (error != null) {
            if ((reply["unavailable"] as? JsonPrimitive)?.booleanOrNull == true) throw UnavailableException(error)
            throw IOException(error)
        }
        return Response(
            status = (reply["status"] as? JsonPrimitive)?.intOrNull ?: throw IOException("browserFetch: no status"),
            headers = (reply["headers"] as? JsonArray).orEmpty().mapNotNull { pair ->
                val values = (pair as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                values?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
            },
            body = (reply["body"] as? JsonPrimitive)?.contentOrNull?.let(Base64.getDecoder()::decode) ?: ByteArray(0),
            truncated = (reply["truncated"] as? JsonPrimitive)?.booleanOrNull == true,
            redirectLocation = (reply["redirect_refused"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
        )
    }

    /**
     * Движок один на процесс и принимает обработчик сокетов только до первого
     * запроса — поэтому он ставится лениво здесь, а не при каждом запуске VPN.
     */
    private fun installGuard() {
        if (!guardInstalled.compareAndSet(false, true)) return
        try {
            Libbox.setBrowserSocketProtector(SocketGuard)
            Libbox.setBrowserResolver(NameResolver)
        } catch (error: Throwable) {
            throw UnavailableException("setBrowserSocketProtector: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Имена для движка: доверенный DoH, а не системный DNS провайдера. Без DoH —
     * системный резолв основной сети, но имя сервера уходит туда, только если это
     * выбранный сервер (как у DNS-транспорта ядра). Пустая строка — «имени нет».
     */
    object NameResolver : BrowserResolver {
        private val trusted = TrustedDohResolver()

        override fun resolve(host: String): String {
            val network = underlyingNetwork() ?: throw IOException("Нет основной сети для резолва.")
            val outcome = runBlocking {
                withTimeoutOrNull(TRUSTED_WINDOW_MILLIS) { trusted.resolve(network, host) }
            } ?: TrustedDnsOutcome.Unavailable
            return when (outcome) {
                is TrustedDnsOutcome.Addresses -> outcome.value.joinToString(",") { it.hostAddress.orEmpty() }
                TrustedDnsOutcome.NameMissing -> ""
                TrustedDnsOutcome.Unavailable -> {
                    if (!ServerNameRegistry.allowsSystemFallback(host)) throw IOException("DoH недоступен.")
                    network.getAllByName(host).joinToString(",", transform = InetAddress::getHostAddress)
                }
            }
        }

        private const val TRUSTED_WINDOW_MILLIS = 4_000L
    }

    /** Решение по каждому сокету движка: VPN поднят — защитить от туннеля, иначе как есть. */
    object SocketGuard : BrowserSocketProtector {
        @Volatile
        private var service: VpnService? = null

        fun attach(vpn: VpnService) {
            service = vpn
        }

        fun detach(vpn: VpnService) {
            if (service === vpn) service = null
        }

        override fun protect(fd: Int): Boolean = service?.protect(fd) ?: true
    }
}

/**
 * DoH к резолверу по IP через [BrowserTransport]; сеть запроса — основная (сокеты защищены).
 * GET `?dns=` (RFC 8484), как у самого Chrome: загрузка тела запроса в cronet-go роняла
 * процесс (SIGSEGV в UploadDataSink под нагрузкой GC), а GET тела не несёт. ID запроса
 * не обнуляется: ядро сверяет ID ответа со своим запросом.
 */
internal object BrowserDohTransport : DohTransport {
    override fun post(network: Network, server: String, query: ByteArray, timeoutMillis: Int): ByteArray {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(query)
        val response = try {
            BrowserTransport.fetch(
                url = "https://$server/dns-query?dns=$encoded",
                headers = listOf("accept" to "application/dns-message"),
                timeoutMillis = timeoutMillis,
                maxBytes = MAX_RESPONSE_BYTES,
            )
        } catch (unavailable: BrowserTransport.UnavailableException) {
            throw DohTransportUnavailable(unavailable.message.orEmpty())
        }
        if (response.status != 200) throw IOException("DoH HTTP ${response.status}")
        if (response.truncated) throw IOException("DoH response too large")
        return response.body
    }

    private const val MAX_RESPONSE_BYTES = 64L * 1024
}
