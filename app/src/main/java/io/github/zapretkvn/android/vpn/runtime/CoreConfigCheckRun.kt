package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.config.CoreConfigCheck
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * Результат проверки конфигурации ядром, выполненной рядом с другими шагами
 * запуска. Отказ не бросается из фоновой задачи: она не должна отменять
 * родителя, а владелец сессии сам решает, когда и как его поднять.
 */
internal class CoreConfigCheckRun(
    val startedAtElapsedMillis: Long,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
    /** Конфиг уже проходил проверку в этом процессе: ядро не вызывалось. */
    val cached: Boolean,
    /** Исключение ядра как есть; `null` — конфигурация принята. */
    val failure: Throwable?,
) {
    companion object {
        fun launch(
            check: CoreConfigCheck,
            runtimeJson: String,
            elapsedRealtime: () -> Long,
            epochMillis: () -> Long,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): Deferred<CoreConfigCheckRun> = CoroutineScope(dispatcher).async {
            val startedElapsed = elapsedRealtime()
            val startedEpoch = epochMillis()
            var cached = false
            val failure = try {
                cached = check.check(runtimeJson)
                null
            } catch (error: Throwable) {
                error
            }
            CoreConfigCheckRun(
                startedAtElapsedMillis = startedElapsed,
                startedAtEpochMillis = startedEpoch,
                durationMillis = (elapsedRealtime() - startedElapsed).coerceAtLeast(0L),
                cached = cached,
                failure = failure,
            )
        }
    }
}
