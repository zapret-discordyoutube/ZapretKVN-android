package io.github.zapretkvn.android.clientreports

import android.net.Network
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import io.github.zapretkvn.clientreports.ReportCheck
import io.github.zapretkvn.clientreports.ReportChecks
import io.github.zapretkvn.networkbootstrap.AndroidBootstrapDnsResolver
import io.github.zapretkvn.networkbootstrap.BootstrapFailureCode
import io.github.zapretkvn.networkbootstrap.BootstrapFailureException
import io.github.zapretkvn.networkbootstrap.TrustedDnsOutcome
import io.github.zapretkvn.networkbootstrap.TrustedDohResolver
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Что проверять: сервер, как он записан в профиле. */
internal data class ProbeTarget(
    val host: String,
    val port: Int,
    /** Имя TLS (server_name); null — имя из [host], если это не IP. */
    val serverName: String?,
    val usesTls: Boolean,
    val udp: Boolean,
    /** Для отчёта (schema 2/3): тип outbound, транспорт и защита, как в профиле. */
    val protocol: String = "",
    val transport: String? = null,
    val security: String? = null,
    /** Schema 3 `carrier`: по чему идёт первый шаг ядра к серверу. */
    val carrier: String = if (udp) "udp" else "tcp",
)

/** Итог проверки пути: пробы и адрес, который проверялся (`server_ip` отчёта, если запуск его не назвал). */
internal data class PathFacts(val checks: Map<String, ReportCheck> = emptyMap(), val probedAddress: String? = null)

/**
 * Проверка пути после отказа (schema 3, решения владельца 19.11.2026): только факты, решает vpnbot.
 *
 * Владелец трижды просил, чтобы на проводе её не было заметно («always do it randomly so TSPU doesn't notice, and not
 * make unnecessary requests to the servers»; «make sure there are no unnecessary checks that could trigger DPI»;
 * «check as rarely as possible and only if it's really necessary»), поэтому:
 * - никаких самодельных пакетов: TLS-пробы идут через системный TLS Android (тот же ClientHello, что у обычных
 *   приложений), никогда не через свой, который стал бы отпечатком приложения; пробы QUIC нет;
 * - лестница минимальная: каждая ступень — только пока она может изменить ответ; ничего, когда код ядра уже доказал,
 *   что сервер ответил ([ReportChecks.needsPathCheck]); у UDP-строк сервер не трогается вовсе;
 * - редко (раз в сутки на сервер, раз в 10 минут на установку — решает [ClientReporter]);
 * - нейтральные имена каждый раз случайные, между пробами случайные паузы.
 *
 * Всё мимо VPN по основной сети. Лестница та же, что у Windows `client_reports/probe.py`:
 * control_front; молчит → control_neutral и стоп. Адрес, которым пользовался запуск, иначе dns_doh, а dns_system
 * (имя сервера открытым текстом) — только если DoH не ответил. TCP-строки: tcp_target; молчит и порт не 443 →
 * tcp_443; ответил и есть TLS → tls_real; нет ответа → tls_neutral.
 */
internal class BlockProbe(
    private val physical: () -> Network?,
    /** Наши имена пула (фронт в России): контроль сети. */
    private val frontHosts: () -> List<String>,
    private val random: Random,
    private val system: AndroidBootstrapDnsResolver = AndroidBootstrapDnsResolver(trusted = null),
    private val trusted: TrustedDohResolver = TrustedDohResolver(),
) {
    suspend fun run(target: ProbeTarget, knownAddress: String? = null): PathFacts = withContext(Dispatchers.IO) {
        val network = physical() ?: return@withContext PathFacts()
        val found = LinkedHashMap<String, ReportCheck>()
        val front = frontHosts().shuffled(random).take(3).firstNotNullOfOrNull { host ->
            runCatching { network.getAllByName(host).firstOrNull { it is Inet4Address } }.getOrNull()
        }
        found["control_front"] = timed { front?.let { tcp(network, it, 443) } ?: ReportChecks.UNKNOWN }
        if (found.getValue("control_front").result != ReportChecks.ANSWERED) {
            gap()
            val neutral = NEUTRAL_NAMES.random(random)
            found["control_neutral"] = timed { tlsByName(network, neutral) }
            return@withContext PathFacts(found)
        }
        val address = literalAddress(target.host)
            ?: knownAddress?.let(::literalAddress)
            ?: resolve(network, target.host, found)
            ?: return@withContext PathFacts(found)
        val probed = address.hostAddress
        // UDP-строка: рукопожатие ядра уже было пробой сервера; лишний SYN к нему ничего не добавит.
        if (target.udp) return@withContext PathFacts(found, probed)

        gap()
        found["tcp_target"] = timed { tcp(network, address, target.port) }
        val tcpTarget = found.getValue("tcp_target").result
        if (tcpTarget != ReportChecks.ANSWERED) {
            if ((tcpTarget == ReportChecks.TIMEOUT || tcpTarget == ReportChecks.UNKNOWN) && target.port != 443) {
                gap()
                found["tcp_443"] = timed { tcp(network, address, 443) }
            }
            return@withContext PathFacts(found, probed)
        }
        val name = (target.serverName ?: target.host).takeIf { it.isNotBlank() && literalAddress(it) == null }
        if (!target.usesTls || name == null) return@withContext PathFacts(found, probed)
        gap()
        found["tls_real"] = timed { tls(network, address, target.port, name) }
        if (found.getValue("tls_real").result != ReportChecks.ANSWERED) {
            gap()
            val neutral = NEUTRAL_NAMES.random(random)
            found["tls_neutral"] = timed { tls(network, address, target.port, neutral) }
        }
        PathFacts(found, probed)
    }

    /** DoH сначала (шифрованный); системный резолвер, который шлёт имя сервера открытым текстом, — только без ответа DoH. */
    private suspend fun resolve(network: Network, host: String, found: MutableMap<String, ReportCheck>): InetAddress? {
        gap()
        var started = SystemClock.elapsedRealtime()
        var doh: Set<String> = emptySet()
        val dohResult = when (val outcome = runCatching { trusted.resolve(network, host) }.getOrNull()) {
            is TrustedDnsOutcome.Addresses -> {
                doh = outcome.value.mapNotNull { it.hostAddress }.toSet()
                if (doh.isEmpty()) ReportChecks.UNKNOWN else ReportChecks.ANSWERED
            }
            TrustedDnsOutcome.NameMissing -> ReportChecks.NXDOMAIN
            else -> ReportChecks.UNKNOWN
        }
        found["dns_doh"] = ReportCheck(dohResult, ReportChecks.clampMillis(SystemClock.elapsedRealtime() - started))
        pickAddress(doh)?.let { return it }
        if (dohResult == ReportChecks.NXDOMAIN) return null
        gap()
        started = SystemClock.elapsedRealtime()
        var addresses: Set<String> = emptySet()
        val systemResult = try {
            addresses = system.resolveSystemOnly(network, host, noCacheLookup = true).mapNotNull { it.hostAddress }.toSet()
            if (addresses.isEmpty()) ReportChecks.NXDOMAIN else ReportChecks.ANSWERED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BootstrapFailureException) {
            when (failure.reason) {
                BootstrapFailureCode.DnsNameNotFound, BootstrapFailureCode.DnsEmptyAnswer -> ReportChecks.NXDOMAIN
                else -> ReportChecks.TIMEOUT
            }
        } catch (_: Throwable) {
            ReportChecks.UNKNOWN
        }
        found["dns_system"] = ReportCheck(systemResult, ReportChecks.clampMillis(SystemClock.elapsedRealtime() - started))
        return pickAddress(addresses)
    }

    private inline fun timed(probe: () -> String): ReportCheck {
        val started = SystemClock.elapsedRealtime()
        val result = try {
            probe()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReportChecks.UNKNOWN
        }
        return ReportCheck(result, ReportChecks.clampMillis(SystemClock.elapsedRealtime() - started))
    }

    /** Случайная пауза между пробами: проверка не идёт ровной очередью. */
    private suspend fun gap() = delay(random.nextLong(GAP_MIN_MILLIS, GAP_MAX_MILLIS + 1))

    private fun tcp(network: Network, address: InetAddress, port: Int): String = try {
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
        }
        ReportChecks.ANSWERED
    } catch (error: IOException) {
        ProbeErrors.result(error, connected = false)
    }

    /** Рукопожатие с [name] на [address]:[port]; само соединение не установилось — unknown (TCP — своя проба). */
    private fun tls(network: Network, address: InetAddress, port: Int, name: String): String {
        val socket: Socket = try {
            network.socketFactory.createSocket().also { it.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS) }
        } catch (_: IOException) {
            return ReportChecks.UNKNOWN
        }
        return systemHello(socket, name, port)
    }

    /** TCP и TLS к нейтральному сайту по имени (control_neutral): ошибки соединения тоже считаются. */
    private fun tlsByName(network: Network, name: String): String {
        val address = runCatching { network.getAllByName(name).firstOrNull { it is Inet4Address } }.getOrNull()
            ?: return ReportChecks.UNKNOWN
        val socket: Socket = try {
            network.socketFactory.createSocket().also { it.connect(InetSocketAddress(address, 443), CONNECT_TIMEOUT_MILLIS) }
        } catch (error: IOException) {
            return ProbeErrors.result(error, connected = false)
        }
        return systemHello(socket, name, 443)
    }

    private fun pickAddress(values: Set<String>): InetAddress? {
        val parsed = values.mapNotNull(::literalAddress)
        return parsed.firstOrNull { it is Inet4Address } ?: parsed.firstOrNull()
    }

    /**
     * `flow`: до [FREEZE_READ_BYTES] через туннель; `b` — байты тела до остановки. null — туннеля нет (не запускалась).
     * Файл тот же, что у замера скорости: не российский адрес, иначе маршрут «Россия напрямую» увёл бы его мимо сервера.
     */
    suspend fun flow(vpn: Network?): ReportCheck? = withContext(Dispatchers.IO) {
        val network = vpn ?: return@withContext null
        val started = SystemClock.elapsedRealtime()
        fun done(result: String, bytes: Long) = ReportCheck(result, ReportChecks.clampMillis(SystemClock.elapsedRealtime() - started), bytes)
        val connection = try {
            network.openConnection(URL(FREEZE_URL)) as HttpsURLConnection
        } catch (_: IOException) {
            return@withContext done(ReportChecks.UNKNOWN, 0)
        }
        var total = 0L
        try {
            connection.connectTimeout = FREEZE_CONNECT_MILLIS
            connection.readTimeout = FREEZE_STALL_MILLIS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Range", "bytes=0-${FREEZE_READ_BYTES - 1}")
            val status = connection.responseCode
            if (status != 200 && status != 206) return@withContext done(ReportChecks.UNKNOWN, 0)
            val buffer = ByteArray(8192)
            connection.inputStream.use { input ->
                while (total < FREEZE_READ_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
            }
            done(if (total >= FREEZE_READ_BYTES) ReportChecks.ANSWERED else ReportChecks.RESET, total)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            done(ProbeErrors.result(error, connected = true), total)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 4_000
        const val ANSWER_TIMEOUT_MILLIS = 5_000
        const val GAP_MIN_MILLIS = 150L
        const val GAP_MAX_MILLIS = 700L

        /** Нейтральные российские имена: открыты в любом режиме фильтрации, белые списки тоже. */
        val NEUTRAL_NAMES = listOf("ya.ru", "vk.com", "www.gosuslugi.ru", "ozon.ru", "www.wildberries.ru")

        const val FREEZE_URL = "https://fra.download.datapacket.com/100mb.bin"
        const val FREEZE_READ_BYTES = 64L * 1024
        const val FREEZE_CONNECT_MILLIS = 6_000
        const val FREEZE_STALL_MILLIS = 5_000

        fun literalAddress(value: String): InetAddress? {
            val text = value.trim().removePrefix("[").removeSuffix("]")
            val numeric = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(text) || (':' in text && text.all { it.isLetterOrDigit() || it == ':' || it == '.' })
            return if (numeric) runCatching { InetAddress.getByName(text) }.getOrNull() else null
        }

        /**
         * Рукопожатие системным TLS Android (никаких самодельных байтов: владелец 19.11.2026). Любое слово сервера —
         * сертификат (доверенный или нет, для этого имени или нет) или alert — ответ; тишина — timeout; RST или EOF
         * вместо ответа — reset. Сокет закрывается.
         */
        fun systemHello(socket: Socket, name: String, port: Int, timeoutMillis: Int = ANSWER_TIMEOUT_MILLIS): String =
            try {
                socket.soTimeout = timeoutMillis
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, name, port, true) as SSLSocket
                tls.soTimeout = timeoutMillis
                tls.use { it.startHandshake() }
                ReportChecks.ANSWERED
            } catch (error: IOException) {
                ProbeErrors.handshake(error)
            } finally {
                runCatching { socket.close() }
            }
    }
}

/** Ошибка пробы как результат отчёта: до установки соединения RST — refused, после — reset. */
internal object ProbeErrors {
    private val REFUSED_TEXT = listOf("ECONNREFUSED", "refused")
    private val RESET_TEXT = listOf("ECONNRESET", "reset", "EPIPE", "Broken pipe", "closed by peer", "Connection closed")

    fun result(error: Throwable, connected: Boolean): String {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        if (chain.any { it is SocketTimeoutException }) return ReportChecks.TIMEOUT
        val errno = chain.filterIsInstance<ErrnoException>().firstOrNull()?.errno
        val text = chain.joinToString(" ") { it.message.orEmpty() }
        return when {
            errno == OsConstants.ETIMEDOUT || "ETIMEDOUT" in text -> ReportChecks.TIMEOUT
            errno == OsConstants.ECONNREFUSED || REFUSED_TEXT.any { text.contains(it, ignoreCase = true) } ||
                chain.any { it is ConnectException && it.message.orEmpty().contains("refused", ignoreCase = true) } ->
                if (connected) ReportChecks.RESET else ReportChecks.REFUSED
            errno == OsConstants.ECONNRESET || chain.any { it is EOFException } ||
                RESET_TEXT.any { text.contains(it, ignoreCase = true) } ->
                if (connected) ReportChecks.RESET else ReportChecks.REFUSED
            else -> ReportChecks.UNKNOWN
        }
    }

    /** Ошибка рукопожатия: сертификат или alert от сервера — это ответ. */
    fun handshake(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        if (chain.any { it is CertificateException || it is java.security.cert.CertPathValidatorException }) return ReportChecks.ANSWERED
        val text = chain.joinToString(" ") { it.message.orEmpty() }
        if (chain.any { it is SSLException } && (text.contains("alert", ignoreCase = true) || "Trust anchor" in text)) {
            return ReportChecks.ANSWERED
        }
        return result(error, connected = true)
    }
}
