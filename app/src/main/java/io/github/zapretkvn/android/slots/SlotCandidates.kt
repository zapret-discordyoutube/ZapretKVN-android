package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.engines.failover.FailoverTarget

/**
 * Candidates for moving one slot off its server (the YouTube episode uses it). The pool of the slot, in two soft steps:
 * servers that are dead and servers another slot (or the main selector) already uses are left out, but only while a
 * candidate remains; a small profile never ends up with no replacement just because every server is busy.
 */
internal object SlotCandidates {
    fun targets(
        members: List<String>,
        planTargets: List<FailoverTarget>?,
        currentId: String,
        usedByOthers: Set<String>,
        dead: Set<String>,
    ): List<FailoverTarget> {
        val known = planTargets.orEmpty().associateBy { it.id }
        val base = members.distinct().map { known[it] ?: FailoverTarget(it, valid = true, maintenance = false) }
        fun others(list: List<FailoverTarget>) = list.any { it.id != currentId && it.valid && !it.maintenance }
        val free = base.filter { it.id == currentId || (it.id !in dead && it.id !in usedByOthers) }
        if (others(free)) return free
        val alive = base.filter { it.id == currentId || it.id !in dead }
        return if (others(alive)) alive else base
    }
}
