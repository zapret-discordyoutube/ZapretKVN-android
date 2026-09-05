package io.github.zapretkvn.android.network.probes

internal object ExternalIpParser {
    private val ipv4 = Regex("(?:0|[1-9]\\d{0,2})(?:\\.(?:0|[1-9]\\d{0,2})){3}")
    private val ipv6 = Regex("[0-9a-fA-F:]{2,45}")

    fun parse(value: String): String? {
        val candidate = value.trim()
        if (candidate.length !in 2..45) return null
        if (ipv4.matches(candidate)) {
            return candidate.takeIf {
                candidate.split('.').all { octet -> octet.toIntOrNull() in 0..255 }
            }
        }
        if (!ipv6.matches(candidate) || ':' !in candidate || ":::" in candidate) return null
        return candidate.takeIf { ipv6StructureLooksValid(it) }
    }

    private fun ipv6StructureLooksValid(value: String): Boolean {
        if (value.count { it == ':' } < 2) return false
        if (value.countSubstring("::") > 1) return false
        val groups = value.split(':').filter(String::isNotEmpty)
        if (groups.any { it.length > 4 }) return false
        return if ("::" in value) groups.size < 8 else groups.size == 8
    }

    private fun String.countSubstring(part: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val next = indexOf(part, offset)
            if (next < 0) return count
            count++
            offset = next + part.length
        }
    }
}
