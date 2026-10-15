package io.github.zapretkvn.networkbootstrap

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Один DNS-вопрос с одним подстраховочным повтором (hedge).
 *
 * Системный резолвер при потере единственного UDP-пакета ждёт собственный
 * тайм-аут (около 5 с). Если ответа нет за [hedgeAfterMillis], вопрос уходит
 * ещё раз, принимается первый ответ, второй запрос отменяется. Всего не больше
 * [MAX_ATTEMPTS] запросов на вопрос: веера запросов нет, чтобы трафик не
 * выделялся среди обычного.
 *
 * Быстрый отказ (ошибка раньше окна) возвращается сразу и повтор не запускает:
 * это уже определённый исход, а не потеря пакета. После запуска повтора ошибка
 * считается итоговой, когда провалились все отправленные запросы.
 */
object HedgedQuery {
    const val MAX_ATTEMPTS = 2

    /** Приёмник исхода одного запроса. Повторные вызовы и вызовы после победителя игнорируются. */
    interface Attempt<R> {
        fun succeed(value: R)
        fun fail(error: Throwable)
    }

    /**
     * [start] отправляет один запрос и возвращает функцию его отмены. Вызывается
     * один раз или два (повтор). При [hedgeAfterMillis] <= 0 повтор не запускается.
     */
    suspend fun <R> run(
        hedgeAfterMillis: Long,
        start: (Attempt<R>) -> (() -> Unit),
    ): R = coroutineScope {
        val result = CompletableDeferred<R>()
        val cancels = CopyOnWriteArrayList<() -> Unit>()
        val lock = Any()
        var sent = 0
        var failed = 0
        var closed = false

        fun cancelAll() {
            cancels.forEach { cancel -> runCatching(cancel) }
        }

        fun send(): Boolean {
            synchronized(lock) {
                if (closed || sent >= MAX_ATTEMPTS) return false
                sent++
            }
            val attempt = object : Attempt<R> {
                private var reported = false

                override fun succeed(value: R) {
                    synchronized(lock) {
                        if (reported || closed) return
                        reported = true
                        closed = true
                    }
                    result.complete(value)
                }

                override fun fail(error: Throwable) {
                    val final = synchronized(lock) {
                        if (reported || closed) return
                        reported = true
                        failed++
                        // Повтор ещё не ушёл: быстрый отказ уже итог. Иначе итог,
                        // когда провалились все отправленные запросы.
                        (failed >= sent).also { if (it) closed = true }
                    }
                    if (final) result.completeExceptionally(error)
                }
            }
            val cancel = start(attempt)
            cancels += cancel
            // Исход мог прийти до регистрации отмены: тогда отменяем сразу.
            if (result.isCompleted) runCatching(cancel)
            return true
        }

        val hedge = if (hedgeAfterMillis > 0) {
            launch {
                delay(hedgeAfterMillis)
                // Сбой отправки повтора не должен ронять уже идущий первый запрос.
                try {
                    send()
                } catch (ignored: Exception) {
                    synchronized(lock) { sent-- }
                }
            }
        } else {
            null
        }
        try {
            send()
            result.await()
        } finally {
            hedge?.cancel()
            synchronized(lock) { closed = true }
            cancelAll()
        }
    }
}
