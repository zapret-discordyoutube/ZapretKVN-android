package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.SlotState
import io.github.zapretkvn.android.network.probes.primaryGroup
import io.github.zapretkvn.core.uimodel.CountryGuess
import io.github.zapretkvn.core.uimodel.ScreenshotPrivacy
import io.github.zapretkvn.core.uimodel.ServerName
import io.github.zapretkvn.core.uimodel.inferCountries

/** One row of the "servers per service" card on Home (docs/SERVICE_SLOTS.md). */
data class HomeSlotUi(
    val id: String,
    val title: String,
    val serverTitle: String,
    val flag: String?,
    /** «Авто» or «Закреплён»; for the main server «Выбран вами». */
    val modeLabel: String,
    /** The pinned server, null while the slot chooses itself. */
    val pinnedTag: String? = null,
    /** What the slot sheet offers; empty for the main server (it is chosen on the server card). */
    val options: List<HomeSlotOption> = emptyList(),
)

/** One server in the slot sheet. */
data class HomeSlotOption(
    val tag: String,
    val title: String,
    val flag: String?,
    /** The slot leaves through this server now. */
    val current: Boolean,
    /** The user pinned this server. */
    val pinned: Boolean,
)

/**
 * Rows for the card: the slots of the session in their own order, then everything else (the main server).
 * Pure: HomeRoute only supplies the inputs. Empty when the session has no slots, so the card shows only the switch.
 */
fun slotCards(
    slots: List<SlotState>,
    groups: List<RuntimeSelectorGroup>,
    mainServer: HomeServerUi?,
    hide: Boolean,
    hostCountries: Map<String, String>,
    pins: Map<String, String> = emptyMap(),
): List<HomeSlotUi> {
    if (slots.isEmpty()) return emptyList()
    val servers = groups.primaryGroup()?.items.orEmpty()
    val hints = inferCountries(servers.map { it.tag to it.endpoint }.distinctBy { it.first }, hostCountries)
    fun label(tag: String): String {
        val masked = ScreenshotPrivacy.serverLabel(tag, hide)
        return if (masked != tag) masked else ServerName.parse(tag).title
    }
    fun flag(tag: String): String? = ServerName.parse(tag).flag ?: hints[tag]?.let(CountryGuess::flagOf)
    val rows = slots.map { slot ->
        // A pin on a server the profile no longer has counts as no pin: the sheet could not show it.
        val pinned = pins[slot.slotId]?.takeIf { pin -> servers.any { it.tag == pin } }
        HomeSlotUi(
            id = slot.slotId,
            title = slotTitle(slot.slotId, slot.title),
            serverTitle = label(slot.serverTag),
            flag = flag(slot.serverTag),
            modeLabel = if (pinned != null) "Закреплён" else "Авто",
            pinnedTag = pinned,
            options = servers.map { item ->
                HomeSlotOption(item.tag, label(item.tag), flag(item.tag), current = item.tag == slot.serverTag, pinned = item.tag == pinned)
            },
        )
    }
    val rest = mainServer?.let {
        HomeSlotUi(id = "default", title = slotTitle("default", ""), serverTitle = it.title, flag = it.flag, modeLabel = "Выбран вами")
    }
    return rows + listOfNotNull(rest)
}

/** Russian names of the built-in slots; a slot the user added keeps its own title. */
fun slotTitle(id: String, fallback: String): String = when (id) {
    "ai" -> "ИИ-приложения"
    "youtube" -> "YouTube"
    "telegram" -> "Telegram"
    "default" -> "Остальные приложения"
    else -> fallback.ifBlank { id }
}
