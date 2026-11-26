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
import org.junit.Before
import org.junit.Test

/**
 * The hop pass on the real servers of the vpnbot test person (owner 2026-11-25: tests run on real data only): the
 * outbounds are what the app's builder makes of the importer's profiles, the WireGuard endpoint is the test person's
 * real AmneziaWG key.
 */
class WarpEntryHopTest {
    @Before
    fun realDataIsThere() = RealSubscription.requireIt()

    private fun JsonArray.byTag(tag: String): JsonObject =
        first { it.jsonObject["tag"]?.jsonPrimitive?.content == tag }.jsonObject

    private fun built(raw: String): JsonObject {
        val result = RuntimeConfigBuilder.build(raw, options = RuntimeConfigOptions(dnsMode = DnsMode.Android))
        assertTrue(result.toString().take(200), result is RuntimeConfigResult.Ready)
        return JsonConfig.parse((result as RuntimeConfigResult.Ready).json) as JsonObject
    }

    private val real: JsonObject by lazy { built(RealSubscription.vlessProfile) }
    private val outbounds: JsonArray get() = real["outbounds"]!!.jsonArray

    private fun apply(endpoints: JsonArray? = null, resolver: String? = "android-dns") =
        WarpEntryHop.apply(outbounds, endpoints, "/data/warp.json", resolver)

    private fun servers(list: JsonArray) = list.map { it.jsonObject }.filter {
        it["server"] != null || it["uri"] != null
    }

    @Test
    fun realServersGetDetourAndLocalTypesDoNot() {
        val result = apply()
        val list = checkNotNull(result.outbounds)
        val remote = servers(list)
        assertTrue(remote.isNotEmpty())
        assertEquals(remote.size, result.chained)
        for (server in remote) assertEquals(WarpEntryHop.TAG, server["detour"]?.jsonPrimitive?.content)
        for (item in list.map { it.jsonObject }) {
            val type = item["type"]?.jsonPrimitive?.content
            if (type == "selector" || type == "urltest" || type == "direct" || type == "block") assertNull(item["detour"])
        }
    }

    @Test
    fun realServerNamesResolveOutsideTheTunnelAndAUsersChoiceStays() {
        val list = checkNotNull(apply().outbounds)
        val named = servers(list).filter { it["server"]?.jsonPrimitive?.content?.any { c -> c.isLetter() && c != ':' } == true }
        assertTrue("the test person has servers with a host name", named.isNotEmpty())
        for (server in named) assertEquals("android-dns", server["domain_resolver"]?.jsonPrimitive?.content)
        // a resolver the user chose in the profile is kept
        val chosen = JsonArray(
            outbounds.map {
                val item = it.jsonObject
                if (item === named.first() || item["tag"] == named.first()["tag"]) {
                    JsonObject(item + ("domain_resolver" to Json.parseToJsonElement("""{"server":"mine"}""")))
                } else {
                    item
                }
            },
        )
        val own = WarpEntryHop.apply(chosen, null, "/p", "android-dns")
        val tag = named.first()["tag"]!!.jsonPrimitive.content
        assertEquals("mine", own.outbounds!!.byTag(tag)["domain_resolver"]!!.jsonObject["server"]!!.jsonPrimitive.content)
        // no resolver known: nothing is added
        val none = WarpEntryHop.apply(outbounds, null, "/p", null)
        assertTrue(servers(none.outbounds!!).none { it["domain_resolver"] != null && "domain_resolver" !in outbounds.byTag(it["tag"]!!.jsonPrimitive.content) })
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
    fun theRealAmneziaWgEndpointEntersThroughTheHopToo() {
        val awg = built(RealSubscription.awgProfile)
        val endpoints = awg["endpoints"]!!.jsonArray
        val result = WarpEntryHop.apply(awg["outbounds"]?.jsonArray ?: JsonArray(emptyList()), endpoints, "/data/warp.json", "android-dns")
        val wireguard = checkNotNull(result.endpoints).map { it.jsonObject }.single {
            it["type"]?.jsonPrimitive?.content == "wireguard"
        }
        assertEquals(WarpEntryHop.TAG, wireguard["detour"]?.jsonPrimitive?.content)
        assertTrue(result.chained >= 1)
    }

    @Test
    fun noServersMeansNoHopAndNoRegistration() {
        // The real runtime copy with every remote server taken out: nothing to enter, so no hop and no registration.
        val local = JsonArray(outbounds.filter { it.jsonObject["server"] == null && it.jsonObject["uri"] == null }
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content != "selector" })
        val result = WarpEntryHop.apply(local, null, "/p", "android-dns")
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
        val result = apply()
        fun violationsOf(mutate: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): List<String> {
            val root = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                "outbounds" to result.outbounds!!, "endpoints" to result.endpoints!!,
            )
            mutate(root)
            return WarpEntryHop.violations(JsonObject(root))
        }
        fun route(json: String) = Json.parseToJsonElement(json)
        assertTrue(violationsOf { it["route"] = route("""{"final":"${WarpEntryHop.TAG}"}""") }.isNotEmpty())
        assertTrue(violationsOf {
            it["route"] = route("""{"rules":[{"domain":["a.example"],"outbound":"${WarpEntryHop.TAG}"}]}""")
        }.isNotEmpty())
        assertTrue(violationsOf {
            it["outbounds"] = JsonArray(
                result.outbounds!! + route("""{"type":"selector","tag":"p","outbounds":["${WarpEntryHop.TAG}"]}"""),
            )
        }.isNotEmpty())
        assertTrue(violationsOf {
            it["outbounds"] = JsonArray(result.outbounds!! + route("""{"type":"direct","tag":"${WarpEntryHop.TAG}"}"""))
        }.isNotEmpty())
        assertTrue("a detour outside outbounds/endpoints/dns is not a server dialing it",
            violationsOf { it["route"] = route("""{"detour":"${WarpEntryHop.TAG}"}""") }.isNotEmpty())
    }

    @Test
    fun hopEndpointCarriesClientSideNoiseBuiltByTheMaskingModule() {
        // The same chain the app's WireGuard masking builds for the preset domain; only client-side Amnezia options.
        val noise = WarpEntryHop.NOISE
        assertEquals(io.github.zapretkvn.masking.wireguard.DnsSignature.chain("yandex.ru"), noise["i1"]?.jsonPrimitive?.content)
        assertEquals(setOf("jc", "jmin", "jmax", "i1"), noise.keys)
    }
}
