package io.github.zapretkvn.android.network.probes

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * DNS-проба VPN-сети при strict Private DNS.
 *
 * Одна попытка с коротким тайм-аутом здесь ничего не доказывает: Android проверяет
 * резолвер пользователя заново на каждой новой VPN-сети, и пока проверка идёт,
 * первые запросы опаздывают. Попытки повторяются до исчерпания бюджета; пауза
 * между ними случайная, а не ровный ритм, чтобы повторы не складывались в
 * узнаваемый рисунок. Последняя ошибка уходит наружу без обёртки.
 */
internal object StrictPrivateDnsProbe {
    const val MIN_PAUSE_MILLIS = 500L
    const val MAX_PAUSE_MILLIS = 1_500L

    suspend fun <T> resolve(
        budgetMillis: Long,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        random: Random = Random.Default,
        pause: suspend (Long) -> Unit = { delay(it) },
        onAttempt: (Int) -> Unit = {},
        /** Повторять ли после этой ошибки; false — она уходит наружу сразу. */
        retryWhen: (Throwable) -> Boolean = { true },
        attempt: suspend () -> T,
    ): T {
        val deadline = clock() + budgetMillis
        var number = 0
        while (true) {
            number += 1
            onAttempt(number)
            try {
                return attempt()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!retryWhen(error)) throw error
                val wait = random.nextLong(MIN_PAUSE_MILLIS, MAX_PAUSE_MILLIS + 1)
                if (clock() + wait >= deadline) throw error
                pause(wait)
            }
        }
    }
}
