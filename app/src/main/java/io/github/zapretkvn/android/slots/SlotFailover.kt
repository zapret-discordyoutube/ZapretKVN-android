package io.github.zapretkvn.android.slots

/** One slot leaving a failed server for another one (docs/SERVICE_SLOTS.md, phase 3). */
internal data class SlotMove(val slotId: String, val from: String, val to: String)

internal sealed interface SlotFailoverDecision {
    /** Nothing to do; [reason] is for the log and the tests. */
    data class Ignore(val reason: String) : SlotFailoverDecision

    /** [failedServer] stopped answering for the slots in [moves]; only those slots leave it. */
    data class Move(val failedServer: String, val hints: Int, val moves: List<SlotMove>) : SlotFailoverDecision
}

/**
 * "On failure only" switching for the service slots (owner decision 2026-11-14, first step).
 *
 * The core log reports a failed connection of an outbound as a hint. For the main selector the supervisor confirms a
 * hint with a probe through the tunnel; that probe always leaves through the main server, so it cannot tell whether a
 * slot's own server is dead. A slot server is therefore judged by repetition: [HINTS_TO_MOVE] switchable failures of
 * the same server within [WINDOW_MILLIS] (one timeout is normal noise, the same as for the main selector). The
 * numbers are a first guess, to be tuned on the phone; every decision is logged.
 *
 * On a decision the server is marked dead for [DEAD_MILLIS] and [SlotServerAssigner] is asked again with it unusable:
 * only the slots that sit on it move, the other slots keep their servers. The `default` slot belongs to the main
 * selector and the main supervisor; it never moves here, it only tells the assigner which server to stay away from.
 * Moves are rate limited ([MAX_MOVES] per [RATE_WINDOW_MILLIS]) so a network-wide outage cannot make the slots hop
 * between servers.
 *
 * Not thread safe: owned by the runtime actor, like `PathSupervisor`.
 */
internal class SlotFailoverPolicy(
    slots: List<SlotDefinition>,
    private val servers: List<String>,
    initial: Map<String, String>,
    private val clock: () -> Long,
) {
    private var slots: List<SlotDefinition> = slots
    private val current = initial.toMutableMap()
    private val hintTimes = mutableMapOf<String, ArrayDeque<Long>>()
    private val deadUntil = mutableMapOf<String, Long>()
    private val leftAt = mutableMapOf<String, Long>()
    private val moveTimes = ArrayDeque<Long>()
    private val dnsHintAt = mutableMapOf<String, Long>()
    private val movedAt = mutableMapOf<String, Long>()

    /** Server of every slot as the policy believes it is (the `default` slot included when it was told). */
    fun assignments(): Map<String, String> = current.toMap()

    /** Servers some slot other than `default` uses now: their names must resolve on their own. */
    fun slotServers(): Set<String> = current.filterKeys { it != SlotIds.DEFAULT }.values.toSet()

    fun deadServers(): Set<String> {
        val now = clock()
        deadUntil.entries.removeAll { it.value <= now }
        return deadUntil.keys.toSet()
    }

    /**
     * A switchable failure of [serverId] was seen in the core log. [mainServer] is the server of the main selector
     * now, [mainDead] the servers the main supervisor confirmed dead. [immediate] is for a failure that repetition
     * cannot make more certain (the server rejects our credentials or certificate): the slot leaves at the first hint.
     */
    fun onFailureHint(
        serverId: String,
        mainServer: String?,
        mainDead: Set<String> = emptySet(),
        immediate: Boolean = false,
    ): SlotFailoverDecision {
        val affected = slots.filter { it.id != SlotIds.DEFAULT && current[it.id] == serverId }
        if (affected.isEmpty()) return SlotFailoverDecision.Ignore("no slot uses $serverId")
        val now = clock()
        // No "now - Long.MIN_VALUE" here: that overflows and would make the first hint look stale.
        if (leftAt[serverId]?.let { now - it < STALE_LINE_MILLIS } == true) {
            return SlotFailoverDecision.Ignore("stale line after a move")
        }
        val times = hintTimes.getOrPut(serverId) { ArrayDeque() }
        times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > WINDOW_MILLIS) times.removeFirst()
        if (!immediate && times.size < HINTS_TO_MOVE) {
            return SlotFailoverDecision.Ignore("${times.size} of $HINTS_TO_MOVE hints")
        }
        while (moveTimes.isNotEmpty() && now - moveTimes.first() >= RATE_WINDOW_MILLIS) moveTimes.removeFirst()
        if (moveTimes.size >= MAX_MOVES) return SlotFailoverDecision.Ignore("move rate limit")

        val hints = times.size
        deadUntil.entries.removeAll { it.value <= now }
        // The failing server is avoided for this search, but it is marked dead only when some slot can leave it:
        // with no replacement the mark would only hide the server from the YouTube candidates for nothing.
        val avoid = deadUntil.keys + serverId + mainDead
        val previous = current.toMutableMap()
        mainServer?.let { previous[SlotIds.DEFAULT] = it }
        val assignments = SlotServerAssigner.assign(slots, servers.map { SlotServer(it, usable = it !in avoid) }, previous)
        val moves = assignments.mapNotNull { choice ->
            val from = current[choice.slotId]
            val to = choice.serverId
            if (choice.slotId == SlotIds.DEFAULT || from != serverId || to == null || to == from) null
            else SlotMove(choice.slotId, from, to)
        }
        if (moves.isEmpty()) return SlotFailoverDecision.Ignore("no usable replacement for $serverId")
        times.clear()
        deadUntil[serverId] = now + DEAD_MILLIS
        moves.forEach {
            current[it.slotId] = it.to
            movedAt[it.slotId] = now
        }
        leftAt[serverId] = now
        moveTimes.addLast(now)
        return SlotFailoverDecision.Move(serverId, hints, moves)
    }

    /**
     * The slot's own DNS ([slotId]) timed out. A dead slot server shows up here first and often alone: the Hysteria
     * client retries the handshake quietly, so the log has no repeated connect error to count. The timeout is judged
     * like any other hint for the server the slot sits on, but at most one hint per [DNS_HINT_GAP_MILLIS] per slot, so
     * one burst of queued lookups is one failure and a move needs about [HINTS_TO_MOVE] separate failures.
     */
    fun onSlotDnsFailure(slotId: String, mainServer: String?, mainDead: Set<String> = emptySet()): SlotFailoverDecision {
        val server = current[slotId] ?: return SlotFailoverDecision.Ignore("unknown slot $slotId")
        if (slotId == SlotIds.DEFAULT) return SlotFailoverDecision.Ignore("the default slot belongs to the main selector")
        val now = clock()
        // Lookups started before the slot moved still time out up to the resolver deadline later: they describe the
        // server the slot left, not the new one (field case 2026-11-14: a healthy server was blamed 11 s after a move).
        if (movedAt[slotId]?.let { now - it < DNS_STALE_MILLIS } == true) {
            return SlotFailoverDecision.Ignore("dns lookups started before the move")
        }
        val last = dnsHintAt[slotId]
        if (last != null && now - last < DNS_HINT_GAP_MILLIS) return SlotFailoverDecision.Ignore("same dns burst")
        dnsHintAt[slotId] = now
        return onFailureHint(server, mainServer, mainDead)
    }

    /**
     * The user's pins changed ([pins]: slot id to server tag; slots without an entry are automatic). Later failover
     * decisions respect them. Returns the moves needed now: a pinned slot that is not on its server yet goes there,
     * also when the server was marked dead (an explicit choice beats the app's guess). A pin outside the slot's pool
     * is ignored. A slot that was unpinned stays where it is; automatic choice takes over from there.
     */
    fun setPins(pins: Map<String, String>, poolOf: (String) -> List<String>): List<SlotMove> {
        slots = slots.map { slot -> slot.copy(pinned = SlotPins.forSlots(pins)[slot.id]) }
        val moves = slots.mapNotNull { slot ->
            val pin = slot.pinned ?: return@mapNotNull null
            val from = current[slot.id] ?: return@mapNotNull null
            if (pin == from || pin !in poolOf(slot.id) || pin !in servers) return@mapNotNull null
            SlotMove(slot.id, from, pin)
        }
        moves.forEach { place(it.slotId, it.to) }
        return moves
    }

    /** Server of one slot now, or null when the policy does not know the slot. */
    fun serverOf(slotId: String): String? = current[slotId]

    /**
     * Another part of the app (the YouTube engine) moved [slotId] to [serverId]: remember it, and forget hints about
     * the server it left (they describe a connection that is gone).
     */
    fun place(slotId: String, serverId: String) {
        val left = current[slotId]
        current[slotId] = serverId
        movedAt[slotId] = clock()
        // Hints describe connections of a server; another slot may still sit on it, and its count must survive.
        if (left != null && left != serverId && current.none { it.key != SlotIds.DEFAULT && it.value == left }) {
            hintTimes.remove(left)
        }
    }

    /**
     * The core refused the command: the slot is still where it was. A late failure of an older command must not undo
     * a newer move of the same slot, so the slot goes back only while it still sits where this command sent it.
     */
    fun moveFailed(move: SlotMove) {
        if (current[move.slotId] == move.to) current[move.slotId] = move.from
    }

    companion object {
        const val HINTS_TO_MOVE = 3
        const val WINDOW_MILLIS = 60_000L
        const val STALE_LINE_MILLIS = 2_000L
        const val DNS_HINT_GAP_MILLIS = 5_000L

        /** The resolver gives up after 10 s; a little more, then DNS failures belong to the new server. */
        const val DNS_STALE_MILLIS = 12_000L
        const val DEAD_MILLIS = 5 * 60_000L
        const val MAX_MOVES = 3
        const val RATE_WINDOW_MILLIS = 15 * 60_000L
    }
}
