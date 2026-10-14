package io.github.zapretkvn.android.network.probes.services

import android.os.SystemClock
import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.probes.ProbeLane
import io.github.zapretkvn.android.vpn.VpnController
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Проверка доступности сервисов одной сессии VPN. Запускается только нажатием
 * на карточку и один раз сама после подключения, и только пока Главная на
 * экране. Цели берёт из [ServiceCatalog]; профиль нужен лишь для подсказки о
 * маршруте и в запросах не участвует.
 */
internal class ServiceAvailabilityCoordinator(
    private val generation: Long,
    private val scope: CoroutineScope,
    private val networkMonitor: DefaultNetworkMonitor,
    private val controller: VpnController,
    private val paths: AndroidServiceProbePaths,
    private val profileJson: String,
    private val lane: ProbeLane = controller.probeLane,
    private val runner: ServiceProbeRunner = ServiceProbeRunner(),
    private val definitions: () -> List<ServiceDefinition> = { ServiceCatalog.defaults },
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = ::probeSleep,
) : AutoCloseable {
    private class Active(val requestId: Long, val networkIdentity: String) {
        @Volatile var job: Job? = null
        @Volatile var staleOnCancel = false
    }

    private val lock = Any()
    private val policy = ServiceCheckPolicy(elapsed)
    private val nextRequestId = AtomicLong(0)
    private var active: Active? = null
    private var reserved = false
    private var closed = false
    private var autoJob: Job? = null

    /** Нажатие на карточку. */
    fun requestCheck() {
        synchronized(lock) {
            if (closed || reserved || active != null) return
            if (!policy.tapAllowed()) {
                controller.publishMessage(generation, "Сервисы только что проверены.")
                return
            }
            reserved = true
        }
        val lease = lane.tryAcquire(ProbeLane.Owner.Services, onPreempt = ::preempt)
        if (lease == null) {
            synchronized(lock) { reserved = false }
            controller.publishMessage(generation, "Идёт проверка серверов.")
            return
        }
        if (!startRun(ServiceCheckTrigger.Tap, lease)) lease.release()
    }

    /** Один автозапуск за сессию: ждёт, пока Главная видна, затем случайная пауза. */
    fun scheduleAutoCheck() {
        if (!policy.claimAutoRun()) return
        val job = scope.launch {
            while (true) {
                controller.homeVisible.first { it }
                sleep(random.nextLong(ServiceCheckPolicy.AUTO_DELAY_MIN_MILLIS, ServiceCheckPolicy.AUTO_DELAY_MAX_MILLIS + 1))
                if (!controller.homeVisible.value) continue
                // Пользователь уже нажал сам, или проверка идёт: автозапуск не нужен.
                if (hasResultsOrRunning()) return@launch
                val lease = lane.acquire(ProbeLane.Owner.Services, onPreempt = ::preempt)
                var handedOver = false
                try {
                    sleep(random.nextLong(ServiceCheckPolicy.LANE_JITTER_MIN_MILLIS, ServiceCheckPolicy.LANE_JITTER_MAX_MILLIS + 1))
                    if (!controller.homeVisible.value || closedNow()) continue
                    if (hasResultsOrRunning()) return@launch
                    handedOver = startRun(ServiceCheckTrigger.Auto, lease)
                    return@launch
                } finally {
                    if (!handedOver) lease.release()
                }
            }
        }
        synchronized(lock) { autoJob = job }
    }

    /** Сеть или сервер сменились: идущий запуск отменяется, результаты устаревают. */
    fun markStale() {
        val running = synchronized(lock) { active }
        if (running != null) {
            running.staleOnCancel = true
            running.job?.cancel(CancellationException("Service check superseded"))
        }
        controller.updateServiceAvailability(generation, ServiceAvailabilityReducer::markStale)
    }

    override fun close() {
        val (running, auto) = synchronized(lock) {
            closed = true
            active to autoJob
        }
        auto?.cancel()
        running?.job?.cancel(CancellationException("Service check closed"))
    }

    private fun closedNow() = synchronized(lock) { closed }

    private fun hasResultsOrRunning(): Boolean {
        val state = controller.serviceAvailability.value
        return state.running || state.items.values.any { it.snapshotOrNull() != null }
    }

    private fun ServiceAvailability.snapshotOrNull() = when (this) {
        is ServiceAvailability.Result -> snapshot
        is ServiceAvailability.Stale -> snapshot
        else -> null
    }

    /** Вытеснение массовой проверкой серверов: запуск отменяется, прежние результаты возвращаются. */
    private fun preempt() {
        synchronized(lock) { active }?.job?.cancel(CancellationException("Service check preempted"))
    }

    /** Берёт [lease] на себя при true; при false вызывающий освобождает его сам. */
    private fun startRun(trigger: ServiceCheckTrigger, lease: ProbeLane.Lease): Boolean {
        val underlying = networkMonitor.current
        val identity = underlying.identity
        if (identity == null) {
            synchronized(lock) { reserved = false }
            if (trigger == ServiceCheckTrigger.Tap) {
                controller.publishMessage(generation, "Основная сеть Android недоступна.")
            }
            return false
        }
        val services = definitions()
        val requestId = nextRequestId.incrementAndGet()
        var started = false
        controller.updateServiceAvailability(generation) { current ->
            ServiceAvailabilityReducer.begin(current, requestId, identity, services.map { it.id }, trigger)
                .also { started = it.started }
                .state
        }
        if (!started) {
            synchronized(lock) { reserved = false }
            return false
        }
        val run = Active(requestId, identity)
        val accepted = synchronized(lock) {
            reserved = false
            if (closed || active != null) false else {
                active = run
                true
            }
        }
        if (!accepted) {
            controller.updateServiceAvailability(generation) {
                ServiceAvailabilityReducer.cancel(it, requestId, identity)
            }
            return false
        }
        run.job = scope.launch { execute(run, trigger, services, underlying.network, lease) }
        return true
    }

    private suspend fun execute(
        run: Active,
        trigger: ServiceCheckTrigger,
        services: List<ServiceDefinition>,
        directNetwork: android.net.Network?,
        lease: ProbeLane.Lease,
    ) {
        val hints = services.associate { it.id to ServiceRouteHint.of(profileJson, it) }
        try {
            val report = runner.run(
                definitions = services,
                paths = RunPaths(paths::openTunnel, paths.direct(directNetwork)),
                networkIdentity = run.networkIdentity,
                generation = generation,
                route = { id -> hints[id] ?: RouteHint.Unknown },
                publish = { batch ->
                    controller.updateServiceAvailability(generation) {
                        ServiceAvailabilityReducer.publishBatch(it, run.requestId, run.networkIdentity, batch)
                    }
                },
            )
            policy.runFinished()
            if (networkMonitor.current.identity != run.networkIdentity) {
                controller.updateServiceAvailability(generation, ServiceAvailabilityReducer::markStale)
            } else {
                controller.updateServiceAvailability(generation) {
                    ServiceAvailabilityReducer.complete(
                        it,
                        run.requestId,
                        run.networkIdentity,
                        ServiceRunSummary(
                            trigger = trigger,
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
            controller.updateServiceAvailability(generation) {
                if (run.staleOnCancel) ServiceAvailabilityReducer.markStale(it)
                else ServiceAvailabilityReducer.cancel(it, run.requestId, run.networkIdentity)
            }
            throw cancelled
        } finally {
            synchronized(lock) { if (active === run) active = null }
            lease.release()
        }
    }
}
