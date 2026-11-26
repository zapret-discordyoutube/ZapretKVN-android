package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The WARP entry hop through the real builder, on the real servers of the vpnbot test person (owner 2026-11-25: servers
 * detour into the hop, nothing else may name it; tests run on real data only).
 *
 * The profile is what the app's own importer stores for the test person's VLESS and Hysteria2 subscriptions, wrapped
 * into a TUN profile the way the app's routing wraps it.
 */
class WarpEntryHopRuntimeTest {
    private val hop = EntryHopOptions(profilePath = "/data/user/0/app/files/warp-profile.json")

    @Before
    fun realDataIsThere() = RealSubscription.requireIt()

    private fun build(raw: String, hopOptions: EntryHopOptions? = hop, mode: DnsMode = DnsMode.Android) =
        RuntimeConfigBuilder.build(raw, options = RuntimeConfigOptions(dnsMode = mode, entryHop = hopOptions))

    private fun root(result: RuntimeConfigResult): JsonObject {
        assertTrue(result.toString().take(200), result is RuntimeConfigResult.Ready)
        return JsonConfig.parse((result as RuntimeConfigResult.Ready).json) as JsonObject
    }

    private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().map { it as JsonObject }

    private val profiles get() = listOf(RealSubscription.vlessProfile, RealSubscription.hysteriaProfile)

    /** The servers the hop must carry: remote outbounds, not the local helper relays and not the grouping types. */
    private fun JsonObject.servers() = list("outbounds").filter {
        val type = it.string("type")
        type != "selector" && type != "urltest" && type != "direct" && type != "block" && type != "dns" &&
            it.string("tag") != WarpEntryHop.API_TAG
    }

    @Test
    fun `every real server detours into the hop and the hop endpoint is added`() {
        for (raw in profiles) {
            val built = root(build(raw))
            val servers = built.servers()
            assertTrue("the test person has servers", servers.isNotEmpty())
            for (server in servers) {
                assertEquals(server.string("tag"), WarpEntryHop.TAG, server.string("detour"))
            }
            val endpoint = built.list("endpoints").single { it.string("tag") == WarpEntryHop.TAG }
            assertEquals("warp", endpoint.string("type"))
            assertNull("the hop dials Cloudflare directly", endpoint.string("detour"))
            assertEquals(emptyList<String>(), WarpEntryHop.violations(built))
        }
    }

    @Test
    fun `hop is an entry only the final and direct rules never name it`() {
        for (raw in profiles) {
            val built = root(build(raw))
            val route = built["route"] as JsonObject
            assertFalse(route.string("final") == WarpEntryHop.TAG)
            for (group in built.list("outbounds").filter { it.string("type") == "selector" || it.string("type") == "urltest" }) {
                assertFalse(WarpEntryHop.TAG in group["outbounds"].toString())
            }
            assertFalse("only the connection to our server enters WARP", WarpEntryHop.TAG in route.toString())
        }
    }

    @Test
    fun `off by default leaves the runtime copy without any hop`() {
        for (raw in profiles) {
            val built = root(build(raw, hopOptions = null))
            assertFalse(WarpEntryHop.TAG in built.toString())
            assertTrue(built.servers().all { it.string("detour") == null })
        }
    }

    @Test
    fun `a runtime copy fed back in keeps exactly one hop and its detours`() {
        for (raw in profiles) {
            val first = (build(raw) as RuntimeConfigResult.Ready).json
            val built = root(build(first))
            assertEquals(1, built.list("endpoints").count { it.string("tag") == WarpEntryHop.TAG })
            assertTrue(built.servers().all { it.string("detour") == WarpEntryHop.TAG })
        }
    }

    @Test
    fun `a profile that routes traffic into the hop does not start`() {
        // The real profile of the test person with one extra route rule a user could write by hand.
        val raw = RealSubscription.hysteriaProfile
        val rule = JsonConfig.parse(raw) as JsonObject
        val route = (rule["route"] as? JsonObject) ?: JsonObject(emptyMap())
        val rules = (route["rules"] as? JsonArray).orEmpty() + JsonConfig.parse(
            """{"domain_suffix":["x.example"],"outbound":"${WarpEntryHop.TAG}"}""",
        )
        val edited = JsonObject(rule + ("route" to JsonObject(route + ("rules" to JsonArray(rules)))))
        val result = build(edited.toString())
        assertTrue(result.toString().take(200), result is RuntimeConfigResult.Invalid)
        assertTrue((result as RuntimeConfigResult.Invalid).message, result.message.contains("WARP"))
    }
}
