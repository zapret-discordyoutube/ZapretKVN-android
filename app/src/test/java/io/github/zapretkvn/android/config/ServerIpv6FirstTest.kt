package io.github.zapretkvn.android.config

import java.net.InetAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerIpv6FirstTest {
    private fun outbounds(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray

    private fun JsonArray.byTag(tag: String): JsonObject =
        first { it.jsonObject["tag"]?.jsonPrimitive?.content == tag }.jsonObject

    @Test
    fun uplinkNeedsNativeGlobalIpv6AndDefaultRoute() {
        val global = InetAddress.getByName("2a00:1370:8186:18d3:59de:5750:748b:4ba9")
        val v4 = InetAddress.getByName("192.168.1.2")
        assertTrue(ServerIpv6First.uplinkHasIpv6(listOf(v4, global), hasDefaultIpv6Route = true))
        assertFalse(ServerIpv6First.uplinkHasIpv6(listOf(v4, global), hasDefaultIpv6Route = false))
        for (value in listOf("fe80::1", "fd7a:115c:a1e0::a937:dd2f", "2001:0:4136:e378::1", "2002:c000:204::1", "::1")) {
            assertFalse(value, ServerIpv6First.isNativeGlobalIpv6(InetAddress.getByName(value)))
        }
        assertFalse(ServerIpv6First.isNativeGlobalIpv6(v4))
        assertFalse(ServerIpv6First.enabled(setting = true, uplinkHasIpv6 = false))
        assertFalse(ServerIpv6First.enabled(setting = false, uplinkHasIpv6 = true))
        assertTrue(ServerIpv6First.enabled(setting = true, uplinkHasIpv6 = true))
    }

    @Test
    fun serverOutboundsGetPreferIpv6Resolver() {
        val result = ServerIpv6First.apply(
            outbounds(
                """
                [
                  {"type":"vless","tag":"xray","uri":"vless://id@node.example:443?security=reality"},
                  {"type":"shadowsocks","tag":"ss","server":"ss.example","server_port":8388,"domain_resolver":"zapret-bootstrap-lkg"},
                  {"type":"hysteria2","tag":"hy","server":"hy.example","server_port":443,
                   "domain_resolver":{"server":"zapret-bootstrap-lkg"}},
                  {"type":"trojan","tag":"own","server":"own.example","domain_resolver":{"server":"x","strategy":"ipv4_only"}},
                  {"type":"shadowsocks","tag":"literal","server":"194.87.115.138"},
                  {"type":"shadowsocks","tag":"literal6","server":"2a03:6f02::175a"},
                  {"type":"shadowsocks","tag":"chained","server":"c.example","detour":"ss"},
                  {"type":"direct","tag":"direct"},
                  {"type":"selector","tag":"proxy","outbounds":["xray","ss"]}
                ]
                """,
            ),
            defaultResolver = "zapret-android-dns",
        )
        val preferAndroid = Json.parseToJsonElement("""{"server":"zapret-android-dns","strategy":"prefer_ipv6"}""")
        val preferBootstrap = Json.parseToJsonElement("""{"server":"zapret-bootstrap-lkg","strategy":"prefer_ipv6"}""")
        assertEquals(preferAndroid, result.byTag("xray")["domain_resolver"])
        assertEquals(preferBootstrap, result.byTag("ss")["domain_resolver"])
        assertEquals(preferBootstrap, result.byTag("hy")["domain_resolver"])
        assertEquals(
            "a user's own strategy stays",
            Json.parseToJsonElement("""{"server":"x","strategy":"ipv4_only"}"""),
            result.byTag("own")["domain_resolver"],
        )
        for (tag in listOf("literal", "literal6", "chained", "direct", "proxy")) {
            assertFalse(tag, result.byTag(tag).containsKey("domain_resolver"))
        }
    }

    @Test
    fun withoutManagedDnsOnlyExistingResolversAreWrapped() {
        val result = ServerIpv6First.apply(
            outbounds(
                """[{"type":"vless","tag":"a","uri":"vless://id@a.example:443"},
                   {"type":"vless","tag":"b","uri":"vless://id@b.example:443","domain_resolver":"profile-dns"}]""",
            ),
            defaultResolver = null,
        )
        assertFalse(result.byTag("a").containsKey("domain_resolver"))
        assertEquals(
            Json.parseToJsonElement("""{"server":"profile-dns","strategy":"prefer_ipv6"}"""),
            result.byTag("b")["domain_resolver"],
        )
    }
}
