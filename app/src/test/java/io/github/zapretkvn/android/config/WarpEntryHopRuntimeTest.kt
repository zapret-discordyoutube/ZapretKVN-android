package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The WARP entry hop through the real builder: servers detour into it, nothing else may name it (owner 2026-11-25). */
class WarpEntryHopRuntimeTest {
    private val hop = EntryHopOptions(profilePath = "/data/user/0/app/files/warp-profile.json")

    private fun profile(routeExtra: String = ""): String = """
        {
          "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
          "outbounds":[
            {"type":"hysteria2","tag":"hy","server":"hy.example.net","server_port":443,"password":"x","tls":{"enabled":true,"server_name":"hy.example.net"}},
            {"type":"selector","tag":"zapret-proxy","outbounds":["hy"],"default":"hy"},
            {"type":"direct","tag":"direct"}
          ],
          "route":{"auto_detect_interface":true,"final":"zapret-proxy","rules":[{"domain_suffix":["ru"],"outbound":"direct"}$routeExtra]}
        }
    """.trimIndent()

    private fun build(raw: String, hopOptions: EntryHopOptions? = hop, mode: DnsMode = DnsMode.Android) =
        RuntimeConfigBuilder.build(raw, options = RuntimeConfigOptions(dnsMode = mode, entryHop = hopOptions))

    private fun root(result: RuntimeConfigResult): JsonObject =
        JsonConfig.parse((result as RuntimeConfigResult.Ready).json) as JsonObject

    private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().map { it as JsonObject }

    @Test
    fun `server detours into the hop and the hop endpoint is added`() {
        val built = root(build(profile()))
        val server = built.list("outbounds").first { it.string("tag") == "hy" }
        assertEquals(WarpEntryHop.TAG, server.string("detour"))
        assertEquals("zapret-android-dns", server.string("domain_resolver"))
        val endpoint = built.list("endpoints").single { it.string("tag") == WarpEntryHop.TAG }
        assertEquals("warp", endpoint.string("type"))
        assertNull("the hop dials Cloudflare directly", endpoint.string("detour"))
        assertEquals(emptyList<String>(), WarpEntryHop.violations(built))
    }

    @Test
    fun `hop is an entry only the selector final and direct rules never name it`() {
        val built = root(build(profile()))
        val route = built["route"] as JsonObject
        assertEquals("zapret-proxy", route.string("final"))
        val selector = built.list("outbounds").first { it.string("tag") == "zapret-proxy" }
        assertFalse(WarpEntryHop.TAG in selector["outbounds"].toString())
        val directRule = route.list("rules").first { it["domain_suffix"].toString().contains("ru") }
        assertEquals("direct", directRule.string("outbound"))
        assertFalse("only the connection to our server enters WARP", WarpEntryHop.TAG in route.toString())
    }

    @Test
    fun `off by default leaves the runtime copy without any hop`() {
        val built = root(build(profile(), hopOptions = null))
        assertFalse(WarpEntryHop.TAG in built.toString())
        assertNull(built.list("outbounds").first { it.string("tag") == "hy" }.string("detour"))
    }

    @Test
    fun `a runtime copy fed back in keeps exactly one hop and its detours`() {
        val first = (build(profile()) as RuntimeConfigResult.Ready).json
        val built = root(build(first))
        assertEquals(1, built.list("endpoints").count { it.string("tag") == WarpEntryHop.TAG })
        assertEquals(WarpEntryHop.TAG, built.list("outbounds").first { it.string("tag") == "hy" }.string("detour"))
    }

    @Test
    fun `a profile that routes traffic into the hop does not start`() {
        val rule = ",{\"domain_suffix\":[\"x.example\"],\"outbound\":\"${WarpEntryHop.TAG}\"}"
        val result = build(profile(routeExtra = rule))
        assertTrue(result.toString(), result is RuntimeConfigResult.Invalid)
        assertTrue((result as RuntimeConfigResult.Invalid).message, result.message.contains("WARP"))
    }
}
