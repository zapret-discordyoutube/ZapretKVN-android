package io.github.zapretkvn.android.vpn

import android.net.Network
import io.github.zapretkvn.android.config.BootstrapConfig
import io.github.zapretkvn.android.config.BootstrapHostOverlay
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.ManagedHealthEndpoint
import io.github.zapretkvn.android.config.ManagedHealthProbe
import io.github.zapretkvn.android.config.ProxyIpFamily
import io.github.zapretkvn.android.config.ProxyBootstrapTarget
import io.github.zapretkvn.networkbootstrap.CodedFailure
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class HealthCheckResult(
    val externalIpProbeAllowed: Boolean,
)

/**
 * CodedFailure с DNS-200: проба идёт через VPN-сеть, поэтому коды системного
 * bootstrap-DNS (DNS-1xx) из cause-цепочки не должны попадать в итоговую ошибку.
 */
internal class VpnDnsHealthException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause), CodedFailure {
    override val failureCode = "DNS-200"
    override val userMessage = message
    override val technicalDetail = (cause as? CodedFailure)?.technicalDetail
}

enum class VpnHealthStage(
    val diagnosticKey: String,
    val diagnosticLabel: String,
) {
    AwaitVpnNetwork("vpn_network", "Ожидание VPN-сети Android"),
    DnsUdpProbe("dns_udp", "DNS через TUN (UDP)"),
    DnsTcpProbe("dns_tcp", "DNS через TUN (TCP fallback)"),
    DnsAndroidProbe("dns_android", "DNS через Android VPN network"),
    HttpsProbe("https_probe", "HTTPS-проверка через VPN"),
}

enum class VpnHealthStageOutcome {
    Success,
    Recovered,
    Failed,
}

data class PreparedBootstrap(
    val target: ProxyBootstrapTarget?,
    val addresses: List<InetAddress>,
    val resolvedAtEpochMillis: Long,
    val overlay: BootstrapHostOverlay? = null,
)

class ProxyBootstrapper(
    private val resolver: BootstrapResolver,
    private val cache: BootstrapCache,
) {
    suspend fun prepare(
        profileId: String,
        rawJson: String,
        underlying: Network,
        noCacheLookup: Boolean = false,
    ): PreparedBootstrap {
        val target = BootstrapConfig.target(rawJson) ?: return PreparedBootstrap(null, emptyList(), 0)
        val now = System.currentTimeMillis()
        if (!target.requiresDns) {
            val literal = numericAddresses(listOf(target.hostname))
            if (literal.isEmpty()) error("Некорректный IP-адрес VPN-сервера.")
            if (target.tcpPreflightSupported && firstReachable(underlying, target, literal) == null) {
                error("VPN-сервер не отвечает: ${target.hostname}:${target.port}.")
            }
            return PreparedBootstrap(target, literal, now)
        }
        val lkg = cache.find(profileId, target.hostname, now)
        val resolved = try {
            Result.success(resolver.resolve(underlying, target.hostname, noCacheLookup))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
        if (resolved.isSuccess) {
            val addresses = resolved.getOrThrow()
            if (!target.tcpPreflightSupported || firstReachable(underlying, target, addresses) != null) {
                return PreparedBootstrap(target, addresses, now)
            }
            if (target.staleAddressAllowed && lkg?.isFreshAt(now) == true) {
                val cached = numericAddresses(lkg.addresses)
                if (firstReachable(underlying, target, cached) != null) {
                    return PreparedBootstrap(target, cached, lkg.resolvedAtEpochMillis, lkg.overlay(target))
                }
            }
            error("VPN-сервер не отвечает: ${target.hostname}:${target.port}.")
        }

        if (target.staleAddressAllowed && lkg?.isUsableAt(now) == true) {
            val cached = numericAddresses(lkg.addresses)
            if (!target.tcpPreflightSupported || firstReachable(underlying, target, cached) != null) {
                return PreparedBootstrap(target, cached, lkg.resolvedAtEpochMillis, lkg.overlay(target))
            }
        }
        throw checkNotNull(resolved.exceptionOrNull())
    }

    suspend fun recordSuccess(profileId: String, prepared: PreparedBootstrap) {
        val target = prepared.target ?: return
        if (!target.staleAddressAllowed || prepared.addresses.isEmpty()) return
        cache.recordSuccess(
            profileId = profileId,
            hostname = target.hostname,
            addresses = prepared.addresses,
            resolvedAtEpochMillis = prepared.resolvedAtEpochMillis,
        )
    }

    private suspend fun firstReachable(
        network: Network,
        target: ProxyBootstrapTarget,
        addresses: List<InetAddress>,
    ): InetAddress? = withContext(Dispatchers.IO) {
        addresses.take(MAX_SOCKET_CANDIDATES).firstOrNull { address ->
            runCatching {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(address, target.port), SOCKET_TIMEOUT_MILLIS)
                }
            }.isSuccess
        }
    }

    private fun numericAddresses(values: List<String>): List<InetAddress> = values.mapNotNull { value ->
        if (':' !in value && !IPV4.matches(value)) return@mapNotNull null
        runCatching { InetAddress.getByName(value) }.getOrNull()
    }

    private fun BootstrapCacheEntry.overlay(target: ProxyBootstrapTarget) = BootstrapHostOverlay(
        outboundTag = target.outboundTag,
        hostname = target.hostname,
        addresses = addresses,
    )

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 1_500
        const val MAX_SOCKET_CANDIDATES = 3
        val IPV4 = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
    }
}

class VpnHealthPipeline(
    private val vpnNetworks: VpnNetworkProvider,
) {
    private val resolver = BootstrapResolver()

    suspend fun verify(
        mode: DnsMode,
        internalDnsServer: String,
        proxyIpFamily: ProxyIpFamily = ProxyIpFamily.Unspecified,
        onStageStarted: (VpnHealthStage) -> Unit = {},
        onStageFinished: (VpnHealthStage, VpnHealthStageOutcome, String?) -> Unit = { _, _, _ -> },
    ): HealthCheckResult {
        onStageStarted(VpnHealthStage.AwaitVpnNetwork)
        if (VpnTestHooks.consumeHealthFailureOverride()) {
            onStageFinished(
                VpnHealthStage.AwaitVpnNetwork,
                VpnHealthStageOutcome.Failed,
                "test_override",
            )
            error("Тестовая ошибка health-check DNS/HTTPS.")
        }
        if (VpnTestHooks.consumeHealthSuccessOverride()) {
            onStageFinished(
                VpnHealthStage.AwaitVpnNetwork,
                VpnHealthStageOutcome.Success,
                "test_override",
            )
            return HealthCheckResult(externalIpProbeAllowed = false)
        }
        return withTimeout(HEALTH_TIMEOUT_MILLIS) {
            val vpnNetwork = try {
                vpnNetworks.awaitActive().also(vpnNetworks::requireActive)
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.AwaitVpnNetwork,
                    VpnHealthStageOutcome.Failed,
                    rootCauseName(error),
                )
                throw error
            }
            onStageFinished(
                VpnHealthStage.AwaitVpnNetwork,
                VpnHealthStageOutcome.Success,
                "active=true",
            )
            if (VpnTestHooks.consumeDnsProbeFailure()) {
                val failedStage = if (mode == DnsMode.Automatic || mode == DnsMode.Secure) {
                    VpnHealthStage.DnsUdpProbe
                } else {
                    VpnHealthStage.DnsAndroidProbe
                }
                onStageStarted(failedStage)
                onStageFinished(failedStage, VpnHealthStageOutcome.Failed, "test_override")
                throw VpnDnsHealthException(
                    "DNS через VPN не отвечает: тестовый внутренний DNS недоступен.",
                )
            }
            if (mode == DnsMode.Automatic || mode == DnsMode.Secure) {
                rawDnsProbe(
                    internalDnsServer,
                    ManagedHealthProbe.endpoints.first().host,
                    onStageStarted,
                    onStageFinished,
                )
            } else {
                onStageStarted(VpnHealthStage.DnsAndroidProbe)
                try {
                    val addresses = resolver.resolve(
                        vpnNetwork,
                        ManagedHealthProbe.endpoints.first().host,
                    )
                    onStageFinished(
                        VpnHealthStage.DnsAndroidProbe,
                        VpnHealthStageOutcome.Success,
                        "answers=${addresses.size}",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    onStageFinished(
                        VpnHealthStage.DnsAndroidProbe,
                        VpnHealthStageOutcome.Failed,
                        rootCauseName(error),
                    )
                    throw dnsFailure(error)
                }
            }
            onStageStarted(VpnHealthStage.HttpsProbe)
            if (VpnTestHooks.consumeHttpsProbeFailure()) {
                onStageFinished(
                    VpnHealthStage.HttpsProbe,
                    VpnHealthStageOutcome.Failed,
                    "test_override",
                )
                error("HTTPS-проверка через VPN не прошла: тестовый endpoint недоступен.")
            }
            try {
                val result = httpsProbe(vpnNetwork, proxyIpFamily)
                onStageFinished(
                    VpnHealthStage.HttpsProbe,
                    VpnHealthStageOutcome.Success,
                    "endpoint=${result.endpoint.code} status=${result.status} " +
                        "family=${result.addressFamily.diagnosticName}",
                )
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.HttpsProbe,
                    VpnHealthStageOutcome.Failed,
                    (error as? HttpsProbeFailure)?.diagnosticDetail ?: rootCauseName(error),
                )
                throw error
            }
            HealthCheckResult(externalIpProbeAllowed = true)
        }
    }

    private suspend fun rawDnsProbe(
        dnsServer: String,
        hostname: String,
        onStageStarted: (VpnHealthStage) -> Unit,
        onStageFinished: (VpnHealthStage, VpnHealthStageOutcome, String?) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val query = dnsQuery(hostname)
            onStageStarted(VpnHealthStage.DnsUdpProbe)
            val udp = try {
                udpDns(dnsServer, query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.DnsUdpProbe,
                    VpnHealthStageOutcome.Recovered,
                    rootCauseName(error),
                )
                null
            }
            if (udp != null) {
                try {
                    val rcode = validateDnsResponse(query, udp)
                    onStageFinished(
                        VpnHealthStage.DnsUdpProbe,
                        VpnHealthStageOutcome.Success,
                        "response_bytes=${udp.size} rcode=$rcode",
                    )
                    return@withContext
                } catch (error: Throwable) {
                    onStageFinished(
                        VpnHealthStage.DnsUdpProbe,
                        VpnHealthStageOutcome.Failed,
                        rootCauseName(error),
                    )
                    throw dnsFailure(error)
                }
            }

            onStageStarted(VpnHealthStage.DnsTcpProbe)
            val tcp = try {
                tcpDns(dnsServer, query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.DnsTcpProbe,
                    VpnHealthStageOutcome.Failed,
                    rootCauseName(error),
                )
                throw dnsFailure(error)
            }
            try {
                val rcode = validateDnsResponse(query, tcp)
                onStageFinished(
                    VpnHealthStage.DnsTcpProbe,
                    VpnHealthStageOutcome.Success,
                    "response_bytes=${tcp.size} rcode=$rcode",
                )
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.DnsTcpProbe,
                    VpnHealthStageOutcome.Failed,
                    rootCauseName(error),
                )
                throw dnsFailure(error)
            }
        }

    private fun dnsFailure(error: Throwable): VpnDnsHealthException {
        if (error is VpnDnsHealthException) return error
        val detail = (error as? CodedFailure)?.failureCode
            ?: error.message?.trim()?.trimEnd('.')
                ?.takeIf(String::isNotBlank)
            ?: error.javaClass.simpleName
        return VpnDnsHealthException("DNS через VPN не отвечает: $detail.", error)
    }

    private fun udpDns(dnsServer: String, query: ByteArray): ByteArray {
        DatagramSocket().use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MILLIS
            val endpoint = InetSocketAddress(InetAddress.getByName(dnsServer), 53)
            socket.send(DatagramPacket(query, query.size, endpoint))
            val buffer = ByteArray(MAX_DNS_PACKET)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            return response.data.copyOf(response.length)
        }
    }

    private fun tcpDns(dnsServer: String, query: ByteArray): ByteArray {
        Socket().use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MILLIS
            socket.connect(InetSocketAddress(InetAddress.getByName(dnsServer), 53), DNS_TIMEOUT_MILLIS)
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(query.size)
                write(query)
                flush()
            }
            val input = DataInputStream(socket.getInputStream())
            val size = input.readUnsignedShort()
            require(size in 12..MAX_DNS_PACKET) { "Некорректный размер DNS-ответа." }
            return ByteArray(size).also(input::readFully)
        }
    }

    private fun validateDnsResponse(query: ByteArray, response: ByteArray): Int {
        if (response.size < 12 || response[0] != query[0] || response[1] != query[1]) {
            error("DNS через VPN вернул некорректный ответ.")
        }
        val flags = ((response[2].toInt() and 0xff) shl 8) or (response[3].toInt() and 0xff)
        if (flags and 0x8000 == 0 || flags and 0x000f != 0) {
            error("DNS через VPN вернул ошибку ${flags and 0x000f}.")
        }
        return flags and 0x000f
    }

    private suspend fun httpsProbe(
        vpnNetwork: Network,
        proxyIpFamily: ProxyIpFamily,
    ): HttpsProbeResult = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        var lastError: Throwable? = null
        for (endpoint in ManagedHealthProbe.endpoints) {
            try {
                return@withContext HttpsProbeResult(
                    endpoint = endpoint,
                    status = httpsProbeOne(vpnNetwork, endpoint, proxyIpFamily),
                    addressFamily = proxyIpFamily,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: VpnHealthAddressFamilyException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                failures += "${endpoint.code}:${httpsFailureReason(error)}"
            }
        }
        val detail = failures.joinToString("; ").take(MAX_HTTPS_FAILURE_DETAIL_CHARS)
        throw HttpsProbeFailure(
            diagnosticDetail = detail,
            message = "HTTPS-проверка через VPN не прошла: $detail. " +
                "Причиной может быть недоступный или заблокированный сервер, " +
                "отключённый ключ либо неверные параметры транспорта.",
            cause = lastError,
        )
    }

    private suspend fun httpsProbeOne(
        vpnNetwork: Network,
        endpoint: ManagedHealthEndpoint,
        proxyIpFamily: ProxyIpFamily,
    ): Int {
        if (proxyIpFamily == ProxyIpFamily.Ipv4Only || proxyIpFamily == ProxyIpFamily.Ipv6Only) {
            val addresses = resolver.resolve(vpnNetwork, endpoint.host)
            val address = selectHealthAddress(addresses, proxyIpFamily)
                ?: throw VpnHealthAddressFamilyException(
                    requiredFamily = proxyIpFamily,
                    endpointCode = endpoint.code,
                    answerCount = addresses.size,
                )
            return httpsProbeOnePinned(vpnNetwork, endpoint, address)
        }
        return httpsProbeOneDefault(vpnNetwork, endpoint)
    }

    private fun httpsProbeOneDefault(vpnNetwork: Network, endpoint: ManagedHealthEndpoint): Int {
        val connection = vpnNetwork.openConnection(URL(endpoint.url)) as HttpsURLConnection
        try {
            connection.connectTimeout = HTTPS_ENDPOINT_TIMEOUT_MILLIS
            connection.readTimeout = HTTPS_ENDPOINT_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            connection.requestMethod = "GET"
            val status = connection.responseCode
            if (status !in HTTP_REACHABLE_STATUS_RANGE) throw UnexpectedHttpsStatus(status)
            return status
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens the selected numeric address on Android's VPN Network while keeping the
     * original hostname for TLS SNI, certificate verification and HTTP Host.
     */
    private fun httpsProbeOnePinned(
        vpnNetwork: Network,
        endpoint: ManagedHealthEndpoint,
        address: InetAddress,
    ): Int {
        val url = URL(endpoint.url)
        val port = url.port.takeIf { it >= 0 } ?: 443
        val plain = vpnNetwork.socketFactory.createSocket()
        try {
            plain.soTimeout = HTTPS_ENDPOINT_TIMEOUT_MILLIS
            plain.connect(InetSocketAddress(address, port), HTTPS_ENDPOINT_TIMEOUT_MILLIS)
            val tls = HttpsURLConnection.getDefaultSSLSocketFactory()
                .createSocket(plain, endpoint.host, port, true) as SSLSocket
            tls.use {
                it.soTimeout = HTTPS_ENDPOINT_TIMEOUT_MILLIS
                it.sslParameters = it.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(endpoint.host))
                }
                it.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(endpoint.host, it.session)) {
                    throw SSLPeerUnverifiedException(
                        "Сертификат HTTPS health endpoint не соответствует имени.",
                    )
                }
                val path = buildString {
                    append(url.path.ifBlank { "/" })
                    url.query?.let { query -> append('?').append(query) }
                }
                val output = BufferedOutputStream(it.outputStream)
                output.write(
                    (
                        "GET $path HTTP/1.1\r\n" +
                            "Host: ${endpoint.host}\r\n" +
                            "User-Agent: ZapretKVN-health\r\n" +
                            "Accept: */*\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII),
                )
                output.flush()
                val statusLine = BufferedInputStream(it.inputStream).use(::readHttpStatusLine)
                val status = statusLine
                    .split(' ', limit = 3)
                    .getOrNull(1)
                    ?.toIntOrNull()
                    ?: throw IOException("Некорректный HTTP status health endpoint.")
                if (status !in HTTP_REACHABLE_STATUS_RANGE) throw UnexpectedHttpsStatus(status)
                return status
            }
        } finally {
            runCatching { plain.close() }
        }
    }

    private fun readHttpStatusLine(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>(64)
        while (bytes.size < MAX_HTTP_STATUS_LINE_BYTES) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        if (bytes.isEmpty() || bytes.size >= MAX_HTTP_STATUS_LINE_BYTES) {
            throw IOException("Некорректная строка HTTP status health endpoint.")
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }

    private fun httpsFailureReason(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        chain.filterIsInstance<CodedFailure>().firstOrNull()?.let { coded ->
            return "DNS через VPN (${coded.failureCode})"
        }
        return when (val cause = chain.last()) {
            is UnexpectedHttpsStatus -> "тестовый узел вернул некорректный HTTP ${cause.status}"
            is SocketTimeoutException -> "истёк тайм-аут ${HTTPS_ENDPOINT_TIMEOUT_MILLIS} мс"
            is UnknownHostException -> "Android не разрешил имя тестового узла"
            is ConnectException -> "TCP-соединение с тестовым узлом не установлено"
            is SSLException -> "ошибка TLS (${cause.javaClass.simpleName})"
            is IOException -> "ошибка ввода-вывода (${cause.javaClass.simpleName})"
            else -> cause.javaClass.simpleName.ifBlank { "неизвестная сетевая ошибка" }
        }
    }

    /** Стабильный код вместо obfuscated-имени класса в release-сборке. */
    private fun rootCauseName(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        return (
            chain.filterIsInstance<CodedFailure>().firstOrNull()?.failureCode
                ?: chain.last().javaClass.simpleName
            ).take(80)
    }

    private data class HttpsProbeResult(
        val endpoint: ManagedHealthEndpoint,
        val status: Int,
        val addressFamily: ProxyIpFamily,
    )

    /**
     * CodedFailure с VPN-200: без этого safeError достаёт из cause-цепочки
     * BootstrapFailureException резолва внутри туннеля и сообщает DNS-101
     * «Системный DNS», хотя системный DNS не участвовал.
     */
    private class HttpsProbeFailure(
        val diagnosticDetail: String,
        message: String,
        cause: Throwable?,
    ) : IOException(message, cause), CodedFailure {
        override val failureCode = "VPN-200"
        override val userMessage = message
        override val technicalDetail = diagnosticDetail
    }

    private class UnexpectedHttpsStatus(val status: Int) : IOException()

    private class VpnHealthAddressFamilyException(
        private val requiredFamily: ProxyIpFamily,
        endpointCode: String,
        answerCount: Int,
    ) : IOException(), CodedFailure {
        override val failureCode = "VPN-201"
        override val userMessage = when (requiredFamily) {
            ProxyIpFamily.Ipv4Only ->
                "HTTPS-проверка: WireGuard-профиль поддерживает только IPv4, " +
                    "но проверочный узел не вернул IPv4-адрес."
            ProxyIpFamily.Ipv6Only ->
                "HTTPS-проверка: WireGuard-профиль поддерживает только IPv6, " +
                    "но проверочный узел не вернул IPv6-адрес."
            else -> "HTTPS-проверка: IP-семейство WireGuard несовместимо с проверочным узлом."
        }
        override val technicalDetail =
            "health_family=${requiredFamily.diagnosticName} endpoint=$endpointCode answers=$answerCount"

        override val message: String
            get() = userMessage
    }

    private fun dnsQuery(hostname: String): ByteArray {
        val id = ThreadLocalRandom.current().nextInt(0x10000)
        val labels = hostname.trimEnd('.').split('.')
        val size = 12 + labels.sumOf { 1 + it.encodeToByteArray().size } + 1 + 4
        val output = ByteArray(size)
        output[0] = (id ushr 8).toByte()
        output[1] = id.toByte()
        output[2] = 0x01
        output[5] = 0x01
        var offset = 12
        labels.forEach { label ->
            val bytes = label.encodeToByteArray()
            require(bytes.size in 1..63)
            output[offset++] = bytes.size.toByte()
            bytes.copyInto(output, offset)
            offset += bytes.size
        }
        output[offset++] = 0
        output[offset++] = 0
        output[offset++] = 1
        output[offset++] = 0
        output[offset] = 1
        return output
    }

    private companion object {
        const val HEALTH_TIMEOUT_MILLIS = 20_000L
        const val DNS_TIMEOUT_MILLIS = 2_500
        const val HTTPS_ENDPOINT_TIMEOUT_MILLIS = 4_000
        const val MAX_HTTPS_FAILURE_DETAIL_CHARS = 240
        const val MAX_HTTP_STATUS_LINE_BYTES = 512
        const val MAX_DNS_PACKET = 65_535
        val HTTP_REACHABLE_STATUS_RANGE = 200..599
    }
}

private val ProxyIpFamily.diagnosticName: String
    get() = when (this) {
        ProxyIpFamily.Ipv4Only -> "ipv4"
        ProxyIpFamily.Ipv6Only -> "ipv6"
        ProxyIpFamily.DualStack -> "dual"
        ProxyIpFamily.Unspecified -> "resolver"
    }

internal fun selectHealthAddress(
    addresses: List<InetAddress>,
    family: ProxyIpFamily,
): InetAddress? = when (family) {
    ProxyIpFamily.Ipv4Only -> addresses.firstOrNull { it is Inet4Address }
    ProxyIpFamily.Ipv6Only -> addresses.firstOrNull { it is Inet6Address }
    ProxyIpFamily.DualStack,
    ProxyIpFamily.Unspecified,
    -> addresses.firstOrNull()
}
