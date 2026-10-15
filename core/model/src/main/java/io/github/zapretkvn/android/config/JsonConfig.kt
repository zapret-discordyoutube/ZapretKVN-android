package io.github.zapretkvn.android.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object JsonConfig {
    val compact = Json {
        explicitNulls = true
        isLenient = false
    }

    val pretty = Json(compact) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun parse(raw: String): JsonElement = compact.parseToJsonElement(raw)

    fun format(raw: String): String = format(parse(raw))

    fun format(element: JsonElement): String =
        pretty.encodeToString(JsonElement.serializer(), element) + "\n"
}
