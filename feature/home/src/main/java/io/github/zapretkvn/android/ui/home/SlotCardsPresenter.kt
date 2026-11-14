package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.vpn.SlotState
import io.github.zapretkvn.core.uimodel.ScreenshotPrivacy
import io.github.zapretkvn.core.uimodel.ServerName

/**
 * One line of the "servers per service" summary on Home (docs/SERVICE_SLOTS.md). The choice itself lives on the
 * «Сервисы» tab (owner decision 2026-11-14); Home only shows where each service leaves now.
 */
data class HomeSlotUi(
    val id: String,
    val title: String,
    val serverTitle: String,
    val flag: String?,
    /** The profile of the server when it is not the active one. */
    val profileName: String? = null,
)

/**
 * Lines for the summary: the slots of the session in their own order, then everything else (the main server). Pure:
 * HomeRoute only supplies the inputs. Empty when the session has no slots, so the card shows only the switch.
 */
fun slotCards(slots: List<SlotState>, mainServer: HomeServerUi?, hide: Boolean): List<HomeSlotUi> {
    if (slots.isEmpty()) return emptyList()
    val rows = slots.map { slot ->
        val parsed = ServerName.parse(slot.serverName)
        val masked = ScreenshotPrivacy.serverLabel(slot.serverName, hide)
        HomeSlotUi(
            id = slot.slotId,
            title = slotTitle(slot.slotId, slot.title),
            serverTitle = if (masked != slot.serverName) masked else parsed.title,
            flag = parsed.flag,
            profileName = slot.profileName,
        )
    }
    val rest = mainServer?.let {
        HomeSlotUi(id = "default", title = slotTitle("default", ""), serverTitle = it.title, flag = it.flag)
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
