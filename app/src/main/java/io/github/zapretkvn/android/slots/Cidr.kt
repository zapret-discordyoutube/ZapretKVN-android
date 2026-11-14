package io.github.zapretkvn.android.slots

import java.net.InetAddress

/** An IPv4 or IPv6 range written as `address/prefix`; literals only, so parsing never touches DNS. */
internal class Cidr private constructor(private val network: ByteArray, private val prefixBits: Int) {
    fun contains(address: ByteArray): Boolean {
        if (address.size != network.size) return false
        val fullBytes = prefixBits / 8
        for (i in 0 until fullBytes) if (address[i] != network[i]) return false
        val rest = prefixBits % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (address[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    companion object {
        private val LITERAL = Regex("""^[0-9a-fA-F:.]+$""")

        fun literal(text: String): ByteArray? {
            val trimmed = text.trim().removePrefix("[").removeSuffix("]")
            if (trimmed.isEmpty() || !LITERAL.matches(trimmed)) return null
            if (!trimmed.contains(':') && !trimmed.contains('.')) return null
            return runCatching { InetAddress.getByName(trimmed).address }.getOrNull()
        }

        fun parse(text: String): Cidr? {
            val parts = text.split('/')
            if (parts.size != 2) return null
            val network = literal(parts[0]) ?: return null
            val bits = parts[1].toIntOrNull() ?: return null
            if (bits < 0 || bits > network.size * 8) return null
            return Cidr(network, bits)
        }
    }
}
