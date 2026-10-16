package io.github.zapretkvn.android.network.probes.services

import android.os.SystemClock
import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.probes.ProbeLane
import io.github.zapretkvn.android.vpn.VpnController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Только выбранные в настройках сервисы: чем их меньше, тем меньше запросов. */
    private val definitions: () -> List<ServiceDefinition> = { ServiceCatalog.resolve(controller.enabledServices.value) },
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
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

    /** Полосу потребовали раньше, чем запуск успел зарегистрироваться: он отменяется сразу после старта. */
    private val preemptPending = AtomicBoolean(false)

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
        preemptPending.set(false)
        val lease = lane.tryAcquire(ProbeLane.Owner.Services, onPreempt = ::preempt)
        if (lease == null) {
            synchronized(lock) { reserved = false }
            controller.publishMessage(generation, "Идёт другая проверка, повторите чуть позже.")
            return
        }
        if (!startRun(ServiceCheckTrigger.Tap, lease)) lease.release()
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
        val running = synchronized(lock) {
            closed = true
            active
        }
        running?.job?.cancel(CancellationException("Service check closed"))
    }

    private fun ServiceAvailability.snapshotOrNull() = when (this) {
        is ServiceAvailability.Result -> snapshot
        is ServiceAvailability.Stale -> snapshot
        else -> null
    }

    /** Вытеснение массовой проверкой серверов: запуск отменяется, прежние результаты возвращаются. */
    private fun preempt() {
        preemptPending.set(true)
        synchronized(lock) { active }?.job?.cancel(CancellationException("Service check preempted"))
    }

    /** Берёт [lease] на себя при true; при false вызывающий освобождает его сам. */
    @OptIn(DelicateCoroutinesApi::class)
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
        // ATOMIC: отмена до первого шага не должна пропустить finally, который отдаёт полосу.
        val job = scope.launch(start = CoroutineStart.ATOMIC) { execute(run, trigger, services, underlying.network, lease) }
        run.job = job
        if (preemptPending.compareAndSet(true, false)) job.cancel(CancellationException("Service check preempted"))
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
