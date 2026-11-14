package io.github.zapretkvn.android.ui.services

import io.github.zapretkvn.android.profiles.MANAGED_SELECTOR_TAG_NAME
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileServerSummary
import io.github.zapretkvn.android.slots.SLOT_SERVER_TYPES
import io.github.zapretkvn.android.slots.ServerRef
import io.github.zapretkvn.android.vpn.SlotState
import io.github.zapretkvn.core.uimodel.ScreenshotPrivacy
import io.github.zapretkvn.core.uimodel.ServerName
import io.github.zapretkvn.core.uimodel.protocolLabel

/** One server the user may pin a service to. [ref] is the stored form of the pin (`<profile id>|<tag>`). */
data class ServiceServerUi(
    val ref: String,
    val title: String,
    val flag: String?,
    val protocol: String,
    /** The service leaves through this server now. */
    val current: Boolean,
    /** The user pinned this server. */
    val pinned: Boolean,
)

/** The servers of one profile, in the order the profile lists them. */
data class ServiceProfileUi(
    val profileId: String,
    val name: String,
    val active: Boolean,
    val servers: List<ServiceServerUi>,
)

/** One row of the «Сервисы» tab. */
data class ServiceUi(
    val id: String,
    val title: String,
    /** Where the service leaves now; null while the VPN is off. */
    val serverTitle: String?,
    val flag: String?,
    /** The profile of the current server when it is not the active one. */
    val profileName: String?,
    /** «Авто», «Закреплён», or the line saying the pinned server is not the one in use. */
    val modeLabel: String,
    /** Stored form of the pin, null while the service chooses itself. */
    val pinnedRef: String?,
    val profiles: List<ServiceProfileUi>,
)

data class ServicesUi(
    /** The beta switch «Раздельные серверы» is on. */
    val enabled: Boolean,
    val connected: Boolean,
    val services: List<ServiceUi>,
    /** The main server's title for «Остальные приложения» (chosen on the «Серверы» tab); null while the VPN is off. */
    val mainServerTitle: String?,
)

/** The services the tab lists, in this order; the titles are the ones the user knows. */
private val SERVICE_ORDER = listOf("youtube" to "YouTube", "telegram" to "Telegram", "ai" to "ИИ-приложения")

/**
 * What the «Сервисы» tab shows. Pure: the route only supplies state. The servers to choose from are the plain servers of
 * every profile's managed group (the same rule as the pool the session builds, [SLOT_SERVER_TYPES]), the active profile
 * first. Pins are stored as `<profile id>|<tag>`; an older value without the profile id means the active profile.
 */
fun buildServicesUi(
    enabled: Boolean,
    connected: Boolean,
    slots: List<SlotState>,
    profiles: List<ProfileMetadata>,
    summaries: Map<String, ProfileServerSummary>,
    activeProfileId: String?,
    pins: Map<String, String>,
    hide: Boolean,
    mainServerTitle: String?,
): ServicesUi {
    fun normalized(pin: String): String? = ServerRef.parse(pin)?.encode() ?: activeProfileId?.let { ServerRef(it, pin).encode() }
    val orderedProfiles = profiles.sortedByDescending { it.id == activeProfileId }

    fun serverUi(profileId: String, tag: String, type: String, current: String?, pinned: String?): ServiceServerUi {
        val ref = ServerRef(profileId, tag).encode()
        val parsed = ServerName.parse(tag)
        val masked = ScreenshotPrivacy.serverLabel(tag, hide)
        return ServiceServerUi(
            ref = ref,
            title = if (masked != tag) masked else parsed.title,
            flag = parsed.flag,
            protocol = protocolLabel(type),
            current = ref == current,
            pinned = ref == pinned,
        )
    }

    val services = SERVICE_ORDER.map { (id, title) ->
        val slot = slots.firstOrNull { it.slotId == id }
        val pinned = pins[id]?.let(::normalized)
        val groups = orderedProfiles.mapNotNull { profile ->
            val group = summaries[profile.id]?.groups?.firstOrNull { it.tag == MANAGED_SELECTOR_TAG_NAME } ?: return@mapNotNull null
            val servers = group.options.filter { it.type.lowercase() in SLOT_SERVER_TYPES }
                .map { serverUi(profile.id, it.tag, it.type, slot?.serverRef, pinned) }
            if (servers.isEmpty()) null else ServiceProfileUi(profile.id, profile.name, profile.id == activeProfileId, servers)
        }
        val known = groups.any { profile -> profile.servers.any { it.ref == pinned } }
        val shownPin = pinned?.takeIf { known }
        val parsed = slot?.let { ServerName.parse(it.serverName) }
        ServiceUi(
            id = id,
            title = title,
            serverTitle = slot?.let {
                val masked = ScreenshotPrivacy.serverLabel(it.serverName, hide)
                if (masked != it.serverName) masked else parsed?.title
            },
            flag = parsed?.flag,
            profileName = slot?.profileName,
            modeLabel = when {
                shownPin == null -> "Авто"
                slot == null || slot.serverRef == shownPin -> "Закреплён"
                else -> "Закреплённый сервер не отвечал"
            },
            pinnedRef = shownPin,
            profiles = groups,
        )
    }
    return ServicesUi(enabled, connected, services, mainServerTitle)
}

/** The profiles with only the servers whose name contains [query] (case-insensitive); profiles left empty are dropped. */
fun filterServiceServers(profiles: List<ServiceProfileUi>, query: String): List<ServiceProfileUi> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return profiles
    return profiles.mapNotNull { profile ->
        val matching = profile.servers.filter { it.title.lowercase().contains(needle) }
        if (matching.isEmpty()) null else profile.copy(servers = matching)
    }
}
