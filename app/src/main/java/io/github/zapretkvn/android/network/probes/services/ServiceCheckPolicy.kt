package io.github.zapretkvn.android.network.probes.services

/**
 * Когда проверку можно начать. Только два повода: нажатие на карточку (не
 * чаще раза в [TAP_COOLDOWN_MILLIS]) и один автозапуск за сессию VPN. Никаких
 * таймеров повтора. Часы монотонные и внедряются, чтобы проверять без ожидания.
 */
internal class ServiceCheckPolicy(private val elapsed: () -> Long) {
    private var lastFinishedAt: Long? = null
    private var autoScheduled = false

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

    /** true ровно один раз за сессию. */
    @Synchronized
    fun claimAutoRun(): Boolean {
        if (autoScheduled) return false
        autoScheduled = true
        return true
    }

    companion object {
        const val TAP_COOLDOWN_MILLIS = 15_000L
        const val AUTO_DELAY_MIN_MILLIS = 4_000L
        const val AUTO_DELAY_MAX_MILLIS = 12_000L
        const val LANE_JITTER_MIN_MILLIS = 1_000L
        const val LANE_JITTER_MAX_MILLIS = 3_000L
    }
}
