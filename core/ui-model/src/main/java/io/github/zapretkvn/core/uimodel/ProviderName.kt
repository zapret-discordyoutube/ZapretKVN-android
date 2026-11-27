package io.github.zapretkvn.core.uimodel

import java.util.Locale
import java.util.regex.Pattern

/**
 * Имя сервера так, как его назвал провайдер: какой это сервер, какой его вариант, каким маршрутом.
 *
 * Решение владельца 2026-11-27: «сервер» в сгруппированном списке — собственное именование провайдера, а не догадка по
 * IP. Это точный порт `windows/xray_fluent/profiles/server_naming.py`: оба приложения группируют одинаково, а тест
 * сверяет разбор с результатом Windows-версии на 1953 настоящих именах владельца (`provider_names.tsv`).
 *
 * Формы, встречающиеся в настоящих подписках:
 * - `🇸🇪 4stok · VLESS gRPC · TLS · V2`: сервер, вариант, необязательный маркер маршрута;
 * - `🇩🇪 Hiphopgen через Россию`: тот же сервер другим маршрутом;
 * - `🇳🇱 Нидерланды 3`: название страны со счётчиком (сервер «Нидерланды», вариант «3»);
 * - `plc31-tcp`: технический id с суффиксом транспорта;
 * - `awg-82.26.193.106:44313`: конфигурация WireGuard, названная адресом;
 * - `proxy`: читать нечего (сервер — всё имя).
 *
 * Протокол из имени НЕ читается: у сервера есть собственный точный тип, а имя — свободный текст.
 */
data class ProviderName(
    val flag: String,
    val server: String,
    val variant: String,
    val via: String,
) {
    /** Идентичность сервера для группировки: регистр не делает два сервера. */
    val serverKey: String get() = server.lowercase(Locale.ROOT)

    companion object {
        // Не `UNICODE_CHARACTER_CLASS`: движок регулярных выражений Android (ICU) этот флаг не принимает и бросает
        // IllegalArgumentException при первом разборе имени, а JVM-тесты его принимают. Поэтому пробельные символы и
        // регистр «через» заданы явными классами: они одинаково работают на JVM и на устройстве.
        private const val WS = "\\s\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{0085}"
        private const val SPACE = "[$WS]"
        private const val NOT_SPACE = "[^$WS]"

        private val FLAG = Pattern.compile(
            "^(?:[\\x{1F1E6}-\\x{1F1FF}]{2}|[\\x{1F3F3}\\x{1F3F4}]\\x{FE0F}?(?:\\x{200D}$NOT_SPACE{1,3})?)",
        )
        private val LEADING_NOISE = Pattern.compile("^[$WS\\x{200D}\\x{FE0F}\\x{2753}\\x{2757}\\x{2022}\\x{00B7}|:-]+")
        private val VIA = Pattern.compile("$SPACE+[Чч][Ее][Рр][Ее][Зз]$SPACE+$NOT_SPACE+(?:$SPACE+$NOT_SPACE+)?$")
        private val SEPARATOR = Pattern.compile(
            "$SPACE+[\\x{00B7}\\x{2022}]$SPACE+|$SPACE+[\\x{2014}\\x{2013}]$SPACE+|$SPACE+-$SPACE+",
        )
        private val COUNTER = Pattern.compile("^(?<server>.*?$NOT_SPACE)$SPACE+(?<variant>\\(?[0-9]{1,3}\\)?)$")
        private val TECHNICAL = Pattern.compile(
            "^(?<server>[A-Za-z0-9_.]+?)-(?<variant>tcp|udp|xhttp|grpc|ws|wss|reality|h2|hy2|quic)$",
            Pattern.CASE_INSENSITIVE,
        )
        private val ADDRESS = Pattern.compile(
            "^(?:awg|wg)-(?<server>[0-9A-Za-z.:\\-]+?)(?::(?<port>[0-9]{2,5}))?$",
            Pattern.CASE_INSENSITIVE,
        )

        fun parse(name: String): ProviderName {
            var text = name.trim()
            var flag = ""
            val flagMatch = FLAG.matcher(text)
            if (flagMatch.find()) {
                flag = flagMatch.group()
                text = text.substring(flagMatch.end())
            }
            text = LEADING_NOISE.matcher(text).replaceFirst("").trim()
            var via = ""
            val viaMatch = VIA.matcher(text)
            if (viaMatch.find()) {
                via = viaMatch.group().trim()
                text = text.substring(0, viaMatch.start()).trimEnd()
            }
            if (text.isEmpty()) return ProviderName(flag, name.trim(), "", via)

            val address = ADDRESS.matcher(text)
            if (address.matches()) {
                return ProviderName(flag, address.group("server")!!, address.group("port") ?: "", via)
            }

            val parts = split(SEPARATOR, text)
            if (parts.size > 1) {
                val server = parts[0].trim()
                val variant = parts.drop(1).map(String::trim).filter(String::isNotEmpty).joinToString(" · ")
                if (server.isNotEmpty()) return ProviderName(flag, server, variant, via)
            }

            val technical = TECHNICAL.matcher(text)
            if (technical.matches()) {
                return ProviderName(
                    flag, technical.group("server")!!, technical.group("variant")!!.lowercase(Locale.ROOT), via,
                )
            }
            val counter = COUNTER.matcher(text)
            if (counter.matches()) {
                return ProviderName(flag, counter.group("server")!!, counter.group("variant")!!.trim('(', ')'), via)
            }
            return ProviderName(flag, text, "", via)
        }

        /** Как `re.split` в Python: пустые куски сохраняются, лимита нет. */
        private fun split(pattern: Pattern, text: String): List<String> {
            val parts = mutableListOf<String>()
            val matcher = pattern.matcher(text)
            var last = 0
            while (matcher.find()) {
                parts += text.substring(last, matcher.start())
                last = matcher.end()
            }
            parts += text.substring(last)
            return parts
        }
    }
}
