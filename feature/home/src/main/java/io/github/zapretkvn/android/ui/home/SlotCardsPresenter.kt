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
    /** «Авто» for now: pinning a server arrives with the slot sheet. */
    val modeLabel: String,
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
): List<HomeSlotUi> {
    if (slots.isEmpty()) return emptyList()
    val servers = groups.primaryGroup()?.items.orEmpty()
    val hints = inferCountries(servers.map { it.tag to it.endpoint }.distinctBy { it.first }, hostCountries)
    val rows = slots.map { slot ->
        val name = ServerName.parse(slot.serverTag)
        val masked = ScreenshotPrivacy.serverLabel(slot.serverTag, hide)
        HomeSlotUi(
            id = slot.slotId,
            title = slotTitle(slot.slotId, slot.title),
            serverTitle = if (masked != slot.serverTag) masked else name.title,
            flag = name.flag ?: hints[slot.serverTag]?.let(CountryGuess::flagOf),
            modeLabel = "Авто",
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
