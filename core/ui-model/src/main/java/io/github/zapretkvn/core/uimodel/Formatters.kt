package io.github.zapretkvn.core.uimodel

import java.util.Locale

/** Пинг старше суток показывается приглушённо: сервер мог сильно измениться. */
const val LATENCY_OLD_MILLIS = 24 * 60 * 60 * 1_000L

fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = safe.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$safe ${units[unit]}" else "%.1f %s".format(Locale.US, value, units[unit])
}

/** Скорость за секунду: «0 Б/с», «1.5 МБ/с». */
fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/с"

fun formatDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    val minutes = seconds / 60 % 60
    val rest = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, rest)
    } else {
        "%02d:%02d".format(Locale.US, minutes, rest)
    }
}

fun formatPing(value: Long?): String {
    val millis = value?.coerceAtLeast(0) ?: return "—"
    if (millis < 1_000) return "$millis мс"

    val hundredths = millis / 10 + if (millis % 10 >= 5) 1 else 0
    val seconds = hundredths / 100
    val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$seconds с" else "$seconds,$fraction с"
}

/** Возраст замера или события: «только что», «12 мин назад», «3 ч назад», «2 д назад». */
fun formatAge(ageMillis: Long): String {
    val age = ageMillis.coerceAtLeast(0)
    return when {
        age < 60_000L -> "только что"
        age < 3_600_000L -> "${age / 60_000L} мин назад"
        age < LATENCY_OLD_MILLIS -> "${age / 3_600_000L} ч назад"
        else -> "${age / LATENCY_OLD_MILLIS} д назад"
    }
}

/**
 * Русская форма слова по числу: `plural(5, "сервер", "сервера", "серверов")` → «серверов».
 * Возвращает только слово, число подставляет вызывающий.
 */
fun plural(count: Int, one: String, few: String, many: String): String {
    val n = kotlin.math.abs(count) % 100
    val last = n % 10
    return when {
        n in 11..14 -> many
        last == 1 -> one
        last in 2..4 -> few
        else -> many
    }
}

/** «48 серверов», «1 сервер», «3 сервера». */
fun countLabel(count: Int, one: String, few: String, many: String): String =
    "$count ${plural(count, one, few, many)}"
