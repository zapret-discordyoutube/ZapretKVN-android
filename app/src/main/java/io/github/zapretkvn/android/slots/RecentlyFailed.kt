package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.network.probes.PersistedServerLatency
import io.github.zapretkvn.android.network.probes.hint

/**
 * What the app knows about a server before a slot trusts it: [failed] servers are not where a slot starts or lands
 * (a fresh «не отвечает» in the latency store, or a slot mark, see [SlotMarkRules]); [alive] servers answered a
 * relay test recently and win ties between servers that no other slot uses. Both are sets of runtime tags.
 */
internal data class SlotServerHealth(val failed: Set<String>, val alive: Set<String>) {
    companion object {
        val Unknown = SlotServerHealth(emptySet(), emptySet())

        /** A good relay result younger than this counts as proof of life. */
        const val ALIVE_TTL_MILLIS = 6 * 60 * 60 * 1_000L
    }
}

/**
 * Health of the slot servers; [latency] is the latency store's `forProfile`. [activeTags] are the tags of the active profile, [foreign] the servers of the other
 * profiles, [marks] the slot marks (`ServerRef.encode()` to time). A slot mark is overruled by a good relay result
 * that is newer than the mark: the server proved itself since.
 */
internal fun slotServerHealth(
    latency: (profileId: String) -> Map<String, PersistedServerLatency>,
    marks: Map<String, Long>,
    profileId: String,
    activeTags: Collection<String>,
    foreign: List<ForeignServer>,
    now: Long = System.currentTimeMillis(),
): SlotServerHealth {
    val failed = mutableSetOf<String>()
    val alive = mutableSetOf<String>()
    fun judge(runtimeTag: String, ref: ServerRef, saved: PersistedServerLatency?) {
        val relay = saved?.relay
        val provedSince = relay != null && !relay.failed && relay.measuredAtEpochMillis > (marks[ref.encode()] ?: Long.MAX_VALUE)
        val good = relay != null && !relay.failed && now - relay.measuredAtEpochMillis <= SlotServerHealth.ALIVE_TTL_MILLIS
        if (good) alive += runtimeTag
        val markedForSlots = marks.containsKey(ref.encode()) && !provedSince
        if (saved?.hint(now)?.failed == true || markedForSlots) failed += runtimeTag
    }
    val own = latency(profileId)
    activeTags.forEach { judge(it, ServerRef(profileId, it), own[it]) }
    for (server in foreign) judge(server.runtimeTag, server.ref, latency(server.ref.profileId)[server.ref.tag])
    return SlotServerHealth(failed, alive)
}
