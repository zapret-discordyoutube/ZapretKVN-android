package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDefaultUtlsTest {
    private fun runtimeOutbound(outbound: String, tag: String = "server-a"): JsonObject {
        val stored = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                $outbound,
                {"type":"selector","tag":"zapret-proxy","outbounds":["$tag"],"default":"$tag"},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """.trimIndent()
        val result = RuntimeConfigBuilder.build(stored)
        check(result is RuntimeConfigResult.Ready) { "runtime rejected: $result" }
        val runtime = JsonConfig.parse(result.json) as JsonObject
        return (runtime["outbounds"] as JsonArray).map { it as JsonObject }.first { it.string("tag") == tag }
    }

    private fun JsonObject.utls(): JsonObject? = (this["tls"] as JsonObject)["utls"] as? JsonObject

    @Test
    fun `tcp tls without utls gets chrome fingerprint`() {
        for (type in listOf("vless", "trojan", "vmess")) {
            val outbound = runtimeOutbound(
                """{"type":"$type","tag":"server-a","server":"example.com","server_port":443,""" +
                    """"uuid":"00000000-0000-0000-0000-000000000000","password":"p",""" +
                    """"tls":{"enabled":true,"server_name":"example.com"}}""",
            )
            val utls = checkNotNull(outbound.utls()) { "$type must get uTLS" }
            assertTrue((utls["enabled"] as JsonPrimitive).boolean)
            assertEquals("chrome", (utls["fingerprint"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `reality without fingerprint becomes runnable chrome`() {
        val outbound = runtimeOutbound(
            """{"type":"vless","tag":"server-a","server":"example.com","server_port":443,""" +
                """"uuid":"00000000-0000-0000-0000-000000000000",""" +
                """"tls":{"enabled":true,"server_name":"example.com","reality":{"enabled":true,"public_key":"k"}}}""",
        )
        assertEquals("chrome", (checkNotNull(outbound.utls())["fingerprint"] as JsonPrimitive).content)
    }

    @Test
    fun `explicit utls choice is kept even when disabled`() {
        val firefox = runtimeOutbound(
            """{"type":"trojan","tag":"server-a","server":"example.com","server_port":443,"password":"p",""" +
                """"tls":{"enabled":true,"utls":{"enabled":true,"fingerprint":"firefox"}}}""",
        )
        assertEquals("firefox", (checkNotNull(firefox.utls())["fingerprint"] as JsonPrimitive).content)

        val disabled = runtimeOutbound(
            """{"type":"trojan","tag":"server-a","server":"example.com","server_port":443,"password":"p",""" +
                """"tls":{"enabled":true,"utls":{"enabled":false}}}""",
        )
        assertFalse((checkNotNull(disabled.utls())["enabled"] as JsonPrimitive).boolean)
    }

    @Test
    fun `quic protocols and quic transport and plain outbounds stay without utls`() {
        val hysteria = runtimeOutbound(
            """{"type":"hysteria2","tag":"server-a","server":"example.com","server_port":443,"password":"p",""" +
                """"tls":{"enabled":true,"server_name":"example.com"}}""",
        )
        assertNull(hysteria.utls())

        val quicTransport = runtimeOutbound(
            """{"type":"vless","tag":"server-a","server":"example.com","server_port":443,""" +
                """"uuid":"00000000-0000-0000-0000-000000000000","transport":{"type":"quic"},""" +
                """"tls":{"enabled":true,"server_name":"example.com"}}""",
        )
        assertNull(quicTransport.utls())

        val disabledTls = runtimeOutbound(
            """{"type":"vless","tag":"server-a","server":"example.com","server_port":443,""" +
                """"uuid":"00000000-0000-0000-0000-000000000000","tls":{"enabled":false}}""",
        )
        assertNull(disabledTls.utls())
    }
}
