package io.github.zapretkvn.android.network.probes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Параллельная гонка проб: кандидаты стартуют со ступенчатой задержкой
 * (staggerMillis между стартами — «happy eyeballs», чтобы не создавать
 * залповую нагрузку на медленный туннель), первый успех отменяет остальных,
 * fatal-ошибка немедленно пробрасывается. Полный провал возвращает ошибки в
 * исходном порядке кандидатов, чтобы диагностическая строка оставалась
 * детерминированной.
 */
internal object HealthProbeRace {
    sealed interface Outcome<out T, out R> {
        data class Success<out T, out R>(val candidate: T, val value: R) : Outcome<T, R>
        data class AllFailed<out T>(val failures: List<Pair<T, Throwable>>) : Outcome<T, Nothing>
    }

    suspend fun <T, R> firstSuccess(
        candidates: List<T>,
        staggerMillis: Long = 0,
        isFatal: (Throwable) -> Boolean = { false },
        attempt: suspend (T) -> R,
    ): Outcome<T, R> {
        require(candidates.isNotEmpty()) { "Нет кандидатов для пробы." }
        return coroutineScope {
            val results = Channel<IndexedValue<Result<R>>>(candidates.size)
            val jobs = candidates.mapIndexed { index, candidate ->
                launch {
                    val outcome = try {
                        if (index > 0 && staggerMillis > 0) delay(index * staggerMillis)
                        Result.success(attempt(candidate))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                    results.send(IndexedValue(index, outcome))
                }
            }
            val failures = arrayOfNulls<Throwable>(candidates.size)
            repeat(candidates.size) {
                val (index, result) = results.receive()
                val error = result.exceptionOrNull()
                if (error == null) {
                    jobs.forEach(Job::cancel)
                    return@coroutineScope Outcome.Success(candidates[index], result.getOrThrow())
                }
                if (isFatal(error)) {
                    jobs.forEach(Job::cancel)
                    throw error
                }
                failures[index] = error
            }
            Outcome.AllFailed(
                candidates.mapIndexedNotNull { index, candidate ->
                    failures[index]?.let { candidate to it }
                },
            )
        }
    }
}
