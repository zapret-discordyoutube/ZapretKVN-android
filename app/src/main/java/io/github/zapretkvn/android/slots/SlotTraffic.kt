package io.github.zapretkvn.android.slots

import io.github.zapretkvn.runtimelog.FlowSnap

/**
 * How much of the tunnel's downlink belongs to the slots other than `default` (docs/SERVICE_SLOTS.md, phase 3b).
 *
 * The throughput monitor reads one global counter. With separate servers a slow Telegram transfer would look like a
 * slow main server and could start a switch of a server that is fine. The monitor therefore subtracts what the other
 * slots carried and judges the `default` slot (the main selector) on its own traffic. The flows come from the same
 * direct snapshot the video detector already uses (no new core calls, I6-05); each flow is assigned to a slot by the
 * rule the config uses ([ServiceSlotRouter]: package, then name, then address).
 *
 * The totals are approximations on purpose: the snapshot lists the heaviest 300 flows, and it is taken on its own
 * cadence, not together with the status counter. Both errors point the safe way. A slot byte not counted yet makes the
 * default rate look higher (no false "slow"); a slot byte counted early makes the adjusted counter step back, which the
 * detector treats as a reset.
 */
internal class SlotTraffic(private val router: ServiceSlotRouter) {
    private class Live(val slot: String, var downlink: Long, var seenAt: Long = 0L)

    private val live = HashMap<String, Live>()
    private val downlink = HashMap<String, Long>()
    private var sequence = 0L

    /** Bytes received so far by every slot other than `default`, as far as the snapshots showed. */
    @Synchronized
    fun downlinkOutsideDefault(): Long = downlink.filterKeys { it != SlotIds.DEFAULT }.values.sum()

    /** Flows of the other slots that are open in the last snapshot. */
    @Synchronized
    fun activeOutsideDefault(): Int = live.values.count { it.slot != SlotIds.DEFAULT && it.seenAt == sequence }

    @Synchronized
    fun downlinkOf(slotId: String): Long = downlink[slotId] ?: 0L

    @Synchronized
    fun onSnapshot(active: List<FlowSnap>, closed: List<FlowSnap>) {
        sequence++
        // Closed first: a flow that closed since the last snapshot reports its final total once.
        for (flow in closed) {
            val entry = live.remove(flow.id)
            account(entry?.slot ?: slotOf(flow), flow.downlink - (entry?.downlink ?: 0L))
        }
        for (flow in active) {
            val entry = live.getOrPut(flow.id) { Live(slotOf(flow), 0L) }
            account(entry.slot, flow.downlink - entry.downlink)
            entry.downlink = flow.downlink
            entry.seenAt = sequence
        }
        // A flow past the snapshot limit keeps its entry (so it is not counted twice when it comes back); one that
        // has not been seen for a while is gone without a closing record, and its bytes are counted up to then.
        live.values.removeAll { sequence - it.seenAt > FORGET_AFTER }
        if (live.size > MAX_TRACKED) live.clear()
    }

    private fun account(slotId: String, delta: Long) {
        if (delta > 0) downlink.merge(slotId, delta, Long::plus)
    }

    private fun slotOf(flow: FlowSnap): String =
        router.slotFor(
            flow.packageName.ifBlank { null },
            flow.domain.ifBlank { null },
            hostOf(flow.destination)?.takeIf { looksLikeAddress(it) },
        )

    companion object {
        /**
         * The `default` slot's own counters out of the tunnel's totals. [outside] is what the other slots carried
         * ([downlinkOutsideDefault] and [activeOutsideDefault]); null means no slots, the totals are returned as they are.
         */
        fun own(totalDownlink: Long, connections: Int, outside: Pair<Long, Int>?): Pair<Long, Int> =
            if (outside == null) totalDownlink to connections
            else (totalDownlink - outside.first) to (connections - outside.second).coerceAtLeast(0)

        private const val MAX_TRACKED = 4_096
        private const val FORGET_AFTER = 40

        /** Host part of `host:port` or `[v6]:port`; null for blank. */
        fun hostOf(destination: String): String? {
            val text = destination.trim()
            if (text.isEmpty()) return null
            if (text.startsWith("[")) return text.substringAfter('[').substringBefore(']').ifEmpty { null }
            return if (text.count { it == ':' } == 1) text.substringBefore(':').ifEmpty { null } else text
        }

        private fun looksLikeAddress(host: String): Boolean = host.contains(':') || host.all { it.isDigit() || it == '.' }
    }
}
