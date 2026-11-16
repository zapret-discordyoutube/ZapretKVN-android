package io.github.zapretkvn.android.slots

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotMachinesTest {
    private fun tree(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `names, addresses and remembered answers make the machine keys`() {
        val root = tree(
            """{"outbounds":[{"tag":"a","type":"vless","server":"A.example."},{"tag":"b","type":"hysteria2","server":"203.0.113.7"},
               {"tag":"proxy","type":"selector","outbounds":["a","b"]}],
               "endpoints":[{"tag":"w","type":"wireguard","peers":[{"address":"wg.example"}]}]}""",
        )
        val remembered = mapOf("a.example" to setOf("203.0.113.7"))
        val machines = SlotMachines.of(root, emptyList()) { remembered[it].orEmpty() }
        assertEquals(setOf("a.example", "203.0.113.7"), machines["a"])
        assertEquals(setOf("203.0.113.7"), machines["b"])
        assertEquals(setOf("wg.example"), machines["w"])
        assertTrue(machines["a"].orEmpty().any(machines["b"].orEmpty()::contains))
        assertEquals(null, machines["proxy"])
    }
}
