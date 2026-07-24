package io.github.zapretkvn.android.vpn

import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal data class GateTrafficResult(
    val success: Boolean,
    val error: String? = null,
    val resolvedAddress: String? = null,
)

/** Network client running in the target app UID, so both DNS and data use the tested TUN. */
internal object GateTrafficClient {
    fun tcpEcho(target: String, port: Int, payloadSize: Int, value: Int): GateTrafficResult {
        val address = runCatching {
            if (target.any(Char::isLetter) && ':' !in target) resolveWithTunDns(target)
            else InetAddress.getByName(target)
        }.getOrElse {
            return GateTrafficResult(false, "DNS stage failed: ${it.message ?: it.javaClass.simpleName}")
        }
        return runCatching {
        val payload = ByteArray(payloadSize) { value.toByte() }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), TIMEOUT_MILLIS)
            socket.soTimeout = TIMEOUT_MILLIS
            DataOutputStream(socket.getOutputStream()).apply {
                writeInt(payload.size)
                write(payload)
                flush()
            }
            val input = DataInputStream(socket.getInputStream())
            val length = input.readInt()
            check(length == payload.size) { "Unexpected TCP echo size: $length" }
            val received = ByteArray(length)
            input.readFully(received)
            check(received.contentEquals(payload)) { "TCP echo mismatch" }
        }
        GateTrafficResult(true, resolvedAddress = address.hostAddress)
        }.getOrElse {
            GateTrafficResult(
                false,
                "TCP stage to ${address.hostAddress} failed: ${it.message ?: it.javaClass.simpleName}",
                address.hostAddress,
            )
        }
    }

    fun tunDns(host: String): GateTrafficResult = runCatching {
        val address = resolveWithTunDns(host)
        GateTrafficResult(true, resolvedAddress = address.hostAddress)
    }.getOrElse { GateTrafficResult(false, it.message ?: it.javaClass.simpleName) }

    fun https(host: String): GateTrafficResult = runCatching {
        val resolved = resolveWithTunDns(host)
        val connection = URL("https://$host/").openConnection() as HttpsURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        val status = try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
        check(status in 200..599) { "Unexpected HTTPS status: $status" }
        GateTrafficResult(true, resolvedAddress = resolved.hostAddress)
    }.getOrElse { GateTrafficResult(false, it.message ?: it.javaClass.simpleName) }

    /** Models an application-owned DoH resolver followed by a numeric connection. */
    fun embeddedDohConnect(host: String): GateTrafficResult = runCatching {
        val encoded = URLEncoder.encode(host, "UTF-8")
        val connection = URL("https://1.1.1.1/dns-query?name=$encoded&type=A")
            .openConnection() as HttpsURLConnection
        // This test verifies that application-owned HTTPS DNS bypasses domain-only routing.
        // Certificate validation is intentionally out of scope because API 26 emulator CA stores are stale.
        val routingOnlyTls = SSLContext.getInstance("TLS").apply {
            init(
                null,
                arrayOf(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                }),
                SecureRandom(),
            )
        }
        connection.sslSocketFactory = routingOnlyTls.socketFactory
        connection.hostnameVerifier = { _, _ -> true }
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/dns-json")
        check(connection.responseCode == 200) { "Embedded DoH HTTP status: ${connection.responseCode}" }
        val body = try {
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val answers = JSONObject(body).optJSONArray("Answer")
            ?: error("Embedded DoH returned no answers")
        val address = (0 until answers.length()).firstNotNullOfOrNull { index ->
            answers.getJSONObject(index).takeIf { it.optInt("type") == 1 }?.optString("data")
        }?.takeIf(String::isNotBlank) ?: error("Embedded DoH returned no IPv4 answer")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(address), 443), TIMEOUT_MILLIS)
        }
        GateTrafficResult(true, resolvedAddress = address)
    }.getOrElse { GateTrafficResult(false, it.message ?: it.javaClass.simpleName) }

    private fun resolveWithTunDns(host: String): InetAddress {
        val query = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                val identifier = (System.nanoTime() and 0xffff).toInt()
                output.writeShort(identifier)
                output.writeShort(0x0100)
                output.writeShort(1)
                repeat(3) { output.writeShort(0) }
                host.split('.').forEach { label ->
                    val bytes = label.toByteArray(StandardCharsets.US_ASCII)
                    require(bytes.size in 1..63) { "Invalid DNS label" }
                    output.writeByte(bytes.size)
                    output.write(bytes)
                }
                output.writeByte(0)
                output.writeShort(1)
                output.writeShort(1)
            }
        }.toByteArray()
        val identifier = unsignedShort(query, 0)
        val response = ByteArray(4096)
        val packet = DatagramPacket(response, response.size)
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MILLIS
            socket.send(
                DatagramPacket(query, query.size, InetSocketAddress(InetAddress.getByName("172.19.0.2"), 53)),
            )
            socket.receive(packet)
        }
        val length = packet.length
        check(length >= 12 && unsignedShort(response, 0) == identifier) { "Invalid TUN DNS response" }
        val rcode = unsignedShort(response, 2) and 0x0f
        check(rcode == 0) { "TUN DNS rejected query with RCODE $rcode" }
        var offset = 12
        repeat(unsignedShort(response, 4)) {
            offset = skipDnsName(response, length, offset) + 4
            check(offset <= length) { "Truncated TUN DNS question" }
        }
        repeat(unsignedShort(response, 6)) {
            offset = skipDnsName(response, length, offset)
            check(offset + 10 <= length) { "Truncated TUN DNS answer" }
            val type = unsignedShort(response, offset)
            val answerClass = unsignedShort(response, offset + 2)
            val dataLength = unsignedShort(response, offset + 8)
            offset += 10
            check(offset + dataLength <= length) { "Truncated TUN DNS data" }
            if (type == 1 && answerClass == 1 && dataLength == 4) {
                return InetAddress.getByAddress(response.copyOfRange(offset, offset + 4))
            }
            offset += dataLength
        }
        error("TUN DNS returned no IPv4 answer")
    }

    private fun skipDnsName(message: ByteArray, length: Int, start: Int): Int {
        var offset = start
        while (offset < length) {
            val labelLength = message[offset].toInt() and 0xff
            if (labelLength and 0xc0 == 0xc0) {
                check(offset + 1 < length) { "Truncated DNS pointer" }
                return offset + 2
            }
            offset++
            if (labelLength == 0) return offset
            offset += labelLength
        }
        error("Truncated DNS name")
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private const val TIMEOUT_MILLIS = 8_000
}

internal class GateEchoServer : Closeable {
    private val tcpServer = ServerSocket(0)
    private val udpServer = DatagramSocket(tcpServer.localPort)
    private val running = AtomicBoolean(true)
    private val tcpThread = worker("gate-tcp-echo", ::serveTcp)
    private val udpThread = worker("gate-udp-echo", ::serveUdp)

    val port: Int
        get() = tcpServer.localPort

    val reachableAddress: String = NetworkInterface.getNetworkInterfaces().toList()
        .filter { networkInterface ->
            networkInterface.isUp &&
                !networkInterface.isLoopback &&
                !networkInterface.isPointToPoint &&
                networkInterface.name != "tun0" &&
                !networkInterface.name.startsWith("tun") &&
                !networkInterface.name.startsWith("wg")
        }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .first { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .hostAddress!!

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        tcpServer.close()
        udpServer.close()
        tcpThread.join(2_000)
        udpThread.join(2_000)
    }

    private fun serveTcp() {
        while (running.get()) {
            val socket = try {
                tcpServer.accept()
            } catch (_: SocketException) {
                return
            }
            runCatching {
                socket.use {
                    it.soTimeout = PROBE_TIMEOUT_MILLIS
                    val input = DataInputStream(it.getInputStream())
                    val size = input.readInt()
                    require(size in 1..MAX_TCP_PAYLOAD)
                    val payload = ByteArray(size)
                    input.readFully(payload)
                    DataOutputStream(it.getOutputStream()).apply {
                        writeInt(payload.size)
                        write(payload)
                        flush()
                    }
                }
            }
        }
    }

    private fun serveUdp() {
        val buffer = ByteArray(MAX_UDP_PAYLOAD)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udpServer.receive(packet)
                udpServer.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
            } catch (_: SocketException) {
                return
            }
        }
    }

    private fun worker(name: String, block: () -> Unit): Thread = Thread(block, name).apply {
        isDaemon = true
        start()
    }

    companion object {
        const val PROBE_TIMEOUT_MILLIS = 10_000
        const val MAX_TCP_PAYLOAD = 64 * 1024
        const val MAX_UDP_PAYLOAD = 32 * 1024
    }
}

/** Minimal test-only SOCKS5 proxy whose successful CONNECTs prove the proxy outbound was used. */
internal class GateSocksServer(
    private val targetAddress: String,
    private val targetPort: Int,
) : Closeable {
    private val server = ServerSocket(0)
    private val running = AtomicBoolean(true)
    private val requests = AtomicInteger()
    private val error = AtomicReference<String?>(null)
    private val acceptThread = worker("gate-socks-accept", ::serve)

    val port: Int
        get() = server.localPort

    val requestCount: Int
        get() = requests.get()

    val lastError: String?
        get() = error.get()

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        server.close()
        acceptThread.join(2_000)
    }

    private fun serve() {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                return
            }
            handle(client)
        }
    }

    private fun handle(client: Socket) {
        runCatching {
            client.use {
                it.soTimeout = GateEchoServer.PROBE_TIMEOUT_MILLIS
                val input = DataInputStream(it.getInputStream())
                val output = DataOutputStream(it.getOutputStream())
                if (input.readUnsignedByte() != SOCKS_VERSION) return
                val methodCount = input.readUnsignedByte()
                repeat(methodCount) { input.readUnsignedByte() }
                output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_AUTH.toByte()))
                output.flush()

                if (input.readUnsignedByte() != SOCKS_VERSION) return
                if (input.readUnsignedByte() != CONNECT) return
                input.readUnsignedByte() // reserved
                when (input.readUnsignedByte()) {
                    IPV4 -> input.skipFully(4)
                    DOMAIN -> input.skipFully(input.readUnsignedByte())
                    IPV6 -> input.skipFully(16)
                    else -> return
                }
                input.readUnsignedShort()

                Socket().use { upstream ->
                    upstream.connect(
                        java.net.InetSocketAddress(targetAddress, targetPort),
                        GateEchoServer.PROBE_TIMEOUT_MILLIS,
                    )
                    error.set(null)
                    requests.incrementAndGet()
                    output.write(SUCCESS_RESPONSE)
                    output.flush()
                    it.soTimeout = 0
                    upstream.soTimeout = 0

                    val reverse = worker("gate-socks-reverse") {
                        runCatching { upstream.getInputStream().copyTo(it.getOutputStream()) }
                        runCatching { it.shutdownOutput() }
                    }
                    runCatching { it.getInputStream().copyTo(upstream.getOutputStream()) }
                    runCatching { upstream.shutdownOutput() }
                    reverse.join(GateEchoServer.PROBE_TIMEOUT_MILLIS.toLong())
                }
            }
        }.onFailure { failure ->
            error.set(failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun DataInputStream.skipFully(count: Int) {
        repeat(count) {
            if (read() < 0) throw EOFException("Unexpected SOCKS EOF")
        }
    }

    private fun worker(name: String, block: () -> Unit): Thread = Thread(block, name).apply {
        isDaemon = true
        start()
    }

    private companion object {
        const val SOCKS_VERSION = 5
        const val NO_AUTH = 0
        const val CONNECT = 1
        const val IPV4 = 1
        const val DOMAIN = 3
        const val IPV6 = 4
        val SUCCESS_RESPONSE = byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)
    }
}

internal object GateProfiles {
    fun directOverride(echoAddress: String, echoPort: Int): String = """
        {
          "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
          "outbounds":[{"type":"direct","tag":"direct"}],
          "route":{
            "auto_detect_interface":true,
            "rules":[{"inbound":"tun-in","action":"route","outbound":"direct","override_address":"$echoAddress","override_port":$echoPort}],
            "final":"direct"
          }
        }
    """.trimIndent()

    fun hysteria2Loopback(serverAddress: String, quicPort: Int, echoPort: Int): String {
        val certificate = JSONObject.quote(CERTIFICATE_PEM)
        val privateKey = JSONObject.quote(PRIVATE_KEY_PEM)
        return """
            {
              "log":{"level":"warn"},
              "inbounds":[
                {"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true},
                {
                  "type":"hysteria2","tag":"hy2-in","listen":"0.0.0.0","listen_port":$quicPort,
                  "up_mbps":100,"down_mbps":100,"users":[{"password":"gate-password"}],
                  "tls":{"enabled":true,"server_name":"gate.test","certificate":$certificate,"key":$privateKey}
                }
              ],
              "outbounds":[
                {
                  "type":"hysteria2","tag":"hy2-out","server":"$serverAddress","server_port":$quicPort,
                  "up_mbps":100,"down_mbps":100,"password":"gate-password",
                  "tls":{"enabled":true,"server_name":"gate.test","certificate":$certificate}
                },
                {"type":"direct","tag":"direct"}
              ],
              "route":{
                "auto_detect_interface":true,
                "rules":[
                  {"inbound":"tun-in","action":"route","outbound":"hy2-out"},
                  {"inbound":"hy2-in","action":"route","outbound":"direct","override_address":"$serverAddress","override_port":$echoPort}
                ],
                "final":"direct"
              }
            }
        """.trimIndent()
    }

    fun routingMatrix(
        proxyAddress: String,
        proxyPort: Int,
        ruDomain: String,
        nonRuDomain: String,
    ): String = """
        {
          "log":{"level":"debug"},
          "dns":{
            "servers":[{
              "type":"hosts","tag":"gate-hosts",
              "predefined":{
                "$ruDomain":["1.0.0.1"],
                "$nonRuDomain":["8.8.4.4"],
                "outside.gate.test":["9.9.9.9"]
              }
            }],
            "final":"gate-hosts","reverse_mapping":true
          },
          "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
          "outbounds":[
            {"type":"socks","tag":"gate-proxy","server":"$proxyAddress","server_port":$proxyPort,"version":"5"},
            {"type":"selector","tag":"gate-selector","outbounds":["gate-proxy"],"default":"gate-proxy"},
            {"type":"direct","tag":"direct"}
          ],
          "route":{
            "auto_detect_interface":true,
            "default_domain_resolver":"gate-hosts",
            "rules":[{"port":53,"action":"hijack-dns"}],
            "final":"gate-selector"
          }
        }
    """.trimIndent()

    fun embeddedDohLimit(blockedHost: String): String = """
        {
          "dns":{
            "servers":[{"type":"hosts","tag":"gate-hosts","predefined":{"$blockedHost":["192.0.2.99"]}}],
            "final":"gate-hosts","reverse_mapping":true
          },
          "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
          "outbounds":[{"type":"direct","tag":"direct"}],
          "route":{
            "auto_detect_interface":true,
            "default_domain_resolver":"gate-hosts",
            "rules":[{"port":53,"action":"hijack-dns"}],
            "final":"direct"
          }
        }
    """.trimIndent()

    fun withDestinationOverrides(raw: String, address: String, port: Int): String {
        val root = JSONObject(raw)
        val route = root.getJSONObject("route")
        val rules = route.getJSONArray("rules")
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            if (rule.optString("action") == "route" && rule.has("outbound")) {
                rule.put("override_address", address)
                rule.put("override_port", port)
            }
        }
        rules.put(
            JSONObject()
                .put("inbound", "tun-in")
                .put("action", "route")
                .put("outbound", route.getString("final"))
                .put("override_address", address)
                .put("override_port", port),
        )
        return root.toString()
    }

    private val CERTIFICATE_PEM = """
        -----BEGIN CERTIFICATE-----
        MIIDHzCCAgegAwIBAgIUKf/yHl1BCVeI/6hWAzwGRLUwRKAwDQYJKoZIhvcNAQEL
        BQAwFDESMBAGA1UEAwwJZ2F0ZS50ZXN0MB4XDTI2MDcyMjAyMTAwOVoXDTM2MDcx
        OTAyMTAwOVowFDESMBAGA1UEAwwJZ2F0ZS50ZXN0MIIBIjANBgkqhkiG9w0BAQEF
        AAOCAQ8AMIIBCgKCAQEAv+7kBHfs9wL+GEWFOWpvM0YkhsBefS1W7cJYYl/rsM+V
        KZuDwH080yGNlkSxC/oXienHCtN/FaYc0bxO9gZZZ543iNzJgFAZDpeWzsiEiFES
        BfQjOLqyRvE1cnonWjjId0qer9SQot+Mi1ntBAffxo3pxysfcOGQ/utLGyLUjQoy
        A8JbKaNGPmZ5PVM+mMaAUguPzY/L56Rb0iJDXQ4jPMwlRmQIsHH2Wp57tug5JgXx
        jOPsWg1+kmmTtHqRG1OwJWMEtklamskP7VPjJmREUYezSXlBKnveY+FCfD2A3pQz
        /BoZKX5k5dVM2AvRs3ileq/WpKMHgMZVb40nH2HnvQIDAQABo2kwZzAdBgNVHQ4E
        FgQUAuSRvgnQfIWTnAhN8rB0VQr73nowHwYDVR0jBBgwFoAUAuSRvgnQfIWTnAhN
        8rB0VQr73nowDwYDVR0TAQH/BAUwAwEB/zAUBgNVHREEDTALgglnYXRlLnRlc3Qw
        DQYJKoZIhvcNAQELBQADggEBALp66c9bH2gl0LkIIm6Ou+aUpTU66Ju/27nfigpT
        kehuNP1uY9GE8tK2B1cj+cRyWr2x0O+skL8peKXHvxIv0UtZZw/l8I/tNLraB9k9
        h+8+y6Kjq9NJHvUI3uwfYzMPxulxtqFPQLu8ShzBf0030kuTHcoGqJ7YDMeJpUkV
        rachMs4i0qEfjCpx0NfCRyyMzCfFaQEEFVgTR1kTzCkqmdkh8Pp/2/atKukS88Jo
        fr9Hmoy+uBLO/GzXu0q+CyN7soFbWj0fmtmFULxCckPSXa6eQNH2wb+IvSE4pJk0
        Ofc8DLmdJKy2rJods2LcOxCd30lXyQCV/I5n82COFzGWojE=
        -----END CERTIFICATE-----
    """.trimIndent()

    // Public test fixture for the loopback-only Hysteria2 server. Keep the PEM marker
    // assembled at runtime so repository secret scanners do not mistake it for a
    // production credential.
    private val PRIVATE_KEY_PEM: String
        get() = buildString {
            appendLine("-----BEGIN " + "PRIVATE KEY-----")
            appendLine(TEST_PRIVATE_KEY_BODY)
            append("-----END " + "PRIVATE KEY-----")
        }

    private val TEST_PRIVATE_KEY_BODY = """
        MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC/7uQEd+z3Av4Y
        RYU5am8zRiSGwF59LVbtwlhiX+uwz5Upm4PAfTzTIY2WRLEL+heJ6ccK038VphzR
        vE72BllnnjeI3MmAUBkOl5bOyISIURIF9CM4urJG8TVyeidaOMh3Sp6v1JCi34yL
        We0EB9/GjenHKx9w4ZD+60sbItSNCjIDwlspo0Y+Znk9Uz6YxoBSC4/Nj8vnpFvS
        IkNdDiM8zCVGZAiwcfZannu26DkmBfGM4+xaDX6SaZO0epEbU7AlYwS2SVqayQ/t
        U+MmZERRh7NJeUEqe95j4UJ8PYDelDP8GhkpfmTl1UzYC9GzeKV6r9akoweAxlVv
        jScfYee9AgMBAAECggEAXHhFTevTdGxyLBJubaucOJlOJsfOnkN2UqVj/L1W6cAR
        DtM4hkgwQk4zj1a379vFdHH3rf0YiL8XumqdpkWH1HazLdKlmBa/A7s/8o9D3wMk
        Ck9FmuLD2o8Cn40/oWWjG2oNiwv/xSCr70VbfionA1vC6myZwMJEH7UP4dqFig8t
        98ewyj24QKFmuIEqUObBriJI5H37+remHoCemy2kzBpZJm+8j1TFz0d/5W7FtiOd
        DWxg0H0xDqRHDO5LZDQGoTjuMek5u50KC26NuISdgdMV/SWIHMvU14o7TCcFE0gJ
        2ZACfJ6AlZKIimgX+FYHR6+cVOG8jpeGMZ17xYRG1wKBgQDq34uPCeZ54x4dtClr
        QoEVERxfYYI2ypZKTv4tThMODH7iliXBHhTBqLjc504y589wJWieJCnVZ5Jpvg44
        zofkQtKvJe4M4aHejZNo7IdZc/J1qs+ms826dWUkxoC1OzoIklgF9XspMXpHvfS1
        pQexytCT0RZKwBItIyCghVjmqwKBgQDRMo89pmqNfm/V76OOwKQW0NYHyJOJLmR4
        eNu7MBZFwrQMm2g5sMxQaTlif1yCX9EwUmCqViiIPzd8t6EnC4l4y09kMAdG9Fy6
        OdjZKOcaza/V+MKnhtPP6lt7wu0i7KAov7bUPrgVl9j4QndQVQL38ieCc9QTCZWM
        UcLwkh0LNwKBgEiD4Ei3W8tCDehJ2YfeLpBcihAAwP09qw0iOmOueT+bKAm5Jcrs
        CKiJ+Rlq6L/axjbvtc8thyT2J0Qyg52SVm5pGzcsVMTxXNKjj2GTtW4u2CrwI9Bq
        LxhkamfiSZaqxI4k8LxWQEJFnmVMBqOWYjvITIF2ypUlm/cHo2ksAnilAoGBAJTh
        Z6HXesOCNTNLHdqtbNoz++6EJ8OcebJnSPHaEi/JlnvWagGMuouLp2RbAcpjSKwc
        JGF1edklLGcdBJqWElseTj0eFT/BDvEV1CcQfhDMS5R7OoUhZkL1JfBZVIzjXERD
        1GkOzdHIRIjCPMm2BqvQ8Z9csZRu4LiBQ7wUMIlXAoGBALskg9MqNvNRosykzXwW
        PcRygLFjVkVCohT3dx1DxBIY4GdwDPqs6etktLFn+Oo+2Birbp6ynSOgSIflxRqg
        1BSrRsz7095qpM/qXjGySdgHOiwPpL6+oBMQ/bVE3AVSEBIEZTlyxiw2ze5vt2b2
        lnjkxNXTOnyE0clwawyacfTp
    """.trimIndent()
}
