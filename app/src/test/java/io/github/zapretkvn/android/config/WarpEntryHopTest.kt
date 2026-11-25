package io.github.zapretkvn.android.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpEntryHopTest {
    private fun array(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray

    private fun JsonArray.byTag(tag: String): JsonObject =
        first { it.jsonObject["tag"]?.jsonPrimitive?.content == tag }.jsonObject

    private val outbounds = """
        [
          {"type":"hysteria2","tag":"hy","server":"hy.example.net","server_port":443},
          {"type":"vless","tag":"vl","server":"203.0.113.7","server_port":443},
          {"type":"vless","tag":"xr","uri":"vless://x@host.example:443"},
          {"type":"trojan","tag":"chained","server":"t.example","server_port":443,"detour":"hy"},
          {"type":"selector","tag":"proxy","outbounds":["hy","vl","xr"]},
          {"type":"direct","tag":"direct"},
          {"type":"block","tag":"block"}
        ]
    """.trimIndent()

    private fun apply(endpoints: String? = null, resolver: String? = "android-dns") =
        WarpEntryHop.apply(array(outbounds), endpoints?.let(::array), "/data/warp.json", resolver)

    @Test
    fun serversGetDetourAndLocalTypesDoNot() {
        val result = apply()
        val list = checkNotNull(result.outbounds)
        assertEquals(3, result.chained)
        for (tag in listOf("hy", "vl", "xr")) {
            assertEquals(tag, WarpEntryHop.TAG, list.byTag(tag)["detour"]?.jsonPrimitive?.content)
        }
        assertEquals("hy", list.byTag("chained")["detour"]?.jsonPrimitive?.content) // not the first link of its chain
        for (tag in listOf("proxy", "direct", "block")) assertNull(tag, list.byTag(tag)["detour"])
    }

    @Test
    fun serverNamesResolveOutsideTheTunnelAndLiteralsAndUserChoicesStay() {
        val list = checkNotNull(apply().outbounds)
        assertEquals("android-dns", list.byTag("hy")["domain_resolver"]?.jsonPrimitive?.content)
        assertEquals("android-dns", list.byTag("xr")["domain_resolver"]?.jsonPrimitive?.content)
        assertNull("an IP literal has nothing to resolve", list.byTag("vl")["domain_resolver"])
        val own = WarpEntryHop.apply(
            array("""[{"type":"hysteria2","tag":"hy","server":"h.example","domain_resolver":{"server":"mine"}}]"""),
            null, "/p", "android-dns",
        )
        assertEquals("mine", own.outbounds!![0].jsonObject["domain_resolver"]!!.jsonObject["server"]!!.jsonPrimitive.content)
        val none = WarpEntryHop.apply(array("""[{"type":"hysteria2","tag":"hy","server":"h.example"}]"""), null, "/p", null)
        assertNull(none.outbounds!![0].jsonObject["domain_resolver"])
    }

    @Test
    fun hopEndpointIsAddedOnceWithMtuAndPrivateProfilePath() {
        val result = apply()
        val hop = checkNotNull(result.endpoints).byTag(WarpEntryHop.TAG)
        assertEquals("warp", hop["type"]?.jsonPrimitive?.content)
        assertEquals(WarpEntryHop.MTU, hop["mtu"]?.jsonPrimitive?.content?.toInt())
        assertEquals("/data/warp.json", hop["profile"]!!.jsonObject["cache_path"]!!.jsonPrimitive.content)
        assertNull("the hop itself dials Cloudflare directly", hop["detour"])
        // registration runs through its own direct outbound: plain Go HTTP has no DNS and no protected socket on Android
        assertEquals(WarpEntryHop.API_TAG, hop["profile"]!!.jsonObject["detour"]!!.jsonPrimitive.content)
        val api = checkNotNull(result.outbounds).byTag(WarpEntryHop.API_TAG)
        assertEquals("direct", api["type"]?.jsonPrimitive?.content)
        assertEquals("android-dns", api["domain_resolver"]?.jsonPrimitive?.content)
        // a runtime copy fed back in must not double the hop
        val again = WarpEntryHop.apply(result.outbounds, result.endpoints, "/data/warp.json", "android-dns")
        assertEquals(1, again.endpoints!!.count { it.jsonObject["tag"]?.jsonPrimitive?.content == WarpEntryHop.TAG })
        assertEquals(1, again.outbounds!!.count { it.jsonObject["tag"]?.jsonPrimitive?.content == WarpEntryHop.API_TAG })
    }

    @Test
    fun wireguardEndpointsEnterThroughTheHopToo() {
        val result = apply(
            """[{"type":"wireguard","tag":"wg","peers":[{"address":"wg.example","port":51820}]},
                {"type":"wireguard","tag":"wg2","detour":"hy","peers":[]}]""",
        )
        val endpoints = checkNotNull(result.endpoints)
        assertEquals(WarpEntryHop.TAG, endpoints.byTag("wg")["detour"]?.jsonPrimitive?.content)
        assertEquals("hy", endpoints.byTag("wg2")["detour"]?.jsonPrimitive?.content)
        assertEquals("android-dns", endpoints.byTag("wg")["domain_resolver"]?.jsonPrimitive?.content)
        assertEquals(4, result.chained)
    }

    @Test
    fun noServersMeansNoHopAndNoRegistration() {
        val result = WarpEntryHop.apply(
            array("""[{"type":"selector","tag":"proxy","outbounds":[]},{"type":"direct","tag":"direct"}]"""),
            null, "/p", "android-dns",
        )
        assertEquals(0, result.chained)
        assertNull(result.endpoints)
        assertFalse(WarpEntryHop.API_TAG in result.outbounds.toString())
    }

    @Test
    fun guardAcceptsDetourOfServersAndTheHopsOwnTag() {
        val result = apply()
        val root = JsonObject(mapOf("outbounds" to result.outbounds!!, "endpoints" to result.endpoints!!))
        assertEquals(emptyList<String>(), WarpEntryHop.violations(root))
    }

    @Test
    fun guardRejectsTheHopAsExitSelectorMemberOrRouteTarget() {
        fun violations(json: String) = WarpEntryHop.violations(Json.parseToJsonElement(json).jsonObject)
        val hop = """"endpoints":[{"type":"warp","tag":"${WarpEntryHop.TAG}"}]"""
        assertTrue(violations("""{$hop,"route":{"final":"${WarpEntryHop.TAG}"}}""").isNotEmpty())
        assertTrue(violations("""{$hop,"route":{"rules":[{"domain":["a.example"],"outbound":"${WarpEntryHop.TAG}"}]}}""").isNotEmpty())
        assertTrue(violations("""{$hop,"outbounds":[{"type":"selector","tag":"p","outbounds":["${WarpEntryHop.TAG}"]}]}""").isNotEmpty())
        assertTrue(violations("""{"outbounds":[{"type":"direct","tag":"${WarpEntryHop.TAG}"}]}""").isNotEmpty())
        assertTrue("a detour outside outbounds/endpoints/dns is not a server dialing it",
            violations("""{$hop,"route":{"detour":"${WarpEntryHop.TAG}"}}""").isNotEmpty())
        assertFalse(violations("""{$hop,"outbounds":[{"type":"vless","tag":"v","server":"1.2.3.4","detour":"${WarpEntryHop.TAG}"}]}""").isNotEmpty())
    }
}
