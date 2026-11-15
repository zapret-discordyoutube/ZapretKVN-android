package io.github.zapretkvn.android.slots

/** The slot layout of one session: the specs for the config builder and what each slot starts on. */
internal data class SlotPlan(
    val specs: List<SlotRouteSpec>,
    val assignments: List<SlotAssignment>,
    /** Servers of other profiles in the pool (their runtime tags are members of the specs). */
    val foreign: List<ForeignServer> = emptyList(),
    /** Servers a slot does not start on now because they failed lately (for the log line). */
    val skippedFailed: Int = 0,
)

/**
 * Turns slot definitions plus the profile's servers into the plan the session starts with. The `default` slot has no
 * selector of its own: it is the main selector, which keeps the server the user selected, and the other slots avoid
 * that server while the profile has more. A profile with one server (or none) gets no slots: nothing to separate.
 */
internal object SlotPlanner {
    fun plan(
        slots: List<SlotDefinition>,
        serverTags: List<String>,
        selectedTag: String?,
        installedPackages: Set<String> = emptySet(),
        previous: Map<String, String> = emptyMap(),
        unusable: Set<String> = emptySet(),
        alive: Set<String> = emptySet(),
    ): SlotPlan {
        val servers = serverTags.distinct()
        if (servers.size < 2) return SlotPlan(emptyList(), emptyList())
        val keep = previous.toMutableMap()
        selectedTag?.takeIf { it in servers }?.let { keep[SlotIds.DEFAULT] = it }
        // A server that just failed is not where a slot starts, a pin on it included (it stays a pool member and stays
        // pinned; the slot goes there again once the failure is old). The main server is never excluded.
        val avoid = unusable - setOfNotNull(selectedTag)
        val assignments = SlotServerAssigner.assign(slots, servers.map { SlotServer(it, usable = it !in avoid, alive = it in alive) }, keep)
        val specs = slots.filter { it.id != SlotIds.DEFAULT }.mapNotNull { slot ->
            val server = assignments.first { it.slotId == slot.id }.serverId ?: return@mapNotNull null
            val members = slot.pool?.let { pool -> servers.filter { it in pool } } ?: servers
            val packages = (slot.packages + installedPackages.filter { name -> slot.packagePrefixes.any(name::startsWith) }).sorted()
            SlotRouteSpec(
                slotId = slot.id,
                members = members,
                defaultMember = server,
                packages = packages,
                domainSuffixes = slot.domainSuffixes.sorted(),
                cidrs = slot.cidrs.sorted(),
            )
        }
        return SlotPlan(specs, assignments)
    }
}
