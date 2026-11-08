package io.github.zapretkvn.runtimelog

/** Решает, что в журнал не пишется вовсе (владелец 2026-11-08: шум пусть отбрасывается, полезное сохраняется). */
fun interface LogNoiseFilter {
    fun isNoise(layer: LogLayer, level: Int, text: String): Boolean

    companion object {
        /** Пишется всё. */
        val None = LogNoiseFilter { _, _, _ -> false }

        /**
         * Только `TRACE connection: … upload/download closed`: по две строки на каждое закрытое
         * соединение, а сведений в них нет — длительность и так видна по строкам `outbound` и
         * по отсчётам трафика. Всё остальное, включая DEBUG и TRACE других видов, остаётся.
         */
        val Default = LogNoiseFilter { layer, level, text ->
            layer == LogLayer.Core && level >= TRACE_LEVEL && text.contains("connection: ") &&
                (text.endsWith(" upload closed") || text.endsWith(" download closed"))
        }

        private const val TRACE_LEVEL = 6
    }
}
