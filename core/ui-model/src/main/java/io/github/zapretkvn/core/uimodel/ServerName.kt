package io.github.zapretkvn.core.uimodel

import java.util.Locale

/**
 * Имя сервера, разобранное для показа: флаг и протокол выносятся в отдельные
 * элементы строки, чтобы само название не дублировало их и не обрезалось.
 *
 * «VLESS 🇳🇱 Amsterdam-2» → flag=🇳🇱, countryCode=NL, protocol=VLESS, title=Amsterdam-2.
 */
data class ServerName(
    val original: String,
    val title: String,
    val flag: String?,
    val countryCode: String?,
    val protocolPrefix: String?,
) {
    /** Название страны по-русски для TalkBack и фильтров; без сети и ассетов. */
    val countryName: String?
        get() = countryCode?.let { Locale("", it).getDisplayCountry(RU).takeIf(String::isNotBlank) }

    companion object {
        private val RU = Locale("ru")
        private val PROTOCOLS = listOf(
            "VLESS", "VMess", "VMESS", "Trojan", "TROJAN", "Shadowsocks", "SS",
            "Hysteria2", "Hysteria", "HY2", "TUIC", "WireGuard", "WG", "AnyTLS", "SOCKS", "HTTP",
        )
        private val EDGE_SEPARATORS = charArrayOf(' ', '|', '-', '_', '·', ',', ':', '/', ' ')

        fun parse(raw: String): ServerName {
            var rest = raw.trim()
            var protocol: String? = null
            var flag: String? = null
            var code: String? = null

            findFlag(rest)?.let { (start, value) ->
                flag = value
                code = flagToCode(value)
                rest = (rest.substring(0, start) + " " + rest.substring(start + value.length)).trim()
            }
            for (candidate in PROTOCOLS) {
                if (rest.startsWith(candidate, ignoreCase = false) &&
                    rest.length > candidate.length &&
                    rest[candidate.length] in EDGE_SEPARATORS
                ) {
                    protocol = candidate
                    rest = rest.substring(candidate.length)
                    break
                }
            }
            val title = rest.trim(*EDGE_SEPARATORS).replace(Regex("\\s{2,}"), " ")
            return ServerName(
                original = raw,
                title = title.ifEmpty { raw.trim() },
                flag = flag,
                countryCode = code,
                protocolPrefix = protocol,
            )
        }

        /** Первая пара regional indicator символов (U+1F1E6..U+1F1FF). */
        private fun findFlag(text: String): Pair<Int, String>? {
            var index = 0
            while (index < text.length) {
                val first = text.codePointAt(index)
                val firstSize = Character.charCount(first)
                if (first.isRegionalIndicator() && index + firstSize < text.length) {
                    val second = text.codePointAt(index + firstSize)
                    if (second.isRegionalIndicator()) {
                        val end = index + firstSize + Character.charCount(second)
                        return index to text.substring(index, end)
                    }
                }
                index += firstSize
            }
            return null
        }

        private fun Int.isRegionalIndicator(): Boolean = this in 0x1F1E6..0x1F1FF

        private fun flagToCode(flag: String): String? {
            val points = flag.codePoints().toArray()
            if (points.size != 2) return null
            return points.joinToString("") { ('A' + (it - 0x1F1E6)).toString() }
        }
    }
}

/** Монограмма протокола для круга, если у сервера нет флага: VL, SS, HY, WG… */
fun protocolMonogram(type: String): String = when (type.lowercase(Locale.ROOT)) {
    "vless" -> "VL"
    "vmess" -> "VM"
    "trojan" -> "TR"
    "shadowsocks", "ss" -> "SS"
    "hysteria", "hysteria2" -> "HY"
    "tuic" -> "TU"
    "wireguard" -> "WG"
    "anytls" -> "AT"
    "socks" -> "SO"
    "http" -> "HT"
    "direct" -> "→"
    else -> type.take(2).uppercase(Locale.ROOT).ifEmpty { "?" }
}

/** Человеческое имя протокола для бейджа: «VLESS», «Hysteria2», «Shadowsocks». */
fun protocolLabel(type: String): String = when (type.lowercase(Locale.ROOT)) {
    "vless" -> "VLESS"
    "vmess" -> "VMess"
    "trojan" -> "Trojan"
    "shadowsocks" -> "Shadowsocks"
    "hysteria" -> "Hysteria"
    "hysteria2" -> "Hysteria2"
    "tuic" -> "TUIC"
    "wireguard" -> "WireGuard"
    "anytls" -> "AnyTLS"
    "socks" -> "SOCKS"
    "http" -> "HTTP"
    "direct" -> "Напрямую"
    "selector" -> "Группа"
    "urltest" -> "Авто"
    else -> type
}
