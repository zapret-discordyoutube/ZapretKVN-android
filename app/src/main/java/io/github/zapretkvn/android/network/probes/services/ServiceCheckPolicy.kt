package io.github.zapretkvn.android.network.probes.services

/**
 * Когда проверку можно начать: только нажатие на карточку (не чаще раза в
 * [TAP_COOLDOWN_MILLIS]). Автозапуска после подключения нет — всплеск запросов
 * при каждом подключении был бы заметным ритмом. Никаких таймеров повтора. Часы монотонные и внедряются, чтобы проверять без ожидания.
 */
internal class ServiceCheckPolicy(private val elapsed: () -> Long) {
    private var lastFinishedAt: Long? = null

    /** Нажатие допустимо, если с конца прошлого запуска прошло достаточно времени. */
    @Synchronized
    fun tapAllowed(): Boolean {
        val last = lastFinishedAt ?: return true
        return elapsed() - last >= TAP_COOLDOWN_MILLIS
    }

    @Synchronized
    fun runFinished() {
        lastFinishedAt = elapsed()
    }

    companion object {
        const val TAP_COOLDOWN_MILLIS = 15_000L
    }
}
