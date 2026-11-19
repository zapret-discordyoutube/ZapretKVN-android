package io.github.zapretkvn.clientreports

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Один ClientHello TLS 1.3 с выбранным именем (SNI) для проверки [BlockDiagnosis]: шлётся в голом TCP,
 * и читается только первый байт ответа. Своя сборка, а не системный TLS: вывод не зависит от проверки
 * сертификата и сообщений исключений, а Windows шлёт те же байты (`contract.py`, `client_hello`).
 *
 * Сервер, который получил ClientHello, отвечает записью TLS — ServerHello (0x16) или alert (0x15),
 * даже если имя ему чужое. Reality отдаёт чужое имя своему сайту-образцу, и тот тоже отвечает. Тишина
 * или сброс после ClientHello — фильтр по пути.
 */
object TlsProbe {
    const val CONNECT_TIMEOUT_MILLIS = 4_000
    const val ANSWER_TIMEOUT_MILLIS = 5_000

    /** Нейтральные имена: открыты в любом режиме фильтрации, в том числе в «белых списках». */
    val NEUTRAL_NAMES = listOf("ya.ru", "vk.com", "www.gosuslugi.ru")

    private val CIPHERS = intArrayOf(0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8)
    private val SIGNATURES = intArrayOf(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601)

    fun clientHello(serverName: String, random: Random): ByteArray {
        val name = serverName.trimEnd('.').toByteArray(Charsets.US_ASCII)
        val extensions = ByteArrayOutputStream().apply {
            // server_name
            extension(0x0000, bytes { u16(name.size + 3); u8(0); u16(name.size); write(name) })
            // extended_master_secret, renegotiation_info
            extension(0x0017, ByteArray(0))
            extension(0xff01, byteArrayOf(0))
            // supported_groups: x25519, secp256r1, secp384r1
            extension(0x000a, bytes { u16(6); u16(0x001d); u16(0x0017); u16(0x0018) })
            // ec_point_formats: uncompressed
            extension(0x000b, byteArrayOf(1, 0))
            // signature_algorithms
            extension(0x000d, bytes { u16(SIGNATURES.size * 2); SIGNATURES.forEach { u16(it) } })
            // ALPN: h2, http/1.1
            extension(0x0010, bytes {
                val h2 = "h2".toByteArray(); val h1 = "http/1.1".toByteArray()
                u16(2 + h2.size + h1.size); u8(h2.size); write(h2); u8(h1.size); write(h1)
            })
            // supported_versions: TLS 1.3, TLS 1.2
            extension(0x002b, bytes { u8(4); u16(0x0304); u16(0x0303) })
            // psk_key_exchange_modes: psk_dhe_ke
            extension(0x002d, byteArrayOf(1, 1))
            // key_share: x25519 (случайные 32 байта неотличимы от настоящего ключа)
            extension(0x0033, bytes { u16(36); u16(0x001d); u16(32); write(random.nextBytes(32)) })
        }.toByteArray()
        val body = bytes {
            u16(0x0303)
            write(random.nextBytes(32))
            u8(32); write(random.nextBytes(32))
            u16(CIPHERS.size * 2); CIPHERS.forEach { u16(it) }
            u8(1); u8(0)
            u16(extensions.size); write(extensions)
        }
        val handshake = bytes { u8(1); u24(body.size); write(body) }
        return bytes { u8(0x16); u16(0x0301); u16(handshake.size); write(handshake) }
    }

    /** Первый байт ответа на ClientHello; -1 — соединение закрыто без ответа. */
    fun judgeFirstByte(first: Int): ProbeResult = if (first < 0) ProbeResult.Reset else ProbeResult.Answered

    /** SNI из собранного [clientHello] (для тестов и журнала). */
    fun serverNameOf(hello: ByteArray): String? = runCatching {
        var at = 5 + 4 + 2 + 32
        at += 1 + (hello[at].toInt() and 0xff)
        at += 2 + u16(hello, at)
        at += 1 + (hello[at].toInt() and 0xff)
        val end = at + 2 + u16(hello, at)
        at += 2
        while (at < end) {
            val type = u16(hello, at)
            val length = u16(hello, at + 2)
            if (type == 0) {
                val nameLength = u16(hello, at + 7)
                return String(hello, at + 9, nameLength, Charsets.US_ASCII)
            }
            at += 4 + length
        }
        null
    }.getOrNull()

    private fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff shl 8) or (bytes[at + 1].toInt() and 0xff)

    private inline fun bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()

    private fun ByteArrayOutputStream.u8(value: Int) = write(value and 0xff)
    private fun ByteArrayOutputStream.u16(value: Int) { u8(value shr 8); u8(value) }
    private fun ByteArrayOutputStream.u24(value: Int) { u8(value shr 16); u16(value) }
    private fun ByteArrayOutputStream.extension(type: Int, data: ByteArray) { u16(type); u16(data.size); write(data) }
}
