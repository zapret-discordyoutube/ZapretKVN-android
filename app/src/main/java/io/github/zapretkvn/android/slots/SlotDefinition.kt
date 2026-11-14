package io.github.zapretkvn.android.slots

/**
 * One slot: what it matches and which servers it may use.
 *
 * [pool] holds server ids of the active profile; null means every server of the profile. [pinned] overrides the
 * automatic choice while that server is usable.
 */
internal data class SlotDefinition(
    val id: String,
    val title: String,
    val packages: Set<String> = emptySet(),
    val packagePrefixes: Set<String> = emptySet(),
    val domainSuffixes: Set<String> = emptySet(),
    val cidrs: Set<String> = emptySet(),
    val pool: List<String>? = null,
    val pinned: String? = null,
)
