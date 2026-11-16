package io.github.zapretkvn.masking.wireguard

/**
 * Validates an AmneziaWG signature packet chain (I1-I5) against the parser of the core we
 * ship: amneziawg-go v3.1.20261116 `device/obf.go` `obfBuilders`.
 *
 * The core knows `b t r rc rd d ds dz`. `<c>` (a packet counter in older docs) is NOT among
 * them: the core refuses the whole config with "unknown tag <c>". `d ds dz` write the packet
 * payload, which a signature packet does not have, so the masking editor offers only the five
 * tags that make sense before a handshake. The core has no size cap of its own (parseObfLen
 * only refuses negatives), so the cap here keeps one packet inside a common path MTU:
 * a larger packet is fragmented by IP, which is itself a visible oddity.
 */
object SignatureChain {
    /** Tags offered in the masking editor; all are accepted by amneziawg-go `obfBuilders`. */
    val TAGS = setOf("b", "t", "r", "rc", "rd")

    /** Tags the core rejects outright (no builder); an imported profile with them cannot start. */
    val CORE_UNKNOWN = setOf("c")

    /** Largest signature packet in bytes: 1500 Ethernet MTU - 20 IPv4 - 8 UDP, rounded down. */
    const val MAX_PACKET_BYTES = 1400

    /** Size of `<t>`: a 32-bit timestamp (obf_timestamp.go). */
    private const val TIMESTAMP_BYTES = 4

    private val HEX = Regex("^[0-9a-fA-F]+$")
    private val WHITESPACE = Regex("\\s+")

    sealed interface Result {
        data class Valid(val bytes: Int) : Result

        data class Invalid(val reason: String) : Result
    }

    fun validate(chain: String): Result {
        var cursor = 0
        var bytes = 0
        var tags = 0
        while (cursor < chain.length) {
            if (chain[cursor].isWhitespace()) {
                cursor++
                continue
            }
            if (chain[cursor] != '<') return Result.Invalid("Текст вне тегов: допускаются только теги вида <b 0x…>, <r 16>.")
            val end = chain.indexOf('>', cursor + 1)
            if (end < 0) return Result.Invalid("Нет закрывающего символа «>».")
            val parts = chain.substring(cursor + 1, end).trim().split(WHITESPACE).filter(String::isNotEmpty)
            if (parts.isEmpty() || parts.size > 2) return Result.Invalid("Пустой или неверный тег.")
            val tag = parts[0]
            val argument = parts.getOrNull(1)
            bytes += when (tag) {
                "b" -> {
                    // obf_bytes.go strips only a lowercase "0x"; "0X" would reach hex.DecodeString and fail.
                    val hex = argument?.removePrefix("0x").orEmpty()
                    if (hex.isEmpty() || hex.length % 2 != 0 || !HEX.matches(hex)) {
                        return Result.Invalid("Тег <b> должен содержать байты в hex, например <b 0x0100>.")
                    }
                    hex.length / 2
                }
                "r", "rc", "rd" -> {
                    val size = argument?.toIntOrNull()
                    if (size == null || size < 1 || size > MAX_PACKET_BYTES) {
                        return Result.Invalid("Тег <$tag> ждёт размер от 1 до $MAX_PACKET_BYTES байт.")
                    }
                    size
                }
                "t" -> {
                    if (argument != null) return Result.Invalid("Тег <t> не принимает аргумент.")
                    TIMESTAMP_BYTES
                }
                in CORE_UNKNOWN -> return Result.Invalid("Тег <$tag> не поддерживается ядром AmneziaWG: подключение не запустится.")
                else -> return Result.Invalid("Неизвестный тег <$tag>. Допустимы: <b>, <t>, <r>, <rc>, <rd>.")
            }
            if (bytes > MAX_PACKET_BYTES) {
                return Result.Invalid("Пакет больше $MAX_PACKET_BYTES байт: его раздробит IP, а это заметно.")
            }
            tags++
            cursor = end + 1
        }
        if (tags == 0) return Result.Invalid("Нет ни одного тега.")
        return Result.Valid(bytes)
    }
}
