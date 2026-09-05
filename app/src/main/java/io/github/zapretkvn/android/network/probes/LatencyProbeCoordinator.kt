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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal class LatencyProbeCoordinator(
    private val generation: Long,
    private val scope: CoroutineScope,
    private val networkMonitor: DefaultNetworkMonitor,
    private val targetResolver: ServerPingTargetResolver,
    private val icmpProbe: IcmpPingProbe,
    private val controller: VpnController,
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
        val seen = mutableSetOf<String>()
        val pendingBatch = linkedMapOf<String, LatencyProbeState>()
        var success = 0
        var failed = 0
        var unsupported = probe.group.items.size - testable.size
        val client = Libbox.newStandaloneCommandClient()
        probe.relayClient = client

        fun flush() {
            if (pendingBatch.isEmpty()) return
            controller.publishLatencyBatch(
                generation = generation,
                requestId = probe.requestId,
                groupTag = probe.group.tag,
                networkIdentity = probe.networkIdentity,
                relay = pendingBatch.toMap(),
            )
            pendingBatch.clear()
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
                when (state) {
                    is LatencyProbeState.Success -> success++
                    is LatencyProbeState.Unsupported -> unsupported++
                    else -> failed++
                }
                pendingBatch[result.outboundTag] = state
                if (pendingBatch.size >= RELAY_PUBLISH_BATCH) flush()
            }
            currentCoroutineContext().ensureActive()
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
        } finally {
            runCatching { client.disconnect() }
            probe.relayClient = null
        }
        for (item in testable) {
            if (seen.add(item.tag)) {
                failed++
                pendingBatch[item.tag] = LatencyProbeState.Failed(
                    LatencyFailure.Failed,
                    item.relay.lastSample(),
                )
            }
        }
        flush()
        return ProbeSummary("Relay", success, failed, unsupported)
    }

    private suspend fun runIcmp(probe: ActiveProbe): ProbeSummary {
        var success = 0
        var failed = 0
        val unsupported = probe.group.items.size - probe.targets.size
        val previousByTag = probe.group.items.associate { it.tag to it.icmp.lastSample() }
        for (chunk in probe.targets.chunked(ICMP_CONCURRENCY)) {
            ensureSameNetwork(probe)
            val states = coroutineScope {
                chunk.map { target ->
                    async {
                        target.outboundTag to try {
                            val millis = icmpProbe.measure(probe.network, target)
                                .coerceIn(0L, Int.MAX_VALUE.toLong())
                                .toInt()
                            LatencyProbeState.Success(
                                LatencySample(
                                    millis = millis,
                                    measuredAtEpochMillis = System.currentTimeMillis(),
                                    networkIdentity = probe.networkIdentity,
                                ),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: IcmpProbeException) {
                            LatencyProbeState.Failed(error.failure, previousByTag[target.outboundTag])
                        } catch (_: Throwable) {
                            LatencyProbeState.Failed(
                                LatencyFailure.Failed,
                                previousByTag[target.outboundTag],
                            )
                        }
                    }
                }.awaitAll().toMap()
            }
            ensureSameNetwork(probe)
            success += states.values.count { it is LatencyProbeState.Success }
            failed += states.size - states.values.count { it is LatencyProbeState.Success }
            controller.publishLatencyBatch(
                generation = generation,
                requestId = probe.requestId,
                groupTag = probe.group.tag,
                networkIdentity = probe.networkIdentity,
                icmp = states,
            )
        }
        return ProbeSummary("ICMP", success, failed, unsupported)
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

    private class ProbeNetworkChangedException : Exception()

    private companion object {
        const val ICMP_CONCURRENCY = 4
        const val RELAY_PUBLISH_BATCH = 10
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
