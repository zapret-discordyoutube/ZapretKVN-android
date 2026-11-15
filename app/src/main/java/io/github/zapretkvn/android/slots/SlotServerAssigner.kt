package io.github.zapretkvn.android.slots

/** A server of the active profile as the assigner sees it. */
internal data class SlotServer(
    val id: String,
    val usable: Boolean = true,
    val alive: Boolean = false,
    /** Slots this server has proven itself for (the YouTube slot: it delivered video fast before). */
    val provenFor: Set<String> = emptySet(),
)

internal enum class SlotChoice {
    /** The pinned server is usable and used. */
    Pinned,

    /** The slot keeps the server it already had. */
    Kept,

    /** The app chose a server, preferring one no other slot uses. */
    Chosen,

    /** The pinned server is not usable, so the app chose another one until it is back. */
    PinUnavailable,

    /** No usable server in the pool. */
    None,
}

internal data class SlotAssignment(val slotId: String, val serverId: String?, val choice: SlotChoice)

/**
 * Picks a server for every slot: pin first, then the server the slot already has, then the usable server the fewest
 * other slots use (with a small pool servers are shared evenly rather than a slot left without one); between equally
 * used servers one with recent proof of life ([SlotServer.alive]) goes first, then the profile's order, so the result is
 * deterministic. Proof of life never beats separation: a slot does not share a server just because it is known good. Failure handling is the same call with the failed server
 * marked unusable: the slot moves, other slots keep what they have.
 */
internal object SlotServerAssigner {
    fun assign(
        slots: List<SlotDefinition>,
        servers: List<SlotServer>,
        previous: Map<String, String> = emptyMap(),
    ): List<SlotAssignment> {
        val usable = servers.filter { it.usable }.map { it.id }.toSet()
        val aliveIds = servers.filter { it.alive }.map { it.id }.toSet()
        val provenFor = servers.filter { it.provenFor.isNotEmpty() }.associate { it.id to it.provenFor }
        val order = servers.map { it.id }
        val taken = mutableMapOf<String, Int>()
        val result = mutableListOf<SlotAssignment>()
        // Slots that keep their server are settled first, so a slot that must move does not take their server.
        val settled = mutableMapOf<String, SlotAssignment>()
        for (slot in slots) {
            val pool = poolOf(slot, order).filter { it in usable }
            val pin = slot.pinned
            when {
                pin != null && pin in pool -> settled[slot.id] = SlotAssignment(slot.id, pin, SlotChoice.Pinned)
                pin == null && previous[slot.id]?.let { it in pool } == true ->
                    settled[slot.id] = SlotAssignment(slot.id, previous.getValue(slot.id), SlotChoice.Kept)
            }
        }
        settled.values.forEach { it.serverId?.let { id -> taken.merge(id, 1, Int::plus) } }
        for (slot in slots) {
            val ready = settled[slot.id]
            if (ready != null) {
                result += ready
                continue
            }
            val pool = poolOf(slot, order).filter { it in usable }
            // Least used first, then proven alive, then profile order: servers are shared evenly when the pool is small.
            // A server proven for this slot goes before a merely alive one; a server proven only for ANOTHER slot is left
            // to that slot when an equal server exists (the YouTube slot does not lose its proven server to the AI slot).
            val pick = pool.withIndex().minWithOrNull(
                compareBy(
                    { taken[it.value] ?: 0 },
                    { if (slot.id in provenFor[it.value].orEmpty()) 0 else 1 },
                    { if (it.value in aliveIds) 0 else 1 },
                    { if (provenFor[it.value].orEmpty().let { proven -> proven.isNotEmpty() && slot.id !in proven }) 1 else 0 },
                    { it.index },
                ),
            )?.value
            if (pick == null) {
                result += SlotAssignment(slot.id, null, SlotChoice.None)
                continue
            }
            taken.merge(pick, 1, Int::plus)
            val choice = if (slot.pinned != null) SlotChoice.PinUnavailable else SlotChoice.Chosen
            result += SlotAssignment(slot.id, pick, choice)
        }
        return result
    }

    private fun poolOf(slot: SlotDefinition, order: List<String>): List<String> {
        val pool = slot.pool ?: return order
        return order.filter { it in pool }
    }
}
