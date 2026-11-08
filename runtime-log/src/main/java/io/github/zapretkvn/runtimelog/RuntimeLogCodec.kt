package io.github.zapretkvn.runtimelog

/**
 * Компактная строка журнала: `<мс от начала сегмента в base36> <слой><уровень> <текст>\n`.
 *
 * Пример: `1k2z DD ... dns: exchanged ... 78ms`. Время кодируется смещением от начала
 * сегмента (в заголовке лежит абсолютное), поэтому метка занимает 3–6 знаков вместо 13.
 * Из текста экранируются только `\`, перевод строки и возврат каретки: остальное остаётся
 * как есть, включая домены и адреса (решение владельца 2026-11-08).
 */
object RuntimeLogCodec {
    const val MAGIC = "#zkvn-log"
    const val VERSION = 1

    fun header(startEpochMillis: Long, sequence: Long): String =
        "$MAGIC $VERSION $startEpochMillis $sequence\n"

    /** Начало сегмента из заголовка или `null`, если строка не заголовок этой версии. */
    fun parseHeader(line: String): Long? {
        val parts = line.trimEnd('\n').split(' ')
        if (parts.size < 4 || parts[0] != MAGIC || parts[1] != VERSION.toString()) return null
        return parts[2].toLongOrNull()
    }

    fun encode(baseEpochMillis: Long, record: LogRecord, out: StringBuilder) {
        out.append((record.epochMillis - baseEpochMillis).coerceAtLeast(0L).toString(36))
        out.append(' ')
        out.append(record.layer.code)
        out.append(('0' + record.level.coerceIn(0, 9)))
        out.append(' ')
        for (char in record.text) {
            when (char) {
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(char)
            }
        }
        out.append('\n')
    }

    fun decode(baseEpochMillis: Long, line: String): LogRecord? {
        val firstSpace = line.indexOf(' ')
        if (firstSpace <= 0) return null
        val secondSpace = line.indexOf(' ', firstSpace + 1)
        if (secondSpace != firstSpace + 3) return null
        val offset = line.substring(0, firstSpace).toLongOrNull(36) ?: return null
        val layer = LogLayer.of(line[firstSpace + 1]) ?: return null
        val level = line[firstSpace + 2] - '0'
        if (level !in 0..9) return null
        return LogRecord(baseEpochMillis + offset, layer, level, unescape(line.substring(secondSpace + 1)))
    }

    private fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                when (text[index + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> out.append(text[index + 1])
                }
                index += 2
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }
}
