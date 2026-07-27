package io.github.zapretkvn.android.vpn

/** Что делать с провалившейся попыткой подключения. */
internal sealed interface VpnRecoveryDecision {
    /**
     * Физической сети нет. Таймер бесполезен: ждём событие `ConnectivityManager`
     * о появлении пригодной сети.
     */
    data object AwaitNetwork : VpnRecoveryDecision

    /** Сеть жива, отказ транзиентный: повтор через [delayMillis]. */
    data class RetryAfter(val delayMillis: Long) : VpnRecoveryDecision

    /** Нужны действия пользователя или профиль/политика Android неисправимы. */
    data object Terminal : VpnRecoveryDecision
}

/**
 * Решает, переживает ли сервис отказ подключения.
 *
 * До этой политики любой отказ был терминальным: сервис публиковал ошибку и
 * умирал вместе с наблюдателем сети, поэтому смена cellular → Wi-Fi оставляла
 * пользователя с `NET-101` и требовала ручного «Подключить».
 *
 * Автоматический повтор ограничен [MAX_ATTEMPTS] и снимает счётчик только при
 * успешном подключении или при появлении другой физической сети: бесконечный
 * цикл перезапусков дороже честной ошибки.
 */
internal object VpnRecoveryPolicy {
    /** Подряд идущие отказы на одной и той же физической сети. */
    const val MAX_ATTEMPTS = 3

    /**
     * Потолок на всю серию восстановления. Счётчик [MAX_ATTEMPTS] обнуляется
     * при появлении другой сети, поэтому ping-pong Wi-Fi ↔ cellular без этого
     * потолка крутил бы перезапуски бесконечно.
     */
    const val MAX_TOTAL_ATTEMPTS = 8
    const val BASE_DELAY_MILLIS = 1_000L
    const val MAX_DELAY_MILLIS = 15_000L

    /**
     * Исчезает само, как только Android отдаст рабочую физическую сеть.
     * Captive portal (`NET-110`) сюда не входит намеренно: без авторизации в
     * браузере сеть не станет пригодной, а держать foreground-сервис в ожидании
     * действия пользователя дороже честной ошибки.
     */
    private val AWAIT_NETWORK_CODES = setOf(
        "NET-101", // Android не предоставил доступную физическую сеть
    )

    /** Транзиентный отказ на уже живой сети. */
    private val RETRY_CODES = setOf(
        "NET-102", // сеть менялась во время bootstrap
        "DNS-101", // системный DNS не ответил вовремя
        "DNS-105", // системная ошибка Android resolver
    )

    /**
     * [networkChangedDuringAttempt] — страховка для отказов без стабильного кода:
     * если физическая сеть сменилась под работающей попыткой, её результат ничего
     * не говорит о профиле и не должен становиться финальной ошибкой.
     */
    fun decide(
        failureCode: String,
        attempt: Int,
        totalAttempts: Int = attempt,
        networkChangedDuringAttempt: Boolean = false,
    ): VpnRecoveryDecision = when {
        attempt >= MAX_ATTEMPTS || totalAttempts >= MAX_TOTAL_ATTEMPTS ->
            VpnRecoveryDecision.Terminal
        failureCode in AWAIT_NETWORK_CODES -> VpnRecoveryDecision.AwaitNetwork
        failureCode in RETRY_CODES || networkChangedDuringAttempt ->
            VpnRecoveryDecision.RetryAfter(backoffMillis(attempt))
        else -> VpnRecoveryDecision.Terminal
    }

    fun backoffMillis(attempt: Int): Long {
        val safeAttempt = attempt.coerceIn(0, 16)
        val scaled = BASE_DELAY_MILLIS shl safeAttempt
        return scaled.coerceAtMost(MAX_DELAY_MILLIS)
    }
}
