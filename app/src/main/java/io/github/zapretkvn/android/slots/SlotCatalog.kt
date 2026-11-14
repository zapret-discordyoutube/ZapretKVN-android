package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.slots.ai.AiSlot
import io.github.zapretkvn.android.slots.telegram.TelegramSlot
import io.github.zapretkvn.android.slots.youtube.YoutubeSlot

/** The slots that ship with the app; each service keeps its own data in its own folder. */
internal object SlotCatalog {
    /** [builtIn] with the user's pins applied; the `default` slot is the main selector and is never pinned here. */
    fun withPins(pins: Map<String, String>): List<SlotDefinition> =
        builtIn().map { slot -> slot.copy(pinned = SlotPins.forSlots(pins)[slot.id]) }

    /** Built-in slots in match order; `default` is last and matches whatever is left. */
    fun builtIn(): List<SlotDefinition> = listOf(
        AiSlot.definition,
        YoutubeSlot.definition,
        TelegramSlot.definition,
        SlotDefinition(SlotIds.DEFAULT, "Everything else"),
    )
}
