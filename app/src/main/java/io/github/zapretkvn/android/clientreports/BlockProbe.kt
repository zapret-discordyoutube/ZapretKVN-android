package io.github.zapretkvn.android.clientreports

import android.net.Network
import android.system.ErrnoException
import android.system.OsConstants
import io.github.zapretkvn.clientreports.DiagnosisFacts
import io.github.zapretkvn.clientreports.ProbeResult
import io.github.zapretkvn.clientreports.TlsProbe
import io.github.zapretkvn.networkbootstrap.AndroidBootstrapDnsResolver
import io.github.zapretkvn.networkbootstrap.BootstrapFailureCode
import io.github.zapretkvn.networkbootstrap.BootstrapFailureException
import io.github.zapretkvn.networkbootstrap.TrustedDnsOutcome
import io.github.zapretkvn.networkbootstrap.TrustedDohResolver
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Что проверять: сервер, как он записан в профиле. */
internal data class ProbeTarget(
    val host: String,
    val port: Int,
    /** Имя TLS (server_name); null — имя из [host], если это не IP. */
    val serverName: String?,
    val usesTls: Boolean,
    val udp: Boolean,
    /** Для отчёта (schema 2): тип outbound, транспорт и защита, как в профиле. */
    val protocol: String = "",
    val transport: String? = null,
    val security: String? = null,
)

/**
 * Сеть для [io.github.zapretkvn.clientreports.BlockDiagnosis]: собирает факты по ступеням, всё мимо
 * VPN по основной сети. Решения здесь нет — только пробы; каждая с коротким сроком, весь проход —
 * до ~40 с в фоне после отказа. Заморозка меряется отдельно ([freeze]) через туннель.
 */
internal class BlockProbe(
    private val physical: () -> Network?,
    /** Наши имена пула (фронт в России): контроль сети и «настоящее имя на рабочем адресе». */
    private val frontHosts: () -> List<String>,
    private val random: Random,
    private val system: AndroidBootstrapDnsResolver = AndroidBootstrapDnsResolver(trusted = null),
    private val trusted: TrustedDohResolver = TrustedDohResolver(),
) {
    suspend fun run(target: ProbeTarget): DiagnosisFacts = withContext(Dispatchers.IO) {
        val network = physical() ?: return@withContext DiagnosisFacts()
        val front = frontHosts().take(3).firstNotNullOfOrNull { host ->
            runCatching { network.getAllByName(host).firstOrNull { it is Inet4Address } }.getOrNull()
        }
        val control = front?.let { tcp(network, it, 443) } ?: ProbeResult.Unknown
        if (front == null || control != ProbeResult.Answered) return@withContext DiagnosisFacts(control = control)

        val literal = literalAddress(target.host)
        var systemDns: Set<String>? = null
        var dohDns: Set<String>? = null
        var nameMissing = false
        if (literal == null) {
            systemDns = try {
                system.resolveSystemOnly(network, target.host, noCacheLookup = true).mapNotNull { it.hostAddress }.toSet()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: BootstrapFailureException) {
                if (failure.reason == BootstrapFailureCode.DnsNameNotFound || failure.reason == BootstrapFailureCode.DnsEmptyAnswer) emptySet() else null
            } catch (_: Throwable) {
                null
            }
            when (val outcome = runCatching { trusted.resolve(network, target.host) }.getOrNull()) {
                is TrustedDnsOutcome.Addresses -> dohDns = outcome.value.mapNotNull { it.hostAddress }.toSet()
                TrustedDnsOutcome.NameMissing -> nameMissing = true
                else -> Unit
            }
        }
        val address = literal
            ?: dohDns?.let(::pickAddress)
            ?: systemDns?.let(::pickAddress)
        var facts = DiagnosisFacts(
            control = control,
            systemDns = systemDns,
            dohDns = dohDns,
            dohNameMissing = nameMissing,
            usesTls = target.usesTls,
            udpTransport = target.udp,
        )
        if (address == null) return@withContext facts
        facts = facts.copy(probedAddress = address.hostAddress)
        if (target.udp) {
            // Порт UDP по TCP не проверить: жив ли сам адрес, говорит 443.
            return@withContext facts.copy(tcpOtherPort = tcp(network, address, 443))
        }
        val tcpTarget = tcp(network, address, target.port)
        facts = facts.copy(tcpTarget = tcpTarget)
        if (tcpTarget == ProbeResult.Timeout && target.port != 443) {
            facts = facts.copy(tcpOtherPort = tcp(network, address, 443))
        }
        val name = (target.serverName ?: target.host).takeIf { it.isNotBlank() && literalAddress(it) == null }
        if (!target.usesTls || tcpTarget != ProbeResult.Answered || name == null) return@withContext facts
        val real = tls(network, address, target.port, name)
        facts = facts.copy(tlsRealName = real)
        if (real == ProbeResult.Answered) return@withContext facts
        val neutral = tls(network, address, target.port, TlsProbe.NEUTRAL_NAMES.random(random))
        facts = facts.copy(tlsNeutralName = neutral)
        if (neutral == ProbeResult.Answered) {
            facts = facts.copy(tlsRealNameElsewhere = tls(network, front, 443, name))
        }
        facts
    }

    private fun tcp(network: Network, address: InetAddress, port: Int): ProbeResult = try {
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(address, port), TlsProbe.CONNECT_TIMEOUT_MILLIS)
        }
        ProbeResult.Answered
    } catch (error: IOException) {
        judgeConnectError(error)
    }

    /** ClientHello с [name] на [address]:[port]; соединение не установилось — [ProbeResult.Unknown]. */
    private fun tls(network: Network, address: InetAddress, port: Int, name: String): ProbeResult {
        val socket: Socket = try {
            network.socketFactory.createSocket().also { it.connect(InetSocketAddress(address, port), TlsProbe.CONNECT_TIMEOUT_MILLIS) }
        } catch (_: IOException) {
            return ProbeResult.Unknown
        }
        return socket.use {
            try {
                it.soTimeout = TlsProbe.ANSWER_TIMEOUT_MILLIS
                it.getOutputStream().apply { write(TlsProbe.clientHello(name, random)); flush() }
                TlsProbe.judgeFirstByte(it.getInputStream().read())
            } catch (_: SocketTimeoutException) {
                ProbeResult.Timeout
            } catch (error: IOException) {
                if (errno(error) in RESET_ERRNOS || error.message.orEmpty().contains("reset", ignoreCase = true)) ProbeResult.Reset else ProbeResult.Unknown
            }
        }
    }

    private fun judgeConnectError(error: IOException): ProbeResult {
        if (error is SocketTimeoutException) return ProbeResult.Timeout
        val code = errno(error)
        val text = error.message.orEmpty()
        return when {
            code == OsConstants.ETIMEDOUT || text.contains("ETIMEDOUT") -> ProbeResult.Timeout
            code in RESET_ERRNOS || text.contains("ECONNREFUSED") || text.contains("ECONNRESET") -> ProbeResult.Reset
            error is ConnectException && text.contains("refused", ignoreCase = true) -> ProbeResult.Reset
            else -> ProbeResult.Unknown
        }
    }

    private fun errno(error: Throwable): Int? =
        generateSequence(error) { it.cause }.take(4).filterIsInstance<ErrnoException>().firstOrNull()?.errno

    private fun pickAddress(values: Set<String>): InetAddress? {
        val parsed = values.mapNotNull(::literalAddress)
        return parsed.firstOrNull { it is Inet4Address } ?: parsed.firstOrNull()
    }

    /**
     * Сколько байт ответа пришло через туннель до остановки; null — прочитано всё, что проба просит
     * ([FREEZE_READ_BYTES]), или замер не удался. Файл тот же, что у замера скорости: не российский
     * адрес, иначе маршрут «Россия напрямую» увёл бы его мимо сервера.
     */
    suspend fun freeze(vpn: Network?): Long? = withContext(Dispatchers.IO) {
        val network = vpn ?: return@withContext null
        val connection = try {
            network.openConnection(URL(FREEZE_URL)) as HttpsURLConnection
        } catch (_: IOException) {
            return@withContext null
        }
        try {
            connection.connectTimeout = FREEZE_CONNECT_MILLIS
            connection.readTimeout = FREEZE_STALL_MILLIS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Range", "bytes=0-${FREEZE_READ_BYTES - 1}")
            val status = connection.responseCode
            if (status != 200 && status != 206) return@withContext null
            var total = 0L
            val buffer = ByteArray(8192)
            try {
                connection.inputStream.use { input ->
                    while (total < FREEZE_READ_BYTES) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                }
            } catch (_: SocketTimeoutException) {
                return@withContext total
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val FREEZE_URL = "https://fra.download.datapacket.com/100mb.bin"
        const val FREEZE_READ_BYTES = 64L * 1024
        const val FREEZE_CONNECT_MILLIS = 6_000
        const val FREEZE_STALL_MILLIS = 5_000
        private val RESET_ERRNOS = setOf(OsConstants.ECONNREFUSED, OsConstants.ECONNRESET)

        fun literalAddress(value: String): InetAddress? {
            val text = value.trim().removePrefix("[").removeSuffix("]")
            val numeric = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(text) || (':' in text && text.all { it.isLetterOrDigit() || it == ':' || it == '.' })
            return if (numeric) runCatching { InetAddress.getByName(text) }.getOrNull() else null
        }
    }
}
