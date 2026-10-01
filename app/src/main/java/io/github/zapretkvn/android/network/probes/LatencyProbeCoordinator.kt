package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencySample
import io.github.zapretkvn.android.vpn.LatencyUnsupportedReason
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.VpnController
import io.github.zapretkvn.android.vpn.lastSample
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.RelayDelayProbeHandler
import io.nekohasekai.libbox.RelayDelayProbeResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal class LatencyProbeCoordinator(
    private val generation: Long,
    private val scope: CoroutineScope,
    private val networkMonitor: DefaultNetworkMonitor,
    private val targetResolver: ServerPingTargetResolver,
    private val icmpProbe: IcmpPingProbe,
    private val controller: VpnController,
    /** Готовые результаты (успех или отказ) для сохранения между запусками. */
    private val onResults: (
        relay: Map<String, LatencyProbeState>,
        icmp: Map<String, LatencyProbeState>,
    ) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val lock = Any()
    private val nextRequestId = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private var active: ActiveProbe? = null

    fun toggle(group: RuntimeSelectorGroup) {
        val previous = synchronized(lock) { active }
        if (previous != null) {
            cancel(previous, stale = false)
            controller.publishMessage(generation, "Проверка задержек отменена.")
            return
        }
        if (closed.get() || group.items.isEmpty()) return
        val underlying = networkMonitor.current
        val network = underlying.network
        val networkIdentity = underlying.identity
        if (network == null || networkIdentity == null) {
            controller.publishMessage(generation, "Основная сеть Android недоступна.")
            return
        }
        val targets = targetResolver.group(group.tag, listOf(group))
        val requestId = nextRequestId.incrementAndGet()
        if (!controller.beginLatencyProbe(
                generation = generation,
                requestId = requestId,
                groupTag = group.tag,
                networkIdentity = networkIdentity,
                icmpTargets = targets.mapTo(mutableSetOf(), ServerPingTarget::outboundTag),
            )
        ) {
            return
        }
        val probe = ActiveProbe(
            requestId = requestId,
            group = group,
            networkIdentity = networkIdentity,
            network = network,
            targets = targets,
        )
        val job = scope.launch(start = CoroutineStart.LAZY) { run(probe) }
        probe.job = job
        val accepted = synchronized(lock) {
            if (closed.get() || active != null) false else {
                active = probe
                true
            }
        }
        if (accepted) {
            job.start()
        } else {
            job.cancel()
            controller.cancelLatencyProbe(generation, requestId, group.tag, networkIdentity)
        }
    }

    fun onNetworkChanged() {
        synchronized(lock) { active }?.let { cancel(it, stale = true) }
        controller.markLatencyStale(generation)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { active }?.let { cancel(it, stale = false) }
    }

    private suspend fun run(probe: ActiveProbe) {
        try {
            val summaries = coroutineScope {
                listOf(
                    async { runRelay(probe) },
                    async { runIcmp(probe) },
                ).awaitAll()
            }
            ensureSameNetwork(probe)
            controller.completeLatencyProbe(
                generation,
                probe.requestId,
                probe.group.tag,
                probe.networkIdentity,
            )
            controller.publishMessage(generation, completionMessage(summaries))
        } catch (changed: ProbeNetworkChangedException) {
            probe.staleOnCancel = true
            controller.markLatencyStale(generation)
            controller.publishMessage(generation, "Сеть изменилась — результаты помечены устаревшими.")
        } catch (cancelled: CancellationException) {
            if (probe.staleOnCancel) controller.markLatencyStale(generation)
            else controller.cancelLatencyProbe(
                generation,
                probe.requestId,
                probe.group.tag,
                probe.networkIdentity,
            )
            throw cancelled
        } finally {
            runCatching { probe.relayClient?.disconnect() }
            synchronized(lock) {
                if (active === probe) active = null
            }
        }
    }

    private suspend fun runRelay(probe: ActiveProbe): ProbeSummary {
        val testable = probe.group.items.filterNot(RuntimeOutboundItem::isNestedGroup)
        val testableTags = testable.mapTo(mutableSetOf(), RuntimeOutboundItem::tag)
        val previousByTag = testable.associate { it.tag to it.relay.lastSample() }
        // Ядро зовёт обработчик из своих потоков.
        val seen = ConcurrentHashMap.newKeySet<String>()
        var success = 0
        var failed = 0
        var unsupported = probe.group.items.size - testable.size
        val client = Libbox.newStandaloneCommandClient()
        probe.relayClient = client
        val results = Channel<Map<String, LatencyProbeState>>(Channel.UNLIMITED)

        coroutineScope {
            val publisher = launch {
                results.collectBatched(PUBLISH_WINDOW_MILLIS) { batch ->
                    for (state in batch.values) {
                        when (state) {
                            is LatencyProbeState.Success -> success++
                            is LatencyProbeState.Unsupported -> unsupported++
                            else -> failed++
                        }
                    }
                    controller.publishLatencyBatch(
                        generation = generation,
                        requestId = probe.requestId,
                        groupTag = probe.group.tag,
                        networkIdentity = probe.networkIdentity,
                        relay = batch,
                    )
                    onResults(batch, emptyMap())
                }
            }
            try {
                callRelay(client, probe.group.tag) { result ->
                    if (result.outboundTag !in testableTags || !seen.add(result.outboundTag)) {
                        return@callRelay
                    }
                    val state = result.toState(
                        probe.networkIdentity,
                        previousByTag[result.outboundTag],
                    )
                    results.trySend(mapOf(result.outboundTag to state))
                }
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
            } finally {
                runCatching { client.disconnect() }
                probe.relayClient = null
            }
            val missing = testable.filter { seen.add(it.tag) }.associate { item ->
                item.tag to LatencyProbeState.Failed(LatencyFailure.Failed, item.relay.lastSample())
            }
            if (missing.isNotEmpty()) results.trySend(missing)
            results.close()
            publisher.join()
        }
        return ProbeSummary("Relay", success, failed, unsupported)
    }

    /**
     * ICMP идёт открытым текстом мимо туннеля, поэтому проба бережная: один
     * Echo на адрес (а не на каждый сервер с этим адресом), случайный порядок и
     * случайная задержка перед каждым. Скользящее окно из [ICMP_CONCURRENCY]
     * проб не ждёт самого медленного сервера, как ждали бы пачки.
     */
    private suspend fun runIcmp(probe: ActiveProbe): ProbeSummary {
        var success = 0
        var failed = 0
        val unsupported = probe.group.items.size - probe.targets.size
        val previousByTag = probe.group.items.associate { it.tag to it.icmp.lastSample() }
        val hosts = probe.targets.groupedByHost()
        val queue = Channel<List<ServerPingTarget>>(Channel.UNLIMITED)
        hosts.forEach { queue.trySend(it) }
        queue.close()
        val results = Channel<Map<String, LatencyProbeState>>(Channel.UNLIMITED)

        coroutineScope {
            val workers = List(minOf(ICMP_CONCURRENCY, hosts.size)) {
                launch {
                    for (sameHost in queue) {
                        ensureSameNetwork(probe)
                        delay(Random.nextLong(ICMP_START_JITTER_MILLIS + 1))
                        val outcome = measureIcmp(probe, sameHost.first())
                        results.send(
                            sameHost.associate { target ->
                                target.outboundTag to outcome.toState(
                                    probe.networkIdentity,
                                    previousByTag[target.outboundTag],
                                )
                            },
                        )
                    }
                }
            }
            launch {
                workers.joinAll()
                results.close()
            }
            results.collectBatched(PUBLISH_WINDOW_MILLIS) { batch ->
                ensureSameNetwork(probe)
                val succeeded = batch.values.count { it is LatencyProbeState.Success }
                success += succeeded
                failed += batch.size - succeeded
                controller.publishLatencyBatch(
                    generation = generation,
                    requestId = probe.requestId,
                    groupTag = probe.group.tag,
                    networkIdentity = probe.networkIdentity,
                    icmp = batch,
                )
                onResults(emptyMap(), batch)
            }
        }
        return ProbeSummary("ICMP", success, failed, unsupported)
    }

    private suspend fun measureIcmp(probe: ActiveProbe, target: ServerPingTarget): IcmpOutcome = try {
        val millis = icmpProbe.measure(probe.network, target)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        IcmpOutcome(millis, System.currentTimeMillis(), null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IcmpProbeException) {
        IcmpOutcome(null, 0L, error.failure)
    } catch (_: Throwable) {
        IcmpOutcome(null, 0L, LatencyFailure.Failed)
    }

    private suspend fun callRelay(
        client: CommandClient,
        groupTag: String,
        onResult: (RelayDelayProbeResult) -> Unit,
    ) = suspendCancellableCoroutine { continuation ->
        val worker = scope.launch(Dispatchers.IO) {
            try {
                client.probeOutboundRelayDelays(groupTag, RelayDelayProbeHandler(onResult))
                if (continuation.isActive) continuation.resume(Unit)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            runCatching { client.disconnect() }
            worker.cancel()
        }
    }

    private fun ensureSameNetwork(probe: ActiveProbe) {
        if (networkMonitor.current.identity != probe.networkIdentity) {
            throw ProbeNetworkChangedException()
        }
    }

    private fun cancel(probe: ActiveProbe, stale: Boolean) {
        probe.staleOnCancel = stale
        runCatching { probe.relayClient?.disconnect() }
        probe.job?.cancel(CancellationException("Latency probe cancelled"))
        synchronized(lock) {
            if (active === probe) active = null
        }
        if (stale) controller.markLatencyStale(generation)
        else controller.cancelLatencyProbe(
            generation,
            probe.requestId,
            probe.group.tag,
            probe.networkIdentity,
        )
    }

    private fun completionMessage(summaries: List<ProbeSummary>): String = summaries.joinToString("; ") {
        "${it.label}: успешно ${it.success}, ошибок ${it.failed}, не поддерживается ${it.unsupported}"
    }

    private class ActiveProbe(
        val requestId: Long,
        val group: RuntimeSelectorGroup,
        val networkIdentity: String,
        val network: android.net.Network,
        val targets: List<ServerPingTarget>,
    ) {
        @Volatile var relayClient: CommandClient? = null
        @Volatile var job: Job? = null
        @Volatile var staleOnCancel: Boolean = false
    }

    private data class ProbeSummary(
        val label: String,
        val success: Int,
        val failed: Int,
        val unsupported: Int,
    )

    /** Итог одной ICMP-пробы адреса; в состояние превращается для каждого сервера с этим адресом. */
    private class IcmpOutcome(
        val millis: Int?,
        val measuredAtEpochMillis: Long,
        val failure: LatencyFailure?,
    ) {
        fun toState(networkIdentity: String, previous: LatencySample?): LatencyProbeState =
            if (millis != null) {
                LatencyProbeState.Success(LatencySample(millis, measuredAtEpochMillis, networkIdentity))
            } else {
                LatencyProbeState.Failed(failure ?: LatencyFailure.Failed, previous)
            }
    }

    private class ProbeNetworkChangedException : Exception()

    private companion object {
        const val ICMP_CONCURRENCY = 4
        const val ICMP_START_JITTER_MILLIS = 150L
        const val PUBLISH_WINDOW_MILLIS = 200L
    }
}

private fun RuntimeOutboundItem.isNestedGroup(): Boolean =
    type.equals("selector", ignoreCase = true) || type.equals("urltest", ignoreCase = true)

private fun RelayDelayProbeResult.toState(
    networkIdentity: String,
    previous: LatencySample?,
): LatencyProbeState = when (outcome) {
    Libbox.RelayDelayOutcomeSuccess -> LatencyProbeState.Success(
        LatencySample(
            millis = delayMillis.coerceAtLeast(0),
            measuredAtEpochMillis = testedAtUnixMillis.takeIf { it > 0L }
                ?: System.currentTimeMillis(),
            networkIdentity = networkIdentity,
        ),
    )
    Libbox.RelayDelayOutcomeTimeout -> LatencyProbeState.Failed(LatencyFailure.NoResponse, previous)
    Libbox.RelayDelayOutcomeUnsupported ->
        LatencyProbeState.Unsupported(LatencyUnsupportedReason.NestedGroup)
    else -> LatencyProbeState.Failed(LatencyFailure.Failed, previous)
}
