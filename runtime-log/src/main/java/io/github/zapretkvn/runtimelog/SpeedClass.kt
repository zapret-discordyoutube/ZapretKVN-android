package io.github.zapretkvn.runtimelog

/**
 * Оценка скорости потока: что много, а что мало. Пороги привязаны к тому, что нужно видео YouTube
 * (примерные битрейты: 360p около 0,7 Мбит/с = 90 КБ/с, 720p 2,5-4 Мбит/с = 300-500 КБ/с,
 * 1080p 5-8 Мбит/с = 600-1000 КБ/с, 4K от 20 Мбит/с). Для плавного старта нужна скорость выше битрейта,
 * иначе кружок крутится, пока набирается буфер.
 *
 * - [Fast] от 600 КБ/с: 1080p и выше идёт с запасом.
 * - [Normal] 200-600 КБ/с: 720p идёт, 1080p на грани.
 * - [Slow] 50-200 КБ/с: хватает на 360p-480p, старт и перемотка долгие.
 * - [VerySlow] меньше 50 КБ/с: не хватает даже на 360p, видео не набирает буфер.
 *
 * Оценивается только поток, который успел передать достаточно данных, иначе скорость определяет разгон
 * соединения, а не канал: см. [MIN_BYTES_TO_GRADE].
 */
enum class SpeedClass(val label: String, val minBytesPerSecond: Long) {
    Fast("быстро", 600L * 1024),
    Normal("норма", 200L * 1024),
    Slow("медленно", 50L * 1024),
    VerySlow("очень медленно", 0),
    ;

    companion object {
        /** Меньше этого поток не оценивается: рукопожатие и разгон не показывают скорость канала. */
        const val MIN_BYTES_TO_GRADE = 64L * 1024

        fun of(bytesPerSecond: Long): SpeedClass = entries.first { bytesPerSecond >= it.minBytesPerSecond }

        /** Оценка потока по байтам и времени; `null`, если данных мало или время не измерено. */
        fun ofFlow(downlinkBytes: Long, millis: Long): SpeedClass? =
            if (downlinkBytes < MIN_BYTES_TO_GRADE || millis < 1_000) null else of(downlinkBytes * 1_000 / millis)
    }
}

/**
 * Оценка канала туннеля по строке статистики QUIC ядра (патч 0012): задержка и потери.
 * Хороший: потери меньше 1% и сглаженная задержка меньше 120 мс. Средний: потери 1-3% или задержка 120-250 мс.
 * Плохой: потери от 3% или задержка от 250 мс. Потери берутся за последнее окно, а при его отсутствии за всё время.
 */
object TunnelGrade {
    private val SMOOTHED = Regex("""smoothed=(\d+)ms""")
    private val WINDOW_LOSS = Regex("""lost \d+ \(([\d.]+)% of sent\)""")
    private val TOTAL = Regex("""total sent (\d+) pkts .*?lost (\d+) pkts""")

    /** Слово оценки или `null`, если строку не удалось разобрать. */
    fun of(line: String): String? {
        val rtt = SMOOTHED.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val loss = WINDOW_LOSS.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: TOTAL.find(line)?.let { match ->
                val sent = match.groupValues[1].toLongOrNull() ?: 0
                val lost = match.groupValues[2].toLongOrNull() ?: 0
                if (sent > 0) lost * 100.0 / sent else 0.0
            }
            ?: return null
        return when {
            loss >= 3.0 || rtt >= 250 -> "плохой"
            loss >= 1.0 || rtt >= 120 -> "средний"
            else -> "хороший"
        }
    }
}
