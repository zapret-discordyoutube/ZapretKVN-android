package io.github.zapretkvn.android.clientreports

import android.net.Network
import android.os.SystemClock
import io.github.zapretkvn.clientreports.ReportCheck
import io.github.zapretkvn.clientreports.ReportChecks
import io.github.zapretkvn.networkbootstrap.TrustedDnsOutcome
import io.github.zapretkvn.networkbootstrap.TrustedDohResolver
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Туннель поднят, данных нет (владелец 19.11.2026): тот же сервис проверки связи по адресу (`tunnel_a`: без DNS в
 * туннеле) и по имени (`tunnel_b`: DNS через туннель) через VPN, и те же два мимо VPN (`direct_a`/`direct_b`). Адрес
 * даёт наш DoH по основной сети. Внутри туннеля проба не видна ТСПУ как отдельная; прямые идут к обычному
 * generate_204, как у любой проверки связи. Сервер не трогается: путь до него туннель уже доказал. Те же шаги, что у
 * Windows `probe.NoDataProbe`.
 */
internal class NoDataProbe(
    private val physical: () -> Network?,
    private val vpn: () -> Network?,
    private val random: Random,
    private val trusted: TrustedDohResolver = TrustedDohResolver(),
) {
    suspend fun run(): Map<String, ReportCheck> = withContext(Dispatchers.IO) {
        val tunnel = vpn() ?: return@withContext emptyMap()
        val direct = physical()
        val (name, path) = ENDPOINTS.random(random)
        val address = direct?.let { network ->
            (runCatching { trusted.resolve(network, name) }.getOrNull() as? TrustedDnsOutcome.Addresses)
                ?.value?.firstOrNull { it is Inet4Address }
        }
        val plan = mutableListOf<Pair<String, (Progress) -> Unit>>("tunnel_b" to { https(tunnel, null, name, path, it) })
        if (address != null) plan += "tunnel_a" to { https(tunnel, address, name, path, it) }
        plan.shuffle(random)
        if (direct != null) {
            val straight = mutableListOf<Pair<String, (Progress) -> Unit>>("direct_b" to { https(direct, null, name, path, it) })
            if (address != null) straight += "direct_a" to { https(direct, address, name, path, it) }
            straight.shuffle(random)
            plan += straight
        }
        val found = LinkedHashMap<String, ReportCheck>()
        plan.forEach { (probe, work) ->
            val started = SystemClock.elapsedRealtime()
            val progress = Progress()
            val result = try {
                work(progress)
                ReportChecks.ANSWERED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                val millis = SystemClock.elapsedRealtime() - started
                lookupResult(progress.stage, millis)
                    ?: ProbeErrors.result(error, connected = connectedFor(progress.stage, tunnel = probe.startsWith("tunnel_")))
            } catch (_: Exception) {
                ReportChecks.UNKNOWN
            }
            found[probe] = ReportCheck(result, ReportChecks.clampMillis(SystemClock.elapsedRealtime() - started))
        }
        found
    }

    /** How far one probe got: the name lookup, the TCP connect, or the exchange over an open connection. */
    internal enum class Stage { LOOKUP, CONNECT, EXCHANGE }

    internal class Progress(var stage: Stage = Stage.LOOKUP)

    /** HEAD по HTTPS через [network]: к литеральному [address] (без DNS) или по имени (DNS этой сети). */
    private fun https(network: Network, address: InetAddress?, name: String, path: String, progress: Progress) {
        val target = address ?: network.getAllByName(name).first()
        progress.stage = Stage.CONNECT
        network.socketFactory.createSocket().use { raw ->
            raw.connect(InetSocketAddress(target, 443), TIMEOUT_MILLIS)
            progress.stage = Stage.EXCHANGE
            raw.soTimeout = TIMEOUT_MILLIS
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, name, 443, true) as SSLSocket
            tls.use { socket ->
                socket.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(name, socket.session)) throw IOException("name")
                socket.outputStream.apply {
                    write("HEAD $path HTTP/1.1\r\nHost: $name\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val head = ByteArray(5)
                var read = 0
                while (read < head.size) {
                    val count = socket.inputStream.read(head, read, head.size - read)
                    if (count < 0) throw java.io.EOFException("no HTTP answer")
                    read += count
                }
                if (String(head, Charsets.US_ASCII) != "HTTP/") throw IOException("no HTTP answer")
            }
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 8_000

        /** A failed name lookup that took this long got no answer (Android reports a resolver timeout as EAI_NODATA too). */
        const val LOOKUP_SILENT_MILLIS = 5_000L

        /**
         * A failed name lookup: no answer in [LOOKUP_SILENT_MILLIS] is `timeout`, a quick failure proves nothing
         * (`unknown`). `null` when the probe got past the lookup (vpnbot 2026-11-19: tunnel_b «unknown» after 20.9 s).
         */
        internal fun lookupResult(stage: Stage, millis: Long): String? = when {
            stage != Stage.LOOKUP -> null
            millis >= LOOKUP_SILENT_MILLIS -> ReportChecks.TIMEOUT
            else -> ReportChecks.UNKNOWN
        }

        /**
         * Whether a RST or EOF came over an open connection (`reset`) rather than to the SYN (`refused`). Through the
         * tunnel the TUN served by sing-box completes the connect itself, before the core dials anything: a drop there
         * is the core's flow dropped, never a refusal (vpnbot 2026-11-19: tunnel_a «refused» after 2 s).
         */
        internal fun connectedFor(stage: Stage, tunnel: Boolean): Boolean = tunnel || stage == Stage.EXCHANGE

        /** Проверка связи, как у пробы сервера: узлы отвергают публичный DoH и сервисы «мой IP», эти — нет. */
        val ENDPOINTS = listOf(
            "www.google.com" to "/generate_204",
            "connectivitycheck.gstatic.com" to "/generate_204",
            "cp.cloudflare.com" to "/generate_204",
        )
    }
}
