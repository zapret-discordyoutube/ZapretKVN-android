package io.github.zapretkvn.android.slots.youtube

import io.github.zapretkvn.android.config.ForcedProxyDomains
import io.github.zapretkvn.android.slots.SlotDefinition
import io.github.zapretkvn.android.slots.SlotIds

/**
 * Everything the YouTube slot knows about YouTube: the clients that belong to it and the names that always go through
 * the VPN (the same list as [ForcedProxyDomains]; one list, so the slot and the forced rule cannot drift apart).
 * The switching engine of this slot is [YoutubeSlotSwitchHost].
 */
internal object YoutubeSlot {
    val packages: Set<String> = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "app.revanced.android.youtube",
        "app.revanced.android.apps.youtube.music",
        "app.rvx.android.youtube",
        "app.rvx.android.apps.youtube.music",
        "com.vanced.android.youtube",
        "com.vanced.android.apps.youtube.music",
        "app.morphe.android.youtube",
        "app.morphe.androoid.youtube",
        "app.morphe.android.apps.youtube.music",
    )

    val domainSuffixes: Set<String> get() = ForcedProxyDomains.suffixes.toSet()

    val definition: SlotDefinition
        get() = SlotDefinition(SlotIds.YOUTUBE, "YouTube", packages = packages, domainSuffixes = domainSuffixes)
}
