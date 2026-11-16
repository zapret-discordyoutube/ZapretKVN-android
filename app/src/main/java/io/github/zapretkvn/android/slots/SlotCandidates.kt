package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.engines.failover.FailoverTarget

/**
 * Candidates for moving one slot off its server (the YouTube episode uses it). The pool of the slot, in two soft steps:
 * servers that are dead and servers another slot (or the main selector) already uses are left out, but only while a
 * candidate remains; a small profile never ends up with no replacement just because every server is busy. Before those,
 * sibling entries of the current machine and of the busy machines are left out too ([machines], as SlotServerAssigner):
 * the same machine leaves from the same address, so its video cluster and its speed are the same.
 */
internal object SlotCandidates {
    fun targets(
        members: List<String>,
        planTargets: List<FailoverTarget>?,
        currentId: String,
        usedByOthers: Set<String>,
        dead: Set<String>,
        machines: Map<String, Set<String>> = emptyMap(),
    ): List<FailoverTarget> {
        val known = planTargets.orEmpty().associateBy { it.id }
        val base = members.distinct().map { known[it] ?: FailoverTarget(it, valid = true, maintenance = false) }
        fun others(list: List<FailoverTarget>) = list.any { it.id != currentId && it.valid && !it.maintenance }
        val free = base.filter { it.id == currentId || (it.id !in dead && it.id !in usedByOthers) }
        val busyKeys = (usedByOthers + currentId).flatMap { machines[it].orEmpty() }.toSet()
        val apart = free.filter { it.id == currentId || machines[it.id].orEmpty().none(busyKeys::contains) }
        if (others(apart)) return apart
        if (others(free)) return free
        val alive = base.filter { it.id == currentId || it.id !in dead }
        return if (others(alive)) alive else base
    }
}
