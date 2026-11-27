package io.github.zapretkvn.android.config

import android.os.SystemClock

/**
 * Пауза входа через WARP на этой сети (владелец 2026-11-27, «а если VPN в итоге не подключится?»).
 *
 * Диагностика 0.6.65 на мобильных данных: аккаунты были, но туннель WARP не пропускал трафик (WireGuard молчит после
 * рукопожатия, все пять кандидатов MASQUE не ответили), и подключение отказывало, хотя без входа сервер доступен. Вход
 * через WARP нужен, чтобы скрыть адрес сервера, а не ценой того, что VPN не работает. Когда запуск со входом не прошёл
 * проверку, [SessionStarter] подключается без входа и ставит паузу: следующие подключения на этой же сети не тратят на
 * заведомо мёртвый туннель ещё минуту. Смена сети, новая регистрация аккаунтов и истечение срока снимают паузу.
 */
object WarpHopSuspension {
    /** Сколько вход не пробуется на той же сети после отказа. */
    const val PAUSE_MILLIS = 600_000L

    private var untilMillis = 0L
    private var networkId: String? = null
    private var clock: () -> Long = SystemClock::elapsedRealtime

    @Synchronized
    fun suspend(network: String?, forMillis: Long = PAUSE_MILLIS) {
        untilMillis = clock() + forMillis
        networkId = network
    }

    /** Пауза идёт, если срок не вышел и сеть та же (неизвестная сеть считается той же). */
    @Synchronized
    fun active(network: String?): Boolean {
        if (untilMillis == 0L) return false
        if (clock() >= untilMillis) return clear().let { false }
        if (networkId != null && network != null && networkId != network) return clear().let { false }
        return true
    }

    @Synchronized
    fun remainingMillis(): Long = if (untilMillis == 0L) 0L else (untilMillis - clock()).coerceAtLeast(0L)

    @Synchronized
    fun clear() {
        untilMillis = 0L
        networkId = null
    }

    /** Только для тестов: свои часы. */
    @Synchronized
    internal fun useClock(source: () -> Long) {
        clock = source
        clear()
    }
}
