package io.github.zapretkvn.android.importer

import android.net.Network
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Где оборвалась загрузка подписки и кто виноват. Те же правила и слова, что в Windows
 * (`xray_fluent/network/fetch_diagnosis.py`): клиент не знает виновника точно и выводит
 * его из улик — стадий DNS → TCP → TLS сервера подписки и нейтрального контрольного узла
 * по каждому маршруту, прямому и через VPN.
 *
 * Адрес в журнал и в сообщения попадает только маской ([FetchMasking]); путь и токен
 * ссылки не пишутся никогда.
 */
enum class Culprit(val headline: String) {
    Server("сервер подписки (бот)"),
    Refused("сервер подписки отказал приложению (бан клиента)"),
    Block("блокировка сети или провайдера"),
    Vpn("VPN"),
    Network("интернет-подключение телефона"),
}

object FetchMasking {
    /** `url.gl***.com` — виден облик адреса, но не весь адрес. */
    fun maskHost(host: String): String {
        val text = host.trim().trim('[', ']').lowercase()
        if (text.isEmpty()) return ""
        if (text.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
            val parts = text.split('.')
            return "${parts[0]}.${parts[1]}.*.*"
        }
        if (':' in text) return text.substringBefore(':') + ":****"
        val labels = text.split('.')
        if (labels.size == 1) return labels[0].take(2) + "***"
        val masked = labels.dropLast(1).map { if (it.length <= 3) it else it.take(2) + "***" }
        return (masked + labels.last()).joinToString(".")
    }

    fun fingerprint(host: String): String =
        MessageDigest.getInstance("SHA-256").digest(host.trim().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(6)

    /** Метка сервера для журнала: маска, порт и отпечаток (`url.gl***.com:443#3fa9c2`). */
    fun hostTag(host: String, port: Int = 443): String = "${maskHost(host)}:$port#${fingerprint(host)}"

    /** Заменить адрес сервера в чужом тексте ошибки его меткой. */
    fun redactHost(text: String, host: String): String =
        if (host.isBlank()) text else text.replace(host, "${maskHost(host)}#${fingerprint(host)}", ignoreCase = true)
}

/** Итог проверки одного узла по одному маршруту. [failed] пуст, если все стадии пройдены. */
data class HostProbe(
    val ok: Boolean,
    val failed: String = "",   // "dns" | "tcp" | "tls" | "http"
    val how: String = "",      // "timeout" | "reset" | "refused" | "dns" | "cert" | "tls_error" | "error"
    val timings: List<Pair<String, Int>> = emptyList(),
    val detail: String = "",
) {
    fun timingsText(): String =
        timings.joinToString(" · ") { "${it.first} ${it.second} мс" }.ifEmpty { "нет замеров" }
}

data class Evidence(
    val host: String,
    val port: Int,
    val direct: Pair<HostProbe, HostProbe>?,   // (сервер, контроль)
    val vpn: Pair<HostProbe, HostProbe>?,      // null — VPN не подключён
    /** Есть ли в приложении хоть один профиль: без него VPN не включить и совет «подключитесь» тупиковый. */
    val serversAvailable: Boolean = true,
)

data class Diagnosis(
    val culprit: Culprit,
    val certain: Boolean,
    val where: String,
    val advice: String = "",
    val logLines: List<String> = emptyList(),
    /** Маршрут через VPN не пробовали, потому что VPN не подключён: такой отказ не окончательный. */
    val vpnUntried: Boolean = false,
) {
    fun message(): String {
        val who = if (certain) culprit.headline else "${culprit.headline} (вероятно)"
        return buildString {
            append("Не удалось загрузить подписку. Виноват: $who. Где оборвалось: $where.")
            if (advice.isNotBlank()) append(' ').append(advice)
        }
    }
}

object FetchJudge {
    const val CONTROL_HOST = "www.gstatic.com"

    /** Совет, когда сервер подписки режут по имени, а профили в приложении есть. */
    const val ADVICE_CONNECT_THEN_REPEAT =
        "Подключитесь к любому серверу: подписка запомнена и загрузится через него сама, пока приложение открыто. " +
            "Если и так не откроется, сервер подписки выключен."

    /** Совет без единого профиля: сначала нужен хотя бы один одиночный ключ, а не подписка. */
    const val ADVICE_NO_SERVERS =
        "Сначала нужен хотя бы один сервер, через который можно дойти до сервера подписки: добавьте любой " +
            "одиночный ключ (ссылку vless://, hysteria2:// и подобные, не подписку; на вкладке «Серверы» кнопка " +
            "«Буфер») и подключитесь: запомненная подписка загрузится через этот сервер сама, пока приложение открыто."

    fun describeFailure(probe: HostProbe, route: String): String {
        if (probe.failed.isEmpty()) return "$route соединение и TLS проходят, но ответа на запрос нет"
        return when (probe.failed) {
            "dns" -> "$route не нашёлся адрес сервера (DNS)"
            "tcp" -> when (probe.how) {
                "refused" -> "$route сервер отклонил TCP-соединение"
                "reset" -> "$route TCP-соединение оборвали сразу"
                else -> "$route TCP-соединение не устанавливается: сервер молчит"
            }
            "tls" -> when (probe.how) {
                "cert" -> "$route сертификат сервера не прошёл проверку (подмена или просрочен)"
                "reset" -> "$route TCP есть, но рукопожатие TLS оборвали сразу — типичная блокировка по имени сервера (SNI)"
                "timeout" -> "$route TCP есть, но рукопожатие TLS повисло — типичная блокировка по имени сервера (SNI)"
                else -> "$route TCP есть, но защищённое соединение (TLS) не установилось"
            }
            "http" -> if (probe.how == "timeout") {
                "$route соединение и TLS проходят, но на запрос сайта ответа нет: сервер молчит или не принимает запросы с этого адреса"
            } else {
                "$route соединение и TLS проходят, но после запроса сайта соединение оборвалось"
            }
            else -> "$route соединение с сервером не удалось (${probe.failed})"
        }
    }

    private fun logLines(ev: Evidence, verdict: Culprit, certain: Boolean): List<String> {
        val tag = FetchMasking.hostTag(ev.host, ev.port)
        val lines = mutableListOf<String>()
        for ((label, pair) in listOf("напрямую" to ev.direct, "через VPN" to ev.vpn)) {
            if (pair == null) {
                lines += "diagnosis $tag: $label: не пробовали (VPN не подключён)"
                continue
            }
            for ((name, probe) in listOf("сервер" to pair.first, "контроль" to pair.second)) {
                val state = if (probe.ok) "ok" else
                    "сбой на ${probe.failed} (${probe.how}${if (probe.detail.isNotEmpty()) ", " + probe.detail else ""})"
                lines += "diagnosis $tag: $label, $name: $state; ${probe.timingsText()}"
            }
        }
        lines += "diagnosis $tag: вердикт ${verdict.name.lowercase()}${if (certain) "" else " (вероятно)"}"
        return lines
    }

    /** Вывести виновника из проб. Чистая функция: проба сети здесь не делается. */
    fun judge(ev: Evidence): Diagnosis {
        val (dTarget, dControl) = requireNotNull(ev.direct) { "нет проб напрямую" }
        val vTarget = ev.vpn?.first
        val vControl = ev.vpn?.second
        val vpnNote = if (ev.vpn == null) " Через VPN не пробовали: он не подключён." else ""

        fun done(culprit: Culprit, certain: Boolean, where: String, advice: String = "") =
            Diagnosis(culprit, certain, where, advice, logLines(ev, culprit, certain), vpnUntried = ev.vpn == null)

        if (vControl != null && !vControl.ok) {
            return if (!dControl.ok) {
                done(
                    Culprit.Network, true,
                    describeFailure(dControl, "напрямую") + "; через VPN то же: " + describeFailure(vControl, "через VPN"),
                    "Проверьте интернет на телефоне.",
                )
            } else {
                done(
                    Culprit.Vpn, true, describeFailure(vControl, "через VPN"),
                    "Контрольный сайт напрямую открывается, через VPN — нет: смените сервер VPN или отключите его и обновите подписку напрямую.",
                )
            }
        }
        if (!dControl.ok) {
            return when {
                vTarget != null && vTarget.ok -> done(
                    Culprit.Block, true, describeFailure(dTarget, "напрямую"),
                    "Через VPN сервер подписки открывается: обновите подписку при включённом VPN.",
                )
                vTarget != null -> done(Culprit.Server, false, describeFailure(vTarget, "через VPN"))
                else -> done(
                    Culprit.Network, true, describeFailure(dControl, "напрямую"),
                    "Контрольный сайт тоже не открывается: проверьте интернет.$vpnNote",
                )
            }
        }
        if (!dTarget.ok) {
            val where = describeFailure(dTarget, "напрямую")
            return when {
                vTarget != null && vTarget.ok -> done(
                    Culprit.Block, true, where,
                    "Через VPN сервер подписки открывается: обновите подписку при включённом VPN.",
                )
                vTarget != null && vTarget.failed == "http" -> done(
                    Culprit.Server, true, where + "; " + describeFailure(vTarget, "через VPN"),
                    "Напрямую путь закрыт, а через VPN соединение с сервером есть, но на запрос он не отвечает: " +
                        "возможно, сервер подписки не пускает адрес этого VPN-сервера. Подключитесь к другому серверу " +
                        "и обновите подписку или возьмите ссылку подписки заново.",
                )
                vTarget != null -> done(
                    Culprit.Server, true, where + "; " + describeFailure(vTarget, "через VPN"),
                    "Сервер недоступен и напрямую, и через VPN.",
                )
                else -> done(
                    Culprit.Block, false, where,
                    if (ev.serversAvailable) ADVICE_CONNECT_THEN_REPEAT + vpnNote else ADVICE_NO_SERVERS,
                )
            }
        }
        return done(
            Culprit.Server, false, "соединение и TLS проходят, но ответа на запрос нет",
            "Сервер принимает соединение и молчит; провайдер мог резать сам запрос. Проверьте ссылку подписки у провайдера.$vpnNote",
        )
    }

    /** Виновник для ответа сервера с кодом: сервер ответил — сеть ни при чём. */
    fun blameForHttp(status: Int?, text: String = ""): Pair<Culprit, String> {
        val lowered = text.lowercase()
        return when {
            status == 401 || status == 403 || "hwid" in lowered || "лимит устройств" in lowered ||
                "идентификатор устройства" in lowered ->
                Culprit.Refused to "Сервер подписки ответил отказом клиенту"
            status == 404 || status == 410 ->
                Culprit.Server to "Сервер подписки (бот) не знает эту ссылку: она недействительна или подписка удалена"
            status == 429 -> Culprit.Server to "Сервер подписки (бот) ограничил частоту запросов"
            status != null && status >= 500 -> Culprit.Server to "Сервер подписки (бот) сломан или перегружен"
            else -> Culprit.Server to "Сервер подписки ответил, но тело не подошло"
        }
    }

    fun answeredMessage(status: Int?, text: String): String {
        val (culprit, phrase) = blameForHttp(status, text)
        return "Не удалось загрузить подписку. Виноват: ${culprit.headline}. $phrase. Ответ сервера: $text"
    }
}

/** Пробы стадий DNS → TCP → TLS по выбранной сети (null — сеть по умолчанию). */
object FetchProbes {
    const val PROBE_TIMEOUT_MILLIS = 6_000

    /** После рукопожатия стадии «настоящий запрос» всегда достаётся хотя бы столько времени. */
    const val HTTP_MIN_BUDGET_MILLIS = 2_500
    private const val PROBE_USER_AGENT = "Mozilla/5.0 (Linux; Android) ZapretKVN-check"

    /** Контрольный узел отвечает на generate_204 мгновенно; у сервера подписки берётся корень сайта, путь и токен ссылки в пробу не попадают. */
    internal fun httpPath(host: String): String = if (host == FetchJudge.CONTROL_HOST) "/generate_204" else "/"

    internal fun httpRequest(host: String): ByteArray =
        ("GET ${httpPath(host)} HTTP/1.1\r\nHost: $host\r\nUser-Agent: $PROBE_USER_AGENT\r\n" +
            "Accept: */*\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)

    /** Любой ответ, хоть 404, значит «сервер отвечает на HTTP»; молчание или не-HTTP считаются сбоем. */
    internal fun checkHttpHead(head: ByteArray) {
        if (head.isEmpty()) throw EOFException("соединение закрыто без ответа")
        if (!String(head, Charsets.ISO_8859_1).startsWith("HTTP/")) throw IOException("ответ не похож на HTTP")
    }

    fun classify(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            if (current is CertificateException || current is SSLPeerUnverifiedException) return "cert"
            current = current.cause
        }
        val text = error.message.orEmpty().lowercase()
        return when {
            error is UnknownHostException -> "dns"
            error is SocketTimeoutException || "timed out" in text -> "timeout"
            error is ConnectException && ("refused" in text || "econnrefused" in text) -> "refused"
            error is EOFException || "reset" in text || "connection closed" in text || "broken pipe" in text -> "reset"
            error is SSLHandshakeException || error is SSLException -> "tls_error"
            error is SocketException -> "error"
            else -> "error"
        }
    }

    fun probe(network: Network?, host: String, port: Int = 443, timeoutMillis: Int = PROBE_TIMEOUT_MILLIS): HostProbe {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        fun remaining() = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
        val timings = mutableListOf<Pair<String, Int>>()
        var started = System.nanoTime()
        fun elapsed() = ((System.nanoTime() - started) / 1_000_000L).toInt()

        val addresses = try {
            if (network != null) network.getAllByName(host).toList() else InetAddress.getAllByName(host).toList()
        } catch (error: IOException) {
            return HostProbe(false, "dns", "dns", timings, error.javaClass.simpleName)
        }
        timings += "dns" to elapsed()

        started = System.nanoTime()
        var socket: Socket? = null
        var last: Throwable = IOException("нет адресов")
        for (address in addresses.take(3)) {
            val left = remaining()
            if (left <= 0) {
                last = SocketTimeoutException("общий срок пробы вышел")
                break
            }
            val candidate = network?.socketFactory?.createSocket() ?: Socket()
            try {
                candidate.connect(InetSocketAddress(address, port), left)
                socket = candidate
                break
            } catch (error: IOException) {
                runCatching { candidate.close() }
                last = error
            }
        }
        if (socket == null) return HostProbe(false, "tcp", classify(last), timings, last.javaClass.simpleName)
        timings += "tcp" to elapsed()

        started = System.nanoTime()
        val tls: SSLSocket
        try {
            val left = remaining().coerceAtLeast(100)
            socket.soTimeout = left
            tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, host, port, true) as SSLSocket
            tls.soTimeout = left
            tls.startHandshake()
        } catch (error: IOException) {
            runCatching { socket.close() }
            return HostProbe(false, "tls", classify(error), timings, error.javaClass.simpleName)
        }
        timings += "tls" to elapsed()

        // Рукопожатие ещё не значит, что сервер отвечает: один настоящий запрос по готовому соединению.
        started = System.nanoTime()
        try {
            tls.soTimeout = remaining().coerceAtLeast(HTTP_MIN_BUDGET_MILLIS)
            tls.outputStream.write(httpRequest(host))
            tls.outputStream.flush()
            val buffer = ByteArray(256)
            var head = ByteArray(0)
            while (head.size < 12) {
                val read = tls.inputStream.read(buffer)
                if (read < 0) break
                head += buffer.copyOf(read)
            }
            checkHttpHead(head)
        } catch (error: IOException) {
            return HostProbe(false, "http", classify(error), timings, error.javaClass.simpleName)
        } finally {
            runCatching { tls.close() }
        }
        timings += "http" to elapsed()
        return HostProbe(true, timings = timings)
    }

    /** Сервер и контроль по каждому маршруту — параллельно. */
    fun collect(
        host: String,
        port: Int,
        directNetwork: Network?,
        vpnNetwork: Network?,
        prober: (Network?, String, Int) -> HostProbe = { network, probeHost, probePort -> probe(network, probeHost, probePort) },
        serversAvailable: Boolean = true,
    ): Evidence {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val direct = listOf(
                pool.submit(Callable { prober(directNetwork, host, port) }),
                pool.submit(Callable { prober(directNetwork, FetchJudge.CONTROL_HOST, 443) }),
            )
            val vpn = vpnNetwork?.let {
                listOf(
                    pool.submit(Callable { prober(it, host, port) }),
                    pool.submit(Callable { prober(it, FetchJudge.CONTROL_HOST, 443) }),
                )
            }
            return Evidence(
                host, port,
                direct[0].get() to direct[1].get(),
                vpn?.let { it[0].get() to it[1].get() },
                serversAvailable,
            )
        } finally {
            pool.shutdownNow()
        }
    }
}
