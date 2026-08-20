package io.github.zapretkvn.android.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Эталонные ссылки `happ://crypt*`, общие с десктопным клиентом. */
internal object HappVectors {

    fun all(): List<JsonObject> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/happ_vectors.json")) {
            "happ_vectors.json missing from test resources"
        }
        val parsed = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() })
        return (parsed as JsonArray).filterIsInstance<JsonObject>()
    }

    fun link(name: String): String = checkNotNull(
        all().firstOrNull { it.string("name") == name }?.string("link"),
    ) { "Vector $name not found" }

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
