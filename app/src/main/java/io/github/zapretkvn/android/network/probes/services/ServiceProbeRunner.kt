package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind
import io.github.zapretkvn.android.network.probes.collectBatched
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Единственная пауза этого пакета: случайный сдвиг старта запросов, повтор
 * основного запроса туннеля и разовая задержка автозапуска. Ни цикла, ни
 * периодического таймера: каждая пауза стоит внутри одного запуска проверки.
 */
internal suspend fun probeSleep(millis: Long) {
    if (millis > 0) delay(millis)
}

/** Транспорт одного пути. Исключения наружу не выходят, кроме [PathLostException] и отмены. */
internal interface ServiceProbeTransport {
    suspend fun https(endpoint: ProbeEndpoint): TransportResult
    suspend fun tcp(target: TcpEndpoint): TransportResult
}

/** VPN-сеть, на которой шла проверка, пропала: возвращаться на несвязанный сокет нельзя. */
internal class PathLostException : Exception()

internal interface TunnelPath : AutoCloseable {
    val transport: ServiceProbeTransport
}

/** Пути запуска. [direct] == null, если физическую сеть привязать нельзя. */
internal class RunPaths(
    val openTunnel: suspend () -> TunnelPath?,
    val direct: ServiceProbeTransport?,
)

internal data class ServiceRunReport(
    val requestsPlanned: Int,
    val requestsMade: Int,
    val bytes: Int,
    val durationMillis: Long,
    val tunnelAvailable: Boolean,
    val directAvailable: Boolean,
    val timedOut: Boolean,
)

internal class ServiceProbeRunner(
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = ::probeSleep,
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val workers: Int = WORKERS,
    private val runTimeoutMillis: Long = ServiceProbeBudget.RUN_TIMEOUT_MILLIS,
    private val requestTimeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) {
    suspend fun run(
        definitions: List<ServiceDefinition>,
        paths: RunPaths,
        networkIdentity: String,
        generation: Long,
        route: (ServiceId) -> RouteHint,
        publish: suspend (Map<ServiceId, ServiceSnapshot>) -> Unit,
    ): ServiceRunReport {
        val started = monotonicMillis()
        val tunnelPath = paths.openTunnel()
        try {
            val plan = ServiceProbePlanner.plan(
                definitions,
                tunnel = tunnelPath != null,
                direct = paths.direct != null,
                random = random,
            )
            val accumulator = ServiceRunAccumulator(plan, definitions, route, networkIdentity, generation, clock)
            val made = AtomicInteger()
            val bytes = AtomicInteger()
            val tunnelLost = AtomicBoolean(false)
            var timedOut = false
            val queue = Channel<PlannedRequest>(Channel.UNLIMITED)
            plan.requests.forEach { queue.trySend(it) }
            queue.close()
            val snapshots = Channel<Map<ServiceId, ServiceSnapshot>>(Channel.UNLIMITED)
            val lock = Any()

            fun settle(request: PlannedRequest?, outcome: PathOutcome?, leftover: SkipReason?) {
                val ready = synchronized(lock) {
                    when {
                        request != null && outcome != null -> accumulator.accept(request, outcome)
                        leftover != null -> accumulator.finish(leftover)
                        else -> emptyList()
                    }
                }
                if (ready.isNotEmpty()) snapshots.trySend(ready.associateBy(ServiceSnapshot::service))
            }

            val executor = Executor(
                tunnel = tunnelPath?.transport,
                direct = paths.direct,
                made = made,
                bytes = bytes,
                tunnelLost = tunnelLost,
            )
            coroutineScope {
                val publisher = launch { snapshots.collectBatched(PUBLISH_WINDOW_MILLIS) { publish(it) } }
                val finished = withTimeoutOrNull(runTimeoutMillis) {
                    List(minOf(workers, plan.requests.size).coerceAtLeast(1)) {
                        launch {
                            for (request in queue) {
                                settle(request, executor.execute(request), null)
                            }
                        }
                    }.joinAll()
                    true
                }
                timedOut = finished == null
                settle(null, null, if (tunnelLost.get() && !timedOut) SkipReason.NoVpn else SkipReason.Budget)
                snapshots.close()
                publisher.join()
            }
            return ServiceRunReport(
                requestsPlanned = plan.requests.size,
                requestsMade = made.get(),
                bytes = bytes.get(),
                durationMillis = monotonicMillis() - started,
                tunnelAvailable = tunnelPath != null,
                directAvailable = paths.direct != null,
                timedOut = timedOut,
            )
        } finally {
            tunnelPath?.close()
        }
    }

    private inner class Executor(
        private val tunnel: ServiceProbeTransport?,
        private val direct: ServiceProbeTransport?,
        private val made: AtomicInteger,
        private val bytes: AtomicInteger,
        private val tunnelLost: AtomicBoolean,
    ) {
        suspend fun execute(request: PlannedRequest): PathOutcome {
            val isTunnel = request.path == PathKind.Tunnel
            val transport = (if (isTunnel) tunnel else direct)
                ?: return PathOutcome.Skipped(if (isTunnel) SkipReason.NoVpn else SkipReason.DirectUnavailable)
            if (isTunnel && tunnelLost.get()) return PathOutcome.Skipped(SkipReason.NoVpn)
            sleep(if (isTunnel) random.nextLong(0, TUNNEL_JITTER_MILLIS + 1) else {
                random.nextLong(DIRECT_JITTER_MIN_MILLIS, DIRECT_JITTER_MAX_MILLIS + 1)
            })
            var outcome = once(request, transport, isTunnel) ?: return skipFor(isTunnel)
            // Единственный повтор: только основной адрес и только через туннель (QUIC/Hysteria переподключается).
            if (isTunnel && request.isPrimary && outcome.isRetryable()) {
                sleep(random.nextLong(RETRY_DELAY_MIN_MILLIS, RETRY_DELAY_MAX_MILLIS + 1))
                outcome = once(request, transport, isTunnel) ?: outcome
            }
            return outcome
        }

        private fun skipFor(isTunnel: Boolean): PathOutcome = PathOutcome.Skipped(
            if (isTunnel && tunnelLost.get()) SkipReason.NoVpn else SkipReason.Budget,
        )

        /** null — бюджет исчерпан или VPN-сеть пропала, запрос не выполнялся. */
        private suspend fun once(request: PlannedRequest, transport: ServiceProbeTransport, isTunnel: Boolean): PathOutcome? {
            if (bytes.get() >= ServiceProbeBudget.MAX_BYTES) return null
            if (made.incrementAndGet() > ServiceProbeBudget.MAX_REQUESTS) return null
            val result = try {
                withTimeoutOrNull(requestTimeoutMillis) {
                    when (val target = request.target) {
                        is ProbeTarget.Https -> transport.https(target.endpoint)
                        is ProbeTarget.Tcp -> transport.tcp(target.endpoint)
                    }
                } ?: TransportResult.Failure(HttpsProbeFailureKind.Timeout, ServiceProbeBudget.HANDSHAKE_BYTES_ESTIMATE)
            } catch (lost: PathLostException) {
                if (isTunnel) tunnelLost.set(true)
                return null
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            bytes.addAndGet(result.bytes)
            return when (val target = request.target) {
                is ProbeTarget.Https -> ServiceVerdictCalculator.classify(target.endpoint, result)
                is ProbeTarget.Tcp -> ServiceVerdictCalculator.classify(ProbeEndpoint(target.endpoint.ip), result)
            }
        }

        private fun PathOutcome.isRetryable(): Boolean = this is PathOutcome.Blocked &&
            (kind == HttpsProbeFailureKind.Timeout || kind == HttpsProbeFailureKind.Reset)
    }

    companion object {
        const val WORKERS = 3
        const val TUNNEL_JITTER_MILLIS = 150L
        const val DIRECT_JITTER_MIN_MILLIS = 100L
        const val DIRECT_JITTER_MAX_MILLIS = 400L
        const val RETRY_DELAY_MIN_MILLIS = 1_500L
        const val RETRY_DELAY_MAX_MILLIS = 3_000L
        const val PUBLISH_WINDOW_MILLIS = 200L
        const val REQUEST_TIMEOUT_MILLIS = 7_000L
    }
}
