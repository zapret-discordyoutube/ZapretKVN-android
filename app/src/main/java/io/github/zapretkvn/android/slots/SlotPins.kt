package io.github.zapretkvn.android.slots

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Servers the user pinned to slots (slot id to server tag), stored as one JSON object in the settings. Server tags
 * hold emoji, spaces and punctuation, so a delimiter format would break; JSON does not. Anything unreadable is "no pins".
 */
internal object SlotPins {
    fun encode(pins: Map<String, String>): String =
        buildJsonObject { pins.toSortedMap().forEach { (slot, tag) -> put(slot, JsonPrimitive(tag)) } }.toString()

    fun decode(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        return root.mapNotNull { (slot, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { slot to it }
        }.toMap()
    }

    /** The slots a pin may apply to: the `default` slot is the main selector, chosen on the server card. */
    fun forSlots(pins: Map<String, String>): Map<String, String> = pins.filterKeys { it != SlotIds.DEFAULT }

    /** [pins] with the pin of [slotId] set to [tag], or removed when [tag] is null. */
    fun with(pins: Map<String, String>, slotId: String, tag: String?): Map<String, String> =
        if (slotId == SlotIds.DEFAULT) pins else if (tag == null) pins - slotId else pins + (slotId to tag)
}
