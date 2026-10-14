package io.github.zapretkvn.android.ui

import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.core.uimodel.ScreenshotPrivacy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * JSON-половина ScreenshotPrivacy: нужна только редактору профиля и зависит от
 * config.JsonConfig, поэтому остаётся в :app. Строковая половина — в :core:ui-model.
 */

internal fun ScreenshotPrivacy.redactServerAddressesInJson(rawJson: String): String = runCatching {
    JsonConfig.format(redactElement(JsonConfig.parse(rawJson)))
}.getOrElse { MASK }

private fun redactElement(element: JsonElement, key: String? = null): JsonElement {
    if (key?.isServerAddressKey() == true) return redactValue(element)
    return when (element) {
        is JsonObject -> JsonObject(element.mapValues { (childKey, child) ->
            redactElement(child, childKey)
        })
        is JsonArray -> JsonArray(element.map { redactElement(it) })
        else -> element
    }
}

private fun redactValue(element: JsonElement): JsonElement = when (element) {
    is JsonPrimitive -> if (element.contentOrNull.isNullOrBlank()) element else JsonPrimitive(ScreenshotPrivacy.MASK)
    is JsonArray -> JsonArray(element.map(::redactValue))
    is JsonObject -> JsonObject(element.mapValues { (key, value) -> redactElement(value, key) })
}

private fun String.isServerAddressKey(): Boolean {
    val normalized = lowercase().replace('-', '_')
    return normalized in SERVER_ADDRESS_KEYS || normalized.endsWith("_url")
}

private val SERVER_ADDRESS_KEYS = setOf(
    "address",
    "endpoint",
    "host",
    "server",
    "server_address",
    "server_name",
    "uri",
    "url",
)
