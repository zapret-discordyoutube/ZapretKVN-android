package io.github.zapretkvn.core.uimodel

/**
 * Задержка словами. Пороги для замера через сервер (Relay HTTPS): именно его
 * чувствует пользователь.
 */
enum class LatencyBand(val word: String) {
    Fast("быстро"),
    Normal("нормально"),
    Slow("медленно"),
    ;

    companion object {
        const val FAST_BELOW_MILLIS = 150
        const val NORMAL_BELOW_MILLIS = 400

        fun of(millis: Int): LatencyBand = when {
            millis < FAST_BELOW_MILLIS -> Fast
            millis < NORMAL_BELOW_MILLIS -> Normal
            else -> Slow
        }
    }
}

/**
 * Лучший сервер помечается, только если разница заметна: минимум два замера и
 * лидер быстрее второго на 15 % или на 20 мс. Иначе метки нет — не создаём
 * ложной разницы между почти одинаковыми серверами.
 */
fun <T> clearlyFastest(candidates: List<Pair<T, Int>>): T? {
    if (candidates.size < 2) return null
    val sorted = candidates.sortedBy { it.second }
    val best = sorted[0].second
    val second = sorted[1].second
    val clearlyBetter = second - best >= 20 || best <= second * 0.85
    return sorted[0].first.takeIf { clearlyBetter }
}
