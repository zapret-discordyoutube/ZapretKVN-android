package io.github.zapretkvn.networkbootstrap

import android.net.Network
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * DNS-сообщения для DoH: запрос и разбор ответа без сторонних библиотек.
 */
object DnsWire {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    class Answer(val rcode: Int, val addresses: List<InetAddress>)

    fun buildQuery(hostname: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // ID 0: RFC 8484 просит нулевой идентификатор, чтобы ответы кешировались.
        out.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        val ascii = IDN.toASCII(hostname.trim().trimEnd('.'), IDN.ALLOW_UNASSIGNED)
        for (label in ascii.split('.')) {
            require(label.isNotEmpty() && label.length <= 63) { "Некорректное имя сервера." }
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf(0, type.toByte(), 0, 1))
        return out.toByteArray()
    }

    fun parse(payload: ByteArray): Answer {
        require(payload.size >= 12) { "Короткий DNS-ответ." }
        val rcode = payload[3].toInt() and 0x0F
        val questions = u16(payload, 4)
        val answers = u16(payload, 6)
        var offset = 12
        repeat(questions) { offset = skipName(payload, offset) + 4 }
        val addresses = ArrayList<InetAddress>(answers)
        repeat(answers) {
            offset = skipName(payload, offset)
            require(offset + 10 <= payload.size) { "Оборванная DNS-запись." }
            val type = u16(payload, offset)
            val length = u16(payload, offset + 8)
            offset += 10
            require(offset + length <= payload.size) { "Оборванная DNS-запись." }
            if ((type == TYPE_A && length == 4) || (type == TYPE_AAAA && length == 16)) {
                addresses += InetAddress.getByAddress(payload.copyOfRange(offset, offset + length))
            }
            offset += length
        }
        return Answer(rcode, addresses)
    }

    private fun u16(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun skipName(data: ByteArray, start: Int): Int {
        var offset = start
        while (true) {
            require(offset < data.size) { "Оборванное имя в DNS-ответе." }
            val length = data[offset].toInt() and 0xFF
            if (length and 0xC0 == 0xC0) return offset + 2
            if (length == 0) return offset + 1
            offset += 1 + length
        }
    }
}

/** Итог опроса одного доверенного резолвера. */
sealed interface TrustedDnsOutcome {
    class Addresses(val value: List<InetAddress>) : TrustedDnsOutcome
    object NameMissing : TrustedDnsOutcome
    object Unavailable : TrustedDnsOutcome
}

object TrustedDnsPolicy {
    /**
     * Свести ответы нескольких резолверов. Имя считается несуществующим, только
     * когда так ответили все опрошенные: один сбойный сервер не должен
     * превращать недоступность в «сервер переехал».
     */
    fun merge(outcomes: List<TrustedDnsOutcome>): TrustedDnsOutcome {
        outcomes.firstOrNull { it is TrustedDnsOutcome.Addresses }?.let { return it }
        return if (outcomes.isNotEmpty() && outcomes.all { it is TrustedDnsOutcome.NameMissing }) {
            TrustedDnsOutcome.NameMissing
        } else {
            TrustedDnsOutcome.Unavailable
        }
    }

    fun usable(address: InetAddress): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress
}

/**
 * Адрес VPN-сервера по DoH у резолверов, заданных литеральным IP.
 *
 * Системный резолвер на российских сетях отвечает NXDOMAIN на заблокированные
 * имена, а имена DoH-серверов обрывают по SNI. У соединения с IP-адресом SNI
 * нет, а сертификат проверяется обычным образом по IP из SAN. Запрос идёт по
 * физической сети, мимо туннеля.
 */
class TrustedDohResolver(
    private val servers: List<String> = DEFAULT_SERVERS,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) {
    // Запросы живут в собственной области: блокирующий сетевой ввод-вывод не
    // прерывается отменой, и ожидание самых медленных серверов не должно
    // задерживать ответ, который уже получен от быстрого.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun resolve(network: Network, hostname: String): TrustedDnsOutcome {
        val winner = CompletableDeferred<TrustedDnsOutcome>()
        val pending = servers.map { server ->
            scope.async {
                val outcome = try {
                    runInterruptible { query(network, server, hostname) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    TrustedDnsOutcome.Unavailable
                }
                if (outcome is TrustedDnsOutcome.Addresses) winner.complete(outcome)
                outcome
            }
        }
        val collector = scope.launch {
            winner.complete(TrustedDnsPolicy.merge(pending.map { it.await() }))
        }
        try {
            return winner.await()
        } finally {
            collector.cancel()
            pending.forEach { it.cancel() }
        }
    }

    private fun query(network: Network, server: String, hostname: String): TrustedDnsOutcome {
        val found = LinkedHashMap<String, InetAddress>()
        for (type in intArrayOf(DnsWire.TYPE_A, DnsWire.TYPE_AAAA)) {
            val answer = exchange(network, server, DnsWire.buildQuery(hostname, type))
            if (answer.rcode == DnsResponseClassifier.RCODE_NAME_ERROR) {
                if (found.isEmpty()) return TrustedDnsOutcome.NameMissing
                break
            }
            if (answer.rcode != DnsResponseClassifier.RCODE_SUCCESS) return TrustedDnsOutcome.Unavailable
            answer.addresses.filter(TrustedDnsPolicy::usable).forEach { found.putIfAbsent(it.hostAddress.orEmpty(), it) }
        }
        return if (found.isEmpty()) TrustedDnsOutcome.Unavailable else TrustedDnsOutcome.Addresses(found.values.toList())
    }

    private fun exchange(network: Network, server: String, query: ByteArray): DnsWire.Answer {
        val connection = network.openConnection(URL("https://$server/dns-query")) as HttpsURLConnection
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/dns-message")
            connection.setRequestProperty("Accept", "application/dns-message")
            connection.setFixedLengthStreamingMode(query.size)
            connection.outputStream.use { it.write(query) }
            check(connection.responseCode == 200) { "DoH HTTP ${connection.responseCode}" }
            val body = connection.inputStream.use { stream ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (buffer.size() <= MAX_RESPONSE_BYTES) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
            check(body.size <= MAX_RESPONSE_BYTES) { "DoH response too large" }
            return DnsWire.parse(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        // У всех сертификат содержит IP в SAN. Quad9 сюда не входит: он отвечает
        // только по HTTP/2, а HttpsURLConnection говорит HTTP/1.1.
        val DEFAULT_SERVERS = listOf("8.8.8.8", "1.1.1.1", "94.140.14.14", "8.8.4.4", "1.0.0.1")
        const val DEFAULT_TIMEOUT_MILLIS = 2_500
        private const val MAX_RESPONSE_BYTES = 65_535
    }
}
