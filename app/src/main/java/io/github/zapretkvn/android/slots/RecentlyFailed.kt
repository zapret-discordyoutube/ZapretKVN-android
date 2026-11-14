package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.network.probes.ServerLatencyStore
import io.github.zapretkvn.android.network.probes.hint

/**
 * Runtime tags of the servers whose last saved result is a fresh failure («не отвечает», see `hint()`): [activeTags]
 * are the tags of the active profile, [foreign] the servers of the other profiles. Used when a slot starts and when it
 * looks for a replacement, so both ask the same question.
 */
internal fun ServerLatencyStore.recentlyFailedRuntimeTags(
    profileId: String,
    activeTags: Collection<String>,
    foreign: List<ForeignServer>,
): Set<String> {
    val own = forProfile(profileId)
    val failed = activeTags.filterTo(mutableSetOf()) { own[it]?.hint()?.failed == true }
    for (server in foreign) {
        if (forProfile(server.ref.profileId)[server.ref.tag]?.hint()?.failed == true) failed += server.runtimeTag
    }
    return failed
}
