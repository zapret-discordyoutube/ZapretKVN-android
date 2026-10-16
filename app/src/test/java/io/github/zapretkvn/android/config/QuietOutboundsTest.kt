package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietOutboundsTest {
    private val raw = """
        {
          "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
          "outbounds":[
            {"type":"urltest","tag":"auto","outbounds":["a","b"],"url":"https://www.gstatic.com/generate_204",
             "interval":"3m","tolerance":50,"interrupt_exist_connections":true},
            {"type":"trojan","tag":"a","server":"a.example","server_port":443,"password":"p",
             "tls":{"enabled":true},"multiplex":{"enabled":true,"protocol":"h2mux"}},
            {"type":"trojan","tag":"b","server":"b.example","server_port":443,"password":"p","tls":{"enabled":true}},
            {"type":"direct","tag":"direct"}
          ],
          "route":{"auto_detect_interface":true,"final":"auto"}
        }
    """.trimIndent()

    private fun JsonArray.byTag(tag: String): JsonObject =
        map { it as JsonObject }.first { (it["tag"] as JsonPrimitive).content == tag }

    @Test
    fun `urltest becomes selector with the same members and first default`() {
        val result = QuietOutbounds.applyToJson(raw)
        val outbounds = (JsonConfig.parse(result.json) as JsonObject)["outbounds"] as JsonArray
        val auto = outbounds.byTag("auto")

        assertEquals("selector", (auto["type"] as JsonPrimitive).content)
        assertEquals(listOf("a", "b"), (auto["outbounds"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals("a", (auto["default"] as JsonPrimitive).content)
        assertTrue((auto["interrupt_exist_connections"] as JsonPrimitive).content.toBoolean())
        for (field in listOf("url", "interval", "tolerance")) assertFalse(field in auto)
        assertFalse("multiplex" in outbounds.byTag("a"))
        assertEquals(listOf(QuietOutbounds.URLTEST_NOTE, QuietOutbounds.MULTIPLEX_NOTE), result.notes)
    }

    @Test
    fun `quiet json is returned unchanged`() {
        val quiet = """{"outbounds":[{"type":"selector","tag":"s","outbounds":["d"]},{"type":"direct","tag":"d"}]}"""
        val result = QuietOutbounds.applyToJson(quiet)
        assertSame(quiet, result.json)
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun `runtime turns a stored urltest profile quiet`() {
        val ready = RuntimeConfigBuilder.build(raw)
        check(ready is RuntimeConfigResult.Ready) { "runtime rejected: $ready" }
        val outbounds = (JsonConfig.parse(ready.json) as JsonObject)["outbounds"] as JsonArray

        assertEquals("selector", (outbounds.byTag("auto")["type"] as JsonPrimitive).content)
        assertFalse("multiplex" in outbounds.byTag("a"))
    }
}
