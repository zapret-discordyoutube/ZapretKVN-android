package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.network.probes.LatencyProbeReducer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Публикация групп серверов и результатов замеров задержки. Состояние групп
 * принадлежит [VpnController] (он же сбрасывает его при смене сессии); здесь
 * только правила слияния и отсев устаревших поколений.
 */
internal class LatencyPublisher(
    private val mutable: MutableStateFlow<List<RuntimeSelectorGroup>>,
    private val currentGeneration: () -> Long,
) {
    internal fun publishGroups(generation: Long, groups: List<RuntimeSelectorGroup>) {
        mutable.update { current ->
            if (generation != currentGeneration()) return@update current
            val priorGroups = current.associateBy(RuntimeSelectorGroup::tag)
            groups.map { incoming ->
                val prior = priorGroups[incoming.tag]
                val priorItems = prior?.items?.associateBy(RuntimeOutboundItem::tag).orEmpty()
                incoming.copy(
                    probeProgress = prior?.probeProgress,
                    items = incoming.items.map { item ->
                        val previous = priorItems[item.tag] ?: return@map item
                        item.copy(
                            icmp = previous.icmp,
                            relay = when {
                                prior?.probeProgress?.running == true -> previous.relay
                                item.relay.lastSample()?.measuredAtEpochMillis
                                    ?.let { incomingTime ->
                                        incomingTime >
                                            (previous.relay.lastSample()?.measuredAtEpochMillis ?: 0L)
                                    } == true -> item.relay
                                previous.relay == LatencyProbeState.NotTested -> item.relay
                                else -> previous.relay
                            },
                        )
                    },
                )
            }
        }
    }

    internal fun beginLatencyProbe(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
        icmpTargets: Set<String>,
    ): Boolean {
        var started = false
        mutable.update { groups ->
            if (generation != currentGeneration()) return@update groups
            val result = LatencyProbeReducer.begin(
                groups,
                requestId,
                groupTag,
                networkIdentity,
                icmpTargets,
            )
            started = result.started
            result.groups
        }
        return started
    }

    internal fun publishLatencyBatch(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
        relay: Map<String, LatencyProbeState> = emptyMap(),
        icmp: Map<String, LatencyProbeState> = emptyMap(),
    ) {
        if (relay.isEmpty() && icmp.isEmpty()) return
        mutable.update { groups ->
            if (generation != currentGeneration()) return@update groups
            LatencyProbeReducer.publishBatch(
                groups,
                requestId,
                groupTag,
                networkIdentity,
                relay,
                icmp,
            )
        }
    }

    internal fun completeLatencyProbe(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
    ) {
        mutable.update { groups ->
            if (generation != currentGeneration()) return@update groups
            LatencyProbeReducer.complete(groups, requestId, groupTag, networkIdentity)
        }
    }

    internal fun cancelLatencyProbe(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
    ) {
        mutable.update { groups ->
            if (generation != currentGeneration()) return@update groups
            LatencyProbeReducer.cancel(groups, requestId, groupTag, networkIdentity)
        }
    }

    internal fun markLatencyStale(generation: Long) {
        mutable.update { groups ->
            if (generation != currentGeneration()) return@update groups
            LatencyProbeReducer.markStale(groups)
        }
    }

    internal fun publishBackgroundIcmp(
        generation: Long,
        outboundTag: String,
        state: LatencyProbeState,
    ) {
        if (generation != currentGeneration() || outboundTag.isBlank()) return
        mutable.update { groups ->
            if (generation != currentGeneration()) return@update groups
            groups.map { group ->
                group.copy(
                    items = group.items.map { item ->
                        if (item.tag == outboundTag && item.icmp !is LatencyProbeState.Running) {
                            item.copy(icmp = state)
                        } else {
                            item
                        }
                    },
                )
            }
        }
    }

    internal fun publishSelection(generation: Long, groupTag: String, outboundTag: String) {
        mutable.update { groups ->
            if (generation != currentGeneration()) return@update groups
            groups.map { group ->
                if (group.tag == groupTag) group.copy(selected = outboundTag) else group
            }
        }
    }
}
