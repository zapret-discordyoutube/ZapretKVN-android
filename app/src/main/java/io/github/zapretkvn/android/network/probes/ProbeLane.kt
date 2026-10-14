package io.github.zapretkvn.android.network.probes

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Одна общая «полоса» для тяжёлых проб: проверка подключения, проверка
 * задержек серверов, замер скорости «умной проверки» и проверка доступности
 * сервисов не идут одновременно.
 *
 * Приоритет строгий: подключение (health-check) вытесняет всех, задержки и замер
 * скорости вытесняют проверку сервисов, а сервисы не вытесняют никого. Вытесняемый
 * владелец получает сигнал `onPreempt`, должен быстро закончить и освободить полосу. Владелец без обратного вызова не вытесняется, и
 * его приходится ждать; подключение поэтому ждёт ограниченное время и при отказе
 * идёт дальше без полосы: полоса никогда не может заблокировать подключение.
 */
internal class ProbeLane {
    enum class Owner { Connect, Latency, Throughput, Services }

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
    private val waiting = ArrayList<Owner>()

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
            // Сначала встаём в очередь, затем сигналим: так вытеснение не теряется ни при
            // захвате полосы прямо сейчас, ни при смене владельца другим ожидающим.
            synchronized(lock) { waiting += owner }
            try {
                signalHolder(owner)
                mutex.lock()
            } finally {
                synchronized(lock) { waiting.remove(owner) }
            }
        }
        return granted(owner, onPreempt)
    }

    /** То же, что [acquire], но не дольше [timeoutMillis]; null — полоса не освободилась. */
    suspend fun acquireOrNull(owner: Owner, timeoutMillis: Long, onPreempt: (() -> Unit)? = null): Lease? {
        var lease: Lease? = null
        // Результат берётся из переменной: таймаут может сработать сразу после захвата.
        withTimeoutOrNull(timeoutMillis) { lease = acquire(owner, onPreempt) }
        return lease
    }

    private fun signalHolder(requester: Owner) {
        val signal = synchronized(lock) { holder?.takeIf { mayPreempt(requester, it) }?.let { preempt } }
        signal?.invoke()
    }

    private fun granted(owner: Owner, onPreempt: (() -> Unit)?): Lease {
        val yieldAtOnce = synchronized(lock) {
            holder = owner
            preempt = onPreempt
            // Пока мы стояли в очереди, мог прийти тот, кто важнее нового владельца.
            waiting.any { mayPreempt(it, owner) }
        }
        if (yieldAtOnce) onPreempt?.invoke()
        return Lease(owner) {
            synchronized(lock) {
                holder = null
                preempt = null
            }
            mutex.unlock()
        }
    }

    companion object {
        /** Может ли [requester] потребовать у [holder] освободить полосу. */
        fun mayPreempt(requester: Owner, holder: Owner): Boolean = when (requester) {
            Owner.Connect -> holder != Owner.Connect
            Owner.Latency, Owner.Throughput -> holder == Owner.Services
            Owner.Services -> false
        }

        /** Сколько подключение ждёт полосу, прежде чем пойти без неё. */
        const val CONNECT_WAIT_MILLIS = 3_000L
    }
}

/** Итог [runPreemptible]. */
internal sealed interface LaneRun<out T> {
    data class Done<T>(val value: T) : LaneRun<T>

    /** Полосу потребовал более важный владелец: работа отменена, результата нет. */
    data object Preempted : LaneRun<Nothing>

    /** Полоса не освободилась за отведённое время: работа не начиналась. */
    data object Timeout : LaneRun<Nothing>
}

/**
 * Выполняет [block], держа полосу, и отменяет его, если полосу потребовал более
 * важный владелец. Полоса освобождается при любом исходе.
 */
internal suspend fun <T> ProbeLane.runPreemptible(
    owner: ProbeLane.Owner,
    waitMillis: Long,
    block: suspend () -> T,
): LaneRun<T> {
    val preempted = AtomicBoolean(false)
    val work = AtomicReference<Job?>()
    val lease = acquireOrNull(owner, waitMillis) {
        preempted.set(true)
        work.get()?.cancel(CancellationException("Probe lane preempted"))
    } ?: return LaneRun.Timeout
    try {
        return coroutineScope {
            val job = async(start = CoroutineStart.LAZY) { block() }
            work.set(job)
            if (preempted.get()) job.cancel(CancellationException("Probe lane preempted"))
            try {
                LaneRun.Done(job.await())
            } catch (cancelled: CancellationException) {
                if (preempted.get() && currentCoroutineContext().isActive) LaneRun.Preempted else throw cancelled
            }
        }
    } finally {
        lease.release()
    }
}

/**
 * Занимает полосу и выдерживает [settle] (случайная пауза автозапуска), не отдавая её. Если
 * более важный владелец потребовал полосу раньше, пауза прерывается, полоса освобождается
 * и возвращается null: вызывающий решает, пробовать ли позже. Иначе возвращается занятая
 * полоса, которую вызывающий обязан освободить.
 */
internal suspend fun ProbeLane.acquireSettled(
    owner: ProbeLane.Owner,
    onPreempt: () -> Unit,
    settle: suspend () -> Unit,
): ProbeLane.Lease? = coroutineScope {
    val yielded = AtomicBoolean(false)
    val pause = AtomicReference<Job?>()
    val lease = acquire(owner) {
        yielded.set(true)
        pause.get()?.cancel(CancellationException("Probe lane preempted"))
        onPreempt()
    }
    try {
        val job = launch { settle() }
        pause.set(job)
        if (yielded.get()) job.cancel(CancellationException("Probe lane preempted"))
        job.join()
        if (yielded.get()) {
            lease.release()
            null
        } else {
            lease
        }
    } catch (error: Throwable) {
        lease.release()
        throw error
    }
}
