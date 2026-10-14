package io.github.zapretkvn.android.network.probes

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/**
 * Одна общая «полоса» для тяжёлых проб: проверка задержек серверов и проверка
 * доступности сервисов не идут одновременно. Массовая проверка серверов
 * вытесняет проверку сервисов (она отменяется и возвращает прежние результаты),
 * а сама ждёт, только если полосу держит что-то, что вытеснить нельзя.
 */
internal class ProbeLane {
    enum class Owner { Latency, Services }

    /** Владение полосой; [release] идемпотентен. */
    class Lease internal constructor(val owner: Owner, private val onRelease: () -> Unit) {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) onRelease()
        }
    }

    private val mutex = Mutex()
    private val lock = Any()
    private var holder: Owner? = null
    private var preempt: (() -> Unit)? = null

    val heldBy: Owner? get() = synchronized(lock) { holder }

    /** Занять без ожидания; null — полоса занята. */
    fun tryAcquire(owner: Owner, onPreempt: (() -> Unit)? = null): Lease? {
        if (!mutex.tryLock()) return null
        return granted(owner, onPreempt)
    }

    /**
     * Занять с ожиданием. Если полосу держит вытесняемый владелец, он
     * получает сигнал на отмену, и вызов ждёт, пока тот освободит полосу.
     */
    suspend fun acquire(owner: Owner, onPreempt: (() -> Unit)? = null): Lease {
        if (!mutex.tryLock()) {
            if (owner == Owner.Latency) {
                val signal = synchronized(lock) { if (holder == Owner.Services) preempt else null }
                signal?.invoke()
            }
            mutex.lock()
        }
        return granted(owner, onPreempt)
    }

    private fun granted(owner: Owner, onPreempt: (() -> Unit)?): Lease {
        synchronized(lock) {
            holder = owner
            preempt = onPreempt
        }
        return Lease(owner) {
            synchronized(lock) {
                holder = null
                preempt = null
            }
            mutex.unlock()
        }
    }
}
