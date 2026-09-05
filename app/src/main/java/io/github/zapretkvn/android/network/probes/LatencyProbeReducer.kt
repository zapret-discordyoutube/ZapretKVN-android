package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.vpn.LatencyProbeProgress
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencyUnsupportedReason
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.lastSample
import io.github.zapretkvn.android.vpn.markStale
import io.github.zapretkvn.android.vpn.restoreAfterCancellation

internal object LatencyProbeReducer {
    data class BeginResult(
        val groups: List<RuntimeSelectorGroup>,
        val started: Boolean,
    )

    fun begin(
        groups: List<RuntimeSelectorGroup>,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
        icmpTargets: Set<String>,
    ): BeginResult {
        if (groups.any { it.probeProgress?.running == true }) return BeginResult(groups, false)
        var started = false
        val updated = groups.map { group ->
            if (group.tag != groupTag || group.items.isEmpty()) return@map group
            val nestedTypes = setOf("selector", "urltest")
            val relayUnsupported = group.items.count { it.type.lowercase() in nestedTypes }
            val icmpUnsupported = group.items.count { it.tag !in icmpTargets }
            started = true
            group.copy(
                probeProgress = LatencyProbeProgress(
                    requestId = requestId,
                    networkIdentity = networkIdentity,
                    relayCompleted = relayUnsupported,
                    relayTotal = group.items.size,
                    icmpCompleted = icmpUnsupported,
                    icmpTotal = group.items.size,
                    running = true,
                ),
                items = group.items.map { item ->
                    val nested = item.type.lowercase() in nestedTypes
                    item.copy(
                        relay = if (nested) {
                            LatencyProbeState.Unsupported(LatencyUnsupportedReason.NestedGroup)
                        } else {
                            LatencyProbeState.Running(item.relay.lastSample())
                        },
                        icmp = when {
                            nested -> LatencyProbeState.Unsupported(LatencyUnsupportedReason.NestedGroup)
                            item.tag !in icmpTargets ->
                                LatencyProbeState.Unsupported(LatencyUnsupportedReason.MissingEndpoint)
                            else -> LatencyProbeState.Running(item.icmp.lastSample())
                        },
                    )
                },
            )
        }
        return BeginResult(updated, started)
    }

    fun publishBatch(
        groups: List<RuntimeSelectorGroup>,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
        relay: Map<String, LatencyProbeState>,
        icmp: Map<String, LatencyProbeState>,
    ): List<RuntimeSelectorGroup> = groups.map { group ->
        val progress = group.probeProgress
        if (!progress.matches(group, requestId, groupTag, networkIdentity, requireRunning = true)) {
            return@map group
        }
        checkNotNull(progress)
        var relayCompleted = progress.relayCompleted
        var icmpCompleted = progress.icmpCompleted
        group.copy(
            items = group.items.map { item ->
                val relayState = relay[item.tag]
                val icmpState = icmp[item.tag]
                if (relayState != null && item.relay is LatencyProbeState.Running) relayCompleted++
                if (icmpState != null && item.icmp is LatencyProbeState.Running) icmpCompleted++
                item.copy(
                    relay = relayState ?: item.relay,
                    icmp = icmpState ?: item.icmp,
                )
            },
            probeProgress = progress.copy(
                relayCompleted = relayCompleted.coerceAtMost(progress.relayTotal),
                icmpCompleted = icmpCompleted.coerceAtMost(progress.icmpTotal),
            ),
        )
    }

    fun complete(
        groups: List<RuntimeSelectorGroup>,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
    ): List<RuntimeSelectorGroup> = groups.map { group ->
        val progress = group.probeProgress
        if (progress.matches(group, requestId, groupTag, networkIdentity, requireRunning = true)) {
            group.copy(probeProgress = checkNotNull(progress).copy(running = false))
        } else {
            group
        }
    }

    fun cancel(
        groups: List<RuntimeSelectorGroup>,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
    ): List<RuntimeSelectorGroup> = groups.map { group ->
        val progress = group.probeProgress
        if (!progress.matches(group, requestId, groupTag, networkIdentity, requireRunning = true)) {
            return@map group
        }
        group.copy(
            probeProgress = null,
            items = group.items.map { item ->
                item.copy(
                    relay = item.relay.restoreAfterCancellation(),
                    icmp = item.icmp.restoreAfterCancellation(),
                )
            },
        )
    }

    fun markStale(groups: List<RuntimeSelectorGroup>): List<RuntimeSelectorGroup> = groups.map { group ->
        group.copy(
            probeProgress = null,
            items = group.items.map { item ->
                item.copy(relay = item.relay.markStale(), icmp = item.icmp.markStale())
            },
        )
    }

    private fun LatencyProbeProgress?.matches(
        group: RuntimeSelectorGroup,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
        requireRunning: Boolean,
    ): Boolean = group.tag == groupTag && this != null &&
        this.requestId == requestId && this.networkIdentity == networkIdentity &&
        (!requireRunning || running)
}
