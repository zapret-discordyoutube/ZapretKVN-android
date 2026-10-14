package io.github.zapretkvn.android.network.probes.services

import android.content.Context
import android.net.Network
import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.VpnNetworkLease
import io.github.zapretkvn.android.network.VpnNetworkLostException
import io.github.zapretkvn.android.network.VpnNetworkProvider
import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HTTPS и TCP поверх конкретной [Network]. Сокет всегда привязан к этой сети:
 * ни при какой ошибке запрос не уходит по умолчанию. Заголовки как у браузера
 * Android; видны они только внутри TLS.
 */
internal class NetworkServiceProbeTransport(
    private val network: Network,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
) : ServiceProbeTransport {
    override suspend fun https(endpoint: ProbeEndpoint): TransportResult = coroutineScope {
        val holder = AtomicReference<HttpsURLConnection?>()
        val work = async(Dispatchers.IO) { doHttps(endpoint, holder) }
        try {
            work.await()
        } catch (cancelled: CancellationException) {
            // Блокирующий ввод-вывод сам не отменяется: обрываем соединение, чтобы поток освободился.
            runCatching { holder.get()?.disconnect() }
            throw cancelled
        }
    }

    private fun doHttps(endpoint: ProbeEndpoint, holder: AtomicReference<HttpsURLConnection?>): TransportResult {
        val started = System.nanoTime()
        var connection: HttpsURLConnection? = null
        return try {
            val opened = network.openConnection(URL("https://${endpoint.host}${endpoint.path}")) as HttpsURLConnection
            connection = opened
            holder.set(opened)
            opened.connectTimeout = connectTimeoutMillis
            opened.readTimeout = readTimeoutMillis
            opened.instanceFollowRedirects = false
            opened.useCaches = false
            opened.requestMethod = if (endpoint.method == ProbeMethod.Head) "HEAD" else "GET"
            opened.setRequestProperty("Connection", "close")
            opened.setRequestProperty("Accept-Encoding", "identity")
            opened.setRequestProperty("User-Agent", USER_AGENT)
            opened.setRequestProperty("Accept", "*/*")
            opened.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            if (endpoint.geo != null) {
                opened.setRequestProperty("Range", "bytes=0-${ServiceProbeBudget.MAX_BODY_BYTES - 1}")
            }
            val status = opened.responseCode
            val millis = elapsedMillis(started)
            var body: String? = null
            var bodyBytes = 0
            val geoRule = endpoint.geo
            if (geoRule != null && status in geoRule.statuses) {
                val stream = if (status >= 400) opened.errorStream else opened.inputStream
                val sample = stream?.use(::readCapped)
                bodyBytes = sample?.size ?: 0
                body = sample?.decodeToString()?.lowercase()
            }
            TransportResult.Http(
                status = status,
                millis = millis,
                bodySample = body,
                bytes = ServiceProbeBudget.HANDSHAKE_BYTES_ESTIMATE + HEADERS_BYTES_ESTIMATE + bodyBytes,
            )
        } catch (error: Throwable) {
            // Только вид отказа: текст исключения может содержать адрес.
            TransportResult.Failure(HttpsProbeFailureKind.of(error), FAILED_BYTES_ESTIMATE)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    override suspend fun tcp(target: TcpEndpoint): TransportResult = coroutineScope {
        val socket = runCatching { network.socketFactory.createSocket() }.getOrNull()
            ?: return@coroutineScope TransportResult.Failure(HttpsProbeFailureKind.Other, 0)
        val work = async(Dispatchers.IO) { doTcp(socket, target) }
        try {
            work.await()
        } catch (cancelled: CancellationException) {
            runCatching { socket.close() }
            throw cancelled
        }
    }

    private fun doTcp(socket: Socket, target: TcpEndpoint): TransportResult {
        val started = System.nanoTime()
        return try {
            socket.connect(InetSocketAddress(target.ip, target.port), connectTimeoutMillis)
            TransportResult.TcpOpen(elapsedMillis(started), TCP_BYTES_ESTIMATE)
        } catch (error: Throwable) {
            TransportResult.Failure(HttpsProbeFailureKind.of(error), TCP_BYTES_ESTIMATE)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val buffer = ByteArray(ServiceProbeBudget.MAX_BODY_BYTES)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        return buffer.copyOf(count)
    }

    private fun elapsedMillis(startedNanos: Long): Int =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        const val READ_TIMEOUT_MILLIS = 5_000
        const val TUNNEL_CONNECT_TIMEOUT_MILLIS = 5_000
        const val DIRECT_CONNECT_TIMEOUT_MILLIS = 4_000
        private const val HEADERS_BYTES_ESTIMATE = 1_024
        private const val FAILED_BYTES_ESTIMATE = 1_024
        private const val TCP_BYTES_ESTIMATE = 128

        /** Обычный мобильный Chrome: Cloudflare и Instagram иначе по-другому отвечают «Dalvik». */
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}

/** Туннельный транспорт: каждый запрос идёт по одной аренде VPN-сети и обрывается, если сеть пропала. */
internal class LeaseTunnelPath(private val lease: VpnNetworkLease) : TunnelPath {
    private val inner = NetworkServiceProbeTransport(
        lease.network,
        NetworkServiceProbeTransport.TUNNEL_CONNECT_TIMEOUT_MILLIS,
    )

    override val transport: ServiceProbeTransport = object : ServiceProbeTransport {
        override suspend fun https(endpoint: ProbeEndpoint) = guarded { inner.https(endpoint) }
        override suspend fun tcp(target: TcpEndpoint) = guarded { inner.tcp(target) }
    }

    private suspend fun guarded(block: suspend () -> TransportResult): TransportResult = try {
        lease.runWhileActive { block() }
    } catch (lost: VpnNetworkLostException) {
        throw PathLostException()
    }

    override fun close() = lease.close()
}

/** Доступ к путям Android: аренда VPN-сети и привязка к физической сети. */
internal class AndroidServiceProbePaths(private val vpnNetworks: VpnNetworkProvider) {
    /** null, если VPN не стал активной сетью приложения за [LEASE_WAIT_MILLIS]. */
    suspend fun openTunnel(): TunnelPath? =
        withTimeoutOrNull(LEASE_WAIT_MILLIS) { vpnNetworks.acquireActive() }?.let(::LeaseTunnelPath)

    fun direct(network: Network?): ServiceProbeTransport? = network?.let {
        NetworkServiceProbeTransport(it, NetworkServiceProbeTransport.DIRECT_CONNECT_TIMEOUT_MILLIS)
    }

    private companion object {
        const val LEASE_WAIT_MILLIS = 3_000L
    }
}

/**
 * Физическая сеть для проверки без VPN. Монитор сети открывается по первому нажатию и
 * живёт, пока на карточке есть результаты (сменилась сеть, и они устарели); пока ничего
 * не проверялось, обратных вызовов сети приложение не держит.
 */
internal class AndroidDirectNetworkSource(private val context: Context) : DirectNetworkSource {
    override fun open(onChange: () -> Unit): DirectNetworkSession {
        val monitor = DefaultNetworkMonitor(context)
        try {
            monitor.start()
        } catch (error: Throwable) {
            monitor.close()
            throw error
        }
        return MonitorDirectSession(monitor, onChange)
    }

    private class MonitorDirectSession(
        private val monitor: DefaultNetworkMonitor,
        onChange: () -> Unit,
    ) : DirectNetworkSession {
        @Volatile private var lastIdentity: String? = monitor.current.identity
        private val observer: AutoCloseable = monitor.observe { state ->
            val now = state.identity
            if (now != lastIdentity) {
                lastIdentity = now
                onChange()
            }
        }

        override val identity: String? get() = monitor.current.identity

        override fun transport(): ServiceProbeTransport? = monitor.current.network?.let {
            NetworkServiceProbeTransport(it, NetworkServiceProbeTransport.DIRECT_CONNECT_TIMEOUT_MILLIS)
        }

        override fun close() {
            runCatching { observer.close() }
            monitor.close()
        }
    }
}
