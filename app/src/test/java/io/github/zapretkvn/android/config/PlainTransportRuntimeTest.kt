package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider stub nodes (`vless://…@0.0.0.0:1?security=none`) must not stop the start under Xray 26.11. */
class PlainTransportRuntimeTest {
    private val stub = """{"type":"vless","tag":"info","uri":"vless://u@0.0.0.0:1?encryption=none&type=tcp&security=none#Info","server":"0.0.0.0","server_port":1,"uuid":"u"}"""
    private val real = """{"type":"hysteria2","tag":"real","server":"example.com","server_port":443,"password":"p","tls":{"enabled":true,"server_name":"example.com"}}"""

    private fun config(members: String, default: String) = """
        {
          "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
          "outbounds":[$stub,$real,
            {"type":"selector","tag":"zapret-proxy","outbounds":[$members],"default":"$default"},
            {"type":"direct","tag":"direct"}],
          "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
        }
    """.trimIndent()

    @Test
    fun `stub server is removed from the runtime config and the others stay`() {
        val result = RuntimeConfigBuilder.build(config("\"info\",\"real\"", "real"))
        assertTrue(result.toString(), result is RuntimeConfigResult.Ready)
        val runtime = JsonConfig.parse((result as RuntimeConfigResult.Ready).json) as JsonObject
        val outbounds = (runtime["outbounds"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("real", "zapret-proxy", "direct"), outbounds.mapNotNull { it.string("tag") }.filter { it != "direct-dns" }.take(3))
        val group = outbounds.first { it.string("tag") == "zapret-proxy" }
        assertEquals(listOf("real"), (group["outbounds"] as JsonArray).map { (it as JsonPrimitive).contentOrNull })
    }

    @Test
    fun `selected stub server is refused with a clear code`() {
        val result = RuntimeConfigBuilder.build(config("\"info\"", "info"))
        assertTrue(result.toString(), result is RuntimeConfigResult.Invalid)
        assertEquals(PlainTransportPolicy.FAILURE_CODE, (result as RuntimeConfigResult.Invalid).code)
    }
}
