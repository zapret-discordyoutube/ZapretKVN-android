package io.github.zapretkvn.android.network.probes.services

import android.os.SystemClock
import io.github.zapretkvn.android.network.probes.ProbeLane
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Физическая сеть на время показа результатов: адрес сети и транспорт, привязанный к ней. */
internal interface DirectNetworkSession : AutoCloseable {
    /** Отпечаток сети сейчас; null — сети нет. */
    val identity: String?

    /** Транспорт, привязанный к текущей физической сети; null — привязать нечего. */
    fun transport(): ServiceProbeTransport?
}

internal fun interface DirectNetworkSource {
    /**
     * Открывает наблюдение за физической сетью. [onChange] вызывается, когда отпечаток
     * сети стал другим; первое значение при открытии не считается сменой.
     */
    fun open(onChange: () -> Unit): DirectNetworkSession
}

/**
 * Проверка доступности сервисов без VPN, уровня приложения: работает, пока VPN
 * остановлен. Идёт только по нажатию на карточку и только прямым путём: та же
 * физическая сеть, те же цели каталога, те же бюджеты и три воркера, что у
 * проверки в сессии, но без туннеля и без профиля. Автозапусков и таймеров нет.
 * Результаты лежат только в памяти: сеть сменилась — они устаревают, VPN начал
 * подключаться — стираются (дальше карточку ведёт проверка сессии).
 */
internal class DirectServiceChecker(
    private val scope: CoroutineScope,
    private val lane: ProbeLane,
    private val source: DirectNetworkSource,
    private val definitions: () -> List<ServiceDefinition>,
    private val runner: ServiceProbeRunner = ServiceProbeRunner(),
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Active(val requestId: Long, val networkIdentity: String) {
        @Volatile var job: Job? = null
        @Volatile var staleOnCancel = false
        @Volatile var cancelRequested = false

        fun cancel(reason: String) {
            cancelRequested = true
            job?.cancel(CancellationException(reason))
        }
    }

    private val mutableState = MutableStateFlow(ServiceAvailabilityState.Initial)
    val state: StateFlow<ServiceAvailabilityState> = mutableState.asStateFlow()

    private val lock = Any()
    private val policy = ServiceCheckPolicy(elapsed)
    private val nextRequestId = AtomicLong(0)
    private var active: Active? = null
    private var session: DirectNetworkSession? = null
    private var reserved = false
    private var vpnIdle = true
    private var epoch = 0L

    /** Полосу потребовали, пока запуск ещё не зарегистрирован: он отменяется сразу после старта. */
    private var preemptEarly = false

    /** Нажатие на карточку при остановленном VPN. */
    fun check() {
        val startEpoch = synchronized(lock) {
            if (!vpnIdle || reserved || active != null || !policy.tapAllowed()) return
            reserved = true
            preemptEarly = false
            epoch
        }
        val lease = lane.tryAcquire(ProbeLane.Owner.Services, onPreempt = ::preempt)
        if (lease == null) {
            synchronized(lock) { reserved = false }
            return
        }
        if (!start(startEpoch, lease)) lease.release()
    }

    /**
     * Состояние VPN. Пока он не остановлен, проверка недоступна, идущая прерывается,
     * результаты стираются и наблюдение за сетью закрывается.
     */
    fun onVpnIdle(idle: Boolean) {
        if (idle) {
            synchronized(lock) { vpnIdle = true }
            return
        }
        val (running, opened) = synchronized(lock) {
            vpnIdle = false
            epoch += 1
            val taken = session
            session = null
            active to taken
        }
        running?.let { it.staleOnCancel = false; it.cancel("VPN is starting") }
        runCatching { opened?.close() }
        mutableState.value = ServiceAvailabilityState.Initial
    }

    /** Сеть сменилась: идущая проверка прерывается, результаты помечаются устаревшими. */
    private fun networkChanged() {
        val running = synchronized(lock) { active }
        running?.let { it.staleOnCancel = true; it.cancel("Network changed") }
        mutableState.update(ServiceAvailabilityReducer::markStale)
    }

    /** Полосу потребовало подключение: проверка уступает, прежние результаты остаются. */
    private fun preempt() {
        val running = synchronized(lock) {
            if (active == null) preemptEarly = true
            active
        }
        running?.cancel("Probe lane preempted")
    }

    /** Берёт [lease] на себя при true; при false вызывающий отдаёт его сам. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun start(startEpoch: Long, lease: ProbeLane.Lease): Boolean {
        val opened: DirectNetworkSession? = synchronized(lock) {
            if (epoch != startEpoch) {
                reserved = false
                return false
            }
            session ?: runCatching { source.open(::networkChanged) }.getOrNull()?.also { session = it }
        }
        val identity = opened?.identity
        val transport = opened?.transport()
        if (identity == null || transport == null) {
            synchronized(lock) {
                reserved = false
                // Наблюдать нечего: без результатов сеть не держим открытой.
                if (mutableState.value.items.isEmpty()) {
                    session = null
                    runCatching { opened?.close() }
                }
            }
            mutableState.update(ServiceAvailabilityReducer::noNetwork)
            return false
        }
        val services = definitions()
        val requestId = nextRequestId.incrementAndGet()
        val run = Active(requestId, identity)
        val accepted = synchronized(lock) {
            reserved = false
            if (epoch != startEpoch || active != null) false else {
                active = run
                true
            }
        }
        if (!accepted) return false
        mutableState.update { current ->
            ServiceAvailabilityReducer.begin(
                current, requestId, identity, services.map { it.id }, ServiceCheckTrigger.Tap,
            ).state
        }
        // ATOMIC: отмена до первого шага не должна пропустить finally, который отдаёт полосу.
        val job = scope.launch(start = CoroutineStart.ATOMIC) { execute(run, services, transport, opened, lease) }
        run.job = job
        val early = synchronized(lock) { preemptEarly.also { preemptEarly = false } }
        if (run.cancelRequested || early) job.cancel(CancellationException("Direct check cancelled"))
        return true
    }

    private suspend fun execute(
        run: Active,
        services: List<ServiceDefinition>,
        transport: ServiceProbeTransport,
        network: DirectNetworkSession,
        lease: ProbeLane.Lease,
    ) {
        try {
            val report = runner.run(
                definitions = services,
                paths = RunPaths({ null }, transport),
                networkIdentity = run.networkIdentity,
                generation = DIRECT_GENERATION,
                route = { RouteHint.Unknown },
                publish = { batch ->
                    mutableState.update {
                        ServiceAvailabilityReducer.publishBatch(it, run.requestId, run.networkIdentity, batch)
                    }
                },
            )
            policy.runFinished()
            if (network.identity != run.networkIdentity) {
                mutableState.update(ServiceAvailabilityReducer::markStale)
            } else {
                mutableState.update {
                    ServiceAvailabilityReducer.complete(
                        it,
                        run.requestId,
                        run.networkIdentity,
                        ServiceRunSummary(
                            trigger = ServiceCheckTrigger.Tap,
                            durationMillis = report.durationMillis,
                            requestsPlanned = report.requestsPlanned,
                            requestsDone = report.requestsMade,
                            bytes = report.bytes,
                            finishedAtElapsedMillis = elapsed(),
                        ),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            mutableState.update {
                if (run.staleOnCancel) ServiceAvailabilityReducer.markStale(it)
                else ServiceAvailabilityReducer.cancel(it, run.requestId, run.networkIdentity)
            }
            throw cancelled
        } finally {
            synchronized(lock) { if (active === run) active = null }
            lease.release()
        }
    }

    companion object {
        /** Снимки без сессии VPN: поколения у них нет. */
        const val DIRECT_GENERATION = 0L
    }
}
