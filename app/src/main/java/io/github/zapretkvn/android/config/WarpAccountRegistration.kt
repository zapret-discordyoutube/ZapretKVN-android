package io.github.zapretkvn.android.config

import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Регистрация аккаунта WARP самим приложением (владелец 2026-11-25), как в Windows `network/warp_registration.py`.
 *
 * Ядро регистрирует аккаунт само при первом запуске, но провайдер может сбрасывать этот единственный HTTPS-запрос к API
 * Cloudflare (отчёт с Windows 2026-11-25: «An existing connection was forcibly closed by the remote host»), и без
 * аккаунта вход через WARP не поднимается. Поэтому приложение регистрирует аккаунт заранее и пишет файл, который читает
 * ядро (`profile.cache_path`, патч 0022): ядро находит его и API не вызывает.
 *
 * Пути по очереди: обычный запрос; тот же запрос с ClientHello, разрезанным внутри имени сервера на два TCP-сегмента (цензор,
 * который ищет имя в первом сегменте, его не видит); оба для каждого другого адреса хоста API. Блокирующий вызов: только
 * из фонового потока. В журнал уходит только путь и итог, адреса и ключи — никогда.
 */
object WarpAccountRegistration {
    const val API_HOST = "api.cloudflareclient.com"
    private const val API_PATH = "/v0a4471/reg"
    private const val API_PORT = 443
    private const val MAX_RESPONSE = 64 * 1024

    /** Адреса хоста API, замеченные в поле: пробуются после тех, что дал резолвер (цензор может закрыть один из них). */
    // Addresses of the API seen to answer (the service's host, 2026-11-27: the name then resolved only to 8.6.112.0 and 8.47.69.0 and both
    // timed out there, while the API answered POST 200 on 104.19.192.1, 104.19.193.1 and 162.159.137.105): the ones that answered first.
    val KNOWN_ADDRESSES = listOf("104.19.192.1", "162.159.137.105", "104.19.193.1", "162.159.138.105", "8.6.112.0", "8.47.69.0")

    private val HEADERS = linkedMapOf(
        "User-Agent" to "WARP for Android",
        "CF-Client-Version" to "a-6.35-4471",
        "Content-Type" to "application/json; charset=UTF-8",
    )

    data class Outcome(val ok: Boolean, val route: String, val detail: String, val elapsedMs: Long = 0)

    fun accountFileReady(file: File): Boolean = runCatching {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        root["private_key"]?.jsonPrimitive?.content?.isNotEmpty() == true &&
            root["peers"]?.jsonArray?.isNotEmpty() == true &&
            root["interface"]?.jsonObject?.get("addresses")?.jsonObject?.get("v4")?.jsonPrimitive?.content?.isNotEmpty() == true
    }.getOrDefault(false)

    /** Время (nanoTime), когда прямые пути последний раз не сработали; 0, если не срабатывали. */
    @Volatile
    private var directFailedAt = 0L

    /** После неудачи прямые пути пропускаются на столько: каждый упирается в свой таймаут, подключение их ждать не должно. */
    private const val DIRECT_RETRY_MS = 600_000L

    /**
     * Файл аккаунта есть; если нет, он регистрируется по путям выше.
     *
     * [budgetMs] это всё время вызова, что бы ни делали пути (поле 2026-11-26, Windows 0.8.121: сеть блокировала API, пути
     * один за другим упирались в таймауты, и подключение не укладывалось в свой срок): каждому пути достаётся не больше,
     * чем осталось. После неудачи следующие подключения десять минут не ждут прямые пути.
     */
    fun ensureAccount(
        file: File,
        addresses: () -> List<String> = { emptyList() },
        timeoutMs: Int = 8000,
        budgetMs: Int = 20_000,
        exchanger: (String?, ByteArray, Boolean, Int) -> ByteArray = ::exchange,
        viaVpn: Boolean = false,
    ): Outcome {
        if (accountFileReady(file)) return Outcome(true, "cached", "account file is there")
        val started = System.nanoTime()
        fun took() = (System.nanoTime() - started) / 1_000_000
        val failedAt = directFailedAt
        // Through the VPN the provider cannot see the name, so there is one plain route and no back-off to honour.
        if (!viaVpn && failedAt != 0L && started - failedAt < DIRECT_RETRY_MS * 1_000_000L) {
            return Outcome(false, "skipped", "the direct routes failed a moment ago", 0)
        }
        val keys = newKey()
        val body = registrationBody(keys.second)
        val found = if (viaVpn) emptyList() else runCatching(addresses).getOrDefault(emptyList())
        val targets = ArrayList<String?>().apply { add(null) }
        if (!viaVpn) for (address in found + KNOWN_ADDRESSES) if (address !in targets) targets.add(address)
        var last = Outcome(false, "none", "no route tried", 0)
        for ((index, target) in targets.withIndex()) {
            for (fragmented in if (viaVpn) listOf(false) else listOf(false, true)) {
                val name = if (viaVpn) "vpn" else (if (index == 0) "api" else "api-$index") + if (fragmented) "-fragmented" else ""
                val remaining = budgetMs - took()
                if (remaining < 2_000) {
                    if (!viaVpn) directFailedAt = System.nanoTime()
                    return Outcome(false, name, "time budget of ${budgetMs / 1000} s is used up", took())
                }
                last = try {
                    // A route may need two timeouts (connect, then the whole exchange): never more than what is left.
                    val raw = exchanger(target, body, fragmented, minOf(timeoutMs.toLong(), remaining / 2).toInt())
                    writeAccount(file, keys.first, raw)
                    return Outcome(true, name, "registered", took())
                } catch (error: Exception) {
                    Outcome(false, name, "${error.javaClass.simpleName}: ${error.message}".take(160), took())
                }
            }
        }
        if (!viaVpn) directFailedAt = System.nanoTime()
        return last
    }

    /** Один путь к API: [target] null это адрес из резолвера, [fragmented] разрезает ClientHello. */
    data class Route(val name: String, val target: String?, val fragmented: Boolean)

    /** Пути по очереди: обычный и с разрезом для каждого адреса (резолвер, затем замеченные в поле). */
    internal fun routes(addresses: () -> List<String>): List<Route> {
        val found = runCatching(addresses).getOrDefault(emptyList())
        val targets = ArrayList<String?>().apply { add(null) }
        for (address in found + KNOWN_ADDRESSES) if (address !in targets) targets.add(address)
        return targets.withIndex().flatMap { (index, target) ->
            listOf(false, true).map { fragmented ->
                Route((if (index == 0) "api" else "api-$index") + if (fragmented) "-fragmented" else "", target, fragmented)
            }
        }
    }

    /** Только для тестов: забыть недавнюю неудачу. */
    internal fun forgetFailure() {
        directFailedAt = 0L
    }

    /** Пара ключей WireGuard (X25519): закрытый и открытый, base64. */
    fun newKey(random: SecureRandom = SecureRandom()): Pair<String, String> {
        val private = ByteArray(32).also(random::nextBytes)
        val public = X25519.publicKey(private)
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(private) to encoder.encodeToString(public)
    }

    fun registrationBody(publicKey: String): ByteArray {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000'+00:00'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val serial = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        return JsonObject(
            linkedMapOf(
                "key" to JsonPrimitive(publicKey),
                "install_id" to JsonPrimitive(""),
                "fcm_token" to JsonPrimitive(""),
                "tos" to JsonPrimitive(stamp),
                "model" to JsonPrimitive("PC"),
                "serial_number" to JsonPrimitive(serial),
                "os_version" to JsonPrimitive(""),
                "key_type" to JsonPrimitive("curve25519"),
                "tunnel_type" to JsonPrimitive("wireguard"),
                "locale" to JsonPrimitive("en-US"),
            ),
        ).toString().toByteArray()
    }

    /** Ответ API -> файл, который читает ядро: `{"private_key", "interface", "peers"}`. Пишется целиком, атомарно. */
    internal fun writeAccount(file: File, privateKey: String, raw: ByteArray) {
        val config = Json.parseToJsonElement(String(raw)).jsonObject["config"]?.jsonObject ?: error("the answer holds no account")
        val iface = config["interface"]?.jsonObject ?: error("the answer holds no account")
        val peers = config["peers"]?.jsonArray ?: error("the answer holds no account")
        check(iface["addresses"]?.jsonObject?.get("v4")?.jsonPrimitive?.content?.isNotEmpty() == true && peers.isNotEmpty()) {
            "the answer holds no account"
        }
        val document = JsonObject(linkedMapOf("private_key" to JsonPrimitive(privateKey), "interface" to iface, "peers" to peers))
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(".warp-profile-", null, file.parentFile)
        try {
            temporary.writeText(document.toString())
            if (!temporary.renameTo(file)) error("cannot keep the account file")
        } finally {
            temporary.delete()
        }
    }

    /** Один HTTPS POST к API; ClientHello при [fragmented] уходит двумя сегментами внутри имени сервера. */
    internal fun exchange(address: String?, body: ByteArray, fragmented: Boolean, timeoutMs: Int): ByteArray =
        request(address, "POST", API_PATH, null, body, fragmented, timeoutMs)

    /**
     * Один HTTPS-запрос к API по тому же пути с тем же разрезом ClientHello: регистрация устройства (POST) и запись ключа
     * MASQUE (PATCH `/reg/<id>` с токеном устройства, [WarpMasqueAccount]). Тело ответа при коде 200.
     */
    internal fun request(
        address: String?,
        method: String,
        path: String,
        token: String?,
        body: ByteArray,
        fragmented: Boolean,
        timeoutMs: Int,
    ): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 2_000_000L
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(InetAddress.getByName(address ?: API_HOST), API_PORT), timeoutMs)
            val engine = SSLContext.getDefault().createSSLEngine(API_HOST, API_PORT).apply {
                useClientMode = true
                sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(API_HOST)) }
            }
            val session = engine.session
            val network = ByteBuffer.allocate(session.packetBufferSize * 2)
            val plain = ByteBuffer.allocate(session.applicationBufferSize * 2)
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            var firstFlight = true

            fun send(buffer: ByteBuffer, split: Boolean) {
                buffer.flip()
                val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                buffer.clear()
                if (bytes.isEmpty()) return
                if (split) {
                    val cut = nameOffset(bytes)
                    out.write(bytes, 0, cut)
                    out.flush()
                    Thread.sleep(50) // a separate TCP segment, not merged by the stack
                    out.write(bytes, cut, bytes.size - cut)
                } else {
                    out.write(bytes)
                }
                out.flush()
            }

            fun readMore() {
                if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException("no answer in time")
                val chunk = ByteArray(16384)
                val count = input.read(chunk)
                if (count < 0) throw java.io.EOFException("closed by the remote host")
                network.put(chunk, 0, count)
            }

            fun unwrapOnce(): SSLEngineResult {
                if (network.position() == 0) readMore()
                while (true) {
                    network.flip()
                    val result = engine.unwrap(network, plain)
                    network.compact()
                    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) { readMore(); continue }
                    return result
                }
            }

            engine.beginHandshake()
            val outgoing = ByteBuffer.allocate(session.packetBufferSize * 2)
            var status = engine.handshakeStatus
            while (status != SSLEngineResult.HandshakeStatus.FINISHED && status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                when (status) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        engine.wrap(ByteBuffer.allocate(0), outgoing)
                        send(outgoing, split = fragmented && firstFlight)
                        firstFlight = false
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        var task = engine.delegatedTask
                        while (task != null) { task.run(); task = engine.delegatedTask }
                    }
                    // NEED_UNWRAP, and NEED_UNWRAP_AGAIN where the platform has it
                    else -> unwrapOnce()
                }
                status = engine.handshakeStatus
            }
            // The chain is trusted by the engine's default trust manager during the handshake; the name is checked here.
            check(certificateCovers(engine.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate, API_HOST)) {
                "certificate is not for the API host"
            }

            val head = "$method $path HTTP/1.1\r\nHost: $API_HOST\r\n" +
                HEADERS.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
                (if (token != null) "Authorization: Bearer $token\r\n" else "") +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            engine.wrap(ByteBuffer.wrap(head.toByteArray() + body), outgoing)
            send(outgoing, split = false)

            val received = java.io.ByteArrayOutputStream()
            while (received.size() <= MAX_RESPONSE) {
                val result = try { unwrapOnce() } catch (_: java.io.EOFException) { break }
                plain.flip()
                received.write(ByteArray(plain.remaining()).also(plain::get))
                plain.clear()
                if (result.status == SSLEngineResult.Status.CLOSED) break
            }
            return bodyOf200(received.toByteArray())
        }
    }

    /** Имя в сертификате: точное или подстановочное (`*.example.com` покрывает ровно один уровень слева). */
    internal fun certificateCovers(certificate: java.security.cert.X509Certificate?, host: String): Boolean {
        val names = certificate?.subjectAlternativeNames.orEmpty()
            .filter { it.size >= 2 && it[0] == 2 }.mapNotNull { it[1] as? String }.map { it.lowercase() }
        val wanted = host.lowercase()
        return names.any { name ->
            name == wanted || (name.startsWith("*.") && wanted.substringAfter('.', "") == name.substring(2) && wanted.substringBefore('.').isNotEmpty())
        }
    }

    /** Несколько байт внутри имени сервера; середина записи, если имя не найдено. */
    internal fun nameOffset(clientHello: ByteArray): Int {
        val name = API_HOST.toByteArray()
        outer@ for (start in 0..clientHello.size - name.size) {
            for (i in name.indices) if (clientHello[start + i] != name[i]) continue@outer
            return start + 3
        }
        return maxOf(1, clientHello.size / 2)
    }

    private fun bodyOf200(raw: ByteArray): ByteArray {
        val text = String(raw, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        require(split > 0) { "API answered nothing" }
        val head = text.substring(0, split)
        val status = head.lineSequence().first().split(" ").getOrNull(1)
        require(status == "200") { "API answered $status" }
        var data = raw.copyOfRange(split + 4, raw.size)
        if (head.lowercase().contains("transfer-encoding: chunked")) {
            val out = java.io.ByteArrayOutputStream()
            var rest = String(data, Charsets.ISO_8859_1)
            var offset = 0
            while (offset < rest.length) {
                val lineEnd = rest.indexOf("\r\n", offset)
                if (lineEnd < 0) break
                val size = rest.substring(offset, lineEnd).substringBefore(';').trim().ifEmpty { "0" }.toInt(16)
                if (size == 0) break
                out.write(data, lineEnd + 2, size)
                offset = lineEnd + 2 + size + 2
            }
            data = out.toByteArray()
        }
        return data
    }

    /** X25519 по RFC 7748 (лесенка Монтгомери): ключ WireGuard без внешней библиотеки, minSdk 26 не имеет XDH. */
    internal object X25519 {
        private val P = BigInteger.valueOf(2).pow(255) - BigInteger.valueOf(19)
        private val A24 = BigInteger.valueOf(121665)

        fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, ByteArray(32).also { it[0] = 9 })

        fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
            val k = scalar.copyOf().also {
                it[0] = (it[0].toInt() and 248).toByte()
                it[31] = ((it[31].toInt() and 127) or 64).toByte()
            }
            val u = decode(point.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() })
            val kInt = decode(k)
            var x2 = BigInteger.ONE
            var z2 = BigInteger.ZERO
            var x3 = u
            var z3 = BigInteger.ONE
            var swap = false
            for (t in 254 downTo 0) {
                val bit = kInt.testBit(t)
                if (swap != bit) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
                swap = bit
                val a = (x2 + z2).mod(P)
                val aa = (a * a).mod(P)
                val b = (x2 - z2).mod(P)
                val bb = (b * b).mod(P)
                val e = (aa - bb).mod(P)
                val c = (x3 + z3).mod(P)
                val d = (x3 - z3).mod(P)
                val da = (d * a).mod(P)
                val cb = (c * b).mod(P)
                x3 = (da + cb).pow(2).mod(P)
                z3 = (u * (da - cb).pow(2)).mod(P)
                x2 = (aa * bb).mod(P)
                z2 = (e * (aa + A24 * e)).mod(P)
            }
            if (swap) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            return encode((x2 * z2.modPow(P - BigInteger.valueOf(2), P)).mod(P))
        }

        private fun decode(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

        private fun encode(value: BigInteger): ByteArray {
            val big = value.toByteArray().reversedArray()
            return ByteArray(32).also { System.arraycopy(big, 0, it, 0, minOf(32, big.size)) }
        }
    }
}
