package io.github.zapretkvn.android.ui

import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Presentation-only masking for values that must not appear in screenshots by default. */
internal object ScreenshotPrivacy {
    const val MASK = "********"

    fun serverEndpoint(value: String?, hidden: Boolean): String? = when {
        value.isNullOrBlank() -> value
        hidden -> MASK
        else -> value
    }

    fun serverLabel(value: String, hidden: Boolean): String =
        if (hidden && value.containsNetworkAddress()) MASK else value

    fun subscriptionSource(value: String, hidden: Boolean): String =
        if (hidden) MASK else value

    fun redactServerAddressesInJson(rawJson: String): String = runCatching {
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
        is JsonPrimitive -> if (element.contentOrNull.isNullOrBlank()) element else JsonPrimitive(MASK)
        is JsonArray -> JsonArray(element.map(::redactValue))
        is JsonObject -> JsonObject(element.mapValues { (key, value) -> redactElement(value, key) })
    }

    private fun String.isServerAddressKey(): Boolean {
        val normalized = lowercase().replace('-', '_')
        return normalized in SERVER_ADDRESS_KEYS || normalized.endsWith("_url")
    }

    private fun String.containsNetworkAddress(): Boolean =
        NETWORK_ADDRESS.containsMatchIn(this) ||
            split(' ', '·', '/', '[', ']').any { token ->
                token.count { it == ':' } >= 2 &&
                    token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' }
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
    private val NETWORK_ADDRESS = Regex(
        "(?i)(?:[a-z][a-z0-9+.-]*://\\S+|" +
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b|" +
            "\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d{1,5})?\\b)",
    )
}
