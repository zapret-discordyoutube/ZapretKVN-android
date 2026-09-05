package io.github.zapretkvn.wireguardimport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireGuardGoldenTest {
    @Test
    fun `shared vectors preserve every transport field or reject explicitly`() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/wg_awg_golden.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        for (entry in fixture.getValue("vectors").jsonArray) {
            val vector = entry.jsonObject
            val name = vector.getValue("id").jsonPrimitive.content
            val conf = vector.getValue("conf").jsonPrimitive.content
            if (!vector.getValue("valid").jsonPrimitive.boolean) {
                assertThrows(name, WireGuardImportException::class.java) { WireGuardConfigParser.parse(conf) }
                continue
            }
            val profile = Json.parseToJsonElement(WireGuardConfigParser.parse(conf).json).jsonObject
            val endpoint = profile.getValue("endpoints").jsonArray.single().jsonObject
            assertEquals(name, canonicalIPs(vector.getValue("endpoint")), JsonObject(endpoint.filterKeys { it != "tag" }))
        }
    }

    // Java renders IPv6 without :: compression; compare address values,
    // not that platform-specific spelling. No parameter is removed.
    private fun canonicalIPs(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { canonicalIPs(it.value) })
        is JsonArray -> JsonArray(value.map(::canonicalIPs))
        is JsonPrimitive -> if (value.isString && ':' in value.content) {
            val parts = value.content.split('/', limit = 2)
            JsonPrimitive(checkNotNull(InetAddress.getByName(parts[0]).hostAddress) + parts.drop(1).joinToString("", prefix = if (parts.size == 2) "/" else ""))
        } else value
    }
}
