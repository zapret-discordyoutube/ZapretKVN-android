package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Where the time of the runtime-overlay stage goes on a big subscription (owner 2026-11-26, "optimize the 5.4 s": the phone
 * spent 5.4 s between "WARP account ready" and "servers through the hop" with 1448 servers).
 *
 * The profile is the real one the app stores for the vpnbot test person's servers, multiplied by repeating those real
 * servers under new tags up to the owner's list size: the size is the point of the measurement, not the content. The
 * numbers are timings only (no server name or address is written). Phase shares carry over to the phone, absolute
 * milliseconds do not (a phone is several times slower than this host).
 */
class RuntimeConfigBuildTimingTest {
    @Before
    fun realDataIsThere() = RealSubscription.requireIt()

    /** The real profile with its servers repeated until [count] servers exist; every group gets the new tags too. */
    private fun scaled(raw: String, count: Int): String {
        val root = JsonConfig.parse(raw) as JsonObject
        val outbounds = (root["outbounds"] as JsonArray).map { it as JsonObject }
        val isServer = { outbound: JsonObject -> outbound.string("server") != null && outbound.string("type") !in setOf("direct", "block", "dns") }
        val servers = outbounds.filter(isServer)
        require(servers.isNotEmpty())
        val extra = mutableListOf<JsonObject>()
        var index = 0
        while (servers.size + extra.size < count) {
            val source = servers[index % servers.size]
            val tag = "${source.string("tag")}~${index}"
            extra += JsonObject(source + ("tag" to JsonPrimitive(tag)))
            index++
        }
        val extraTags = extra.map { it.string("tag")!! }
        val grown = outbounds.map { outbound ->
            if (outbound.string("type") in setOf("selector", "urltest")) {
                val members = (outbound["outbounds"] as? JsonArray).orEmpty()
                JsonObject(outbound + ("outbounds" to JsonArray(members + extraTags.map { JsonPrimitive(it) })))
            } else {
                outbound
            }
        } + extra
        return JsonConfig.compact.encodeToString(JsonObject.serializer(), JsonObject(root + ("outbounds" to JsonArray(grown))))
    }

    private fun JsonObject.detouringIntoTheHop(): Int =
        ((this["outbounds"] as? JsonArray).orEmpty() + (this["endpoints"] as? JsonArray).orEmpty())
            .count { (it as JsonObject).string("detour") == WarpEntryHop.TAG }

    /**
     * The server count of the connect log line is the builder's own, not a scan of the finished text: that scan was a
     * regular expression over 1.8 MB which cost 2.5 s on an emulator and 5.4 s on the owner's phone, against 0.2 s for the
     * whole build (measured 2026-11-26). The count must equal what is really in the config, and the build must stay in
     * the range of a fifth of a second per thousand servers on a host (a generous 10 s bound, it is a guard for the order
     * of magnitude, not a benchmark).
     */
    @Test
    fun `the hop count of a thousand and a half real servers comes from the builder and is right`() {
        for ((name, source) in listOf("vless" to RealSubscription.vlessProfile, "hysteria2" to RealSubscription.hysteriaProfile)) {
            val raw = scaled(source, 1448)
            val withoutHop = RuntimeConfigBuilder.build(ParsedConfig.parse(raw), enableTrafficStats = true) as RuntimeConfigResult.Ready
            assertEquals(name, 0, withoutHop.hopChained)
            val options = RuntimeConfigOptions(dnsMode = DnsMode.Android, entryHop = EntryHopOptions("/data/user/0/app/files/warp-profile.json"))
            val started = System.nanoTime()
            val ready = RuntimeConfigBuilder.build(ParsedConfig.parse(raw), enableTrafficStats = true, options = options) as RuntimeConfigResult.Ready
            val millis = (System.nanoTime() - started) / 1_000_000
            val inConfig = (JsonConfig.parse(ready.json) as JsonObject).detouringIntoTheHop()
            assertEquals("$name: the count the log prints must be what the config holds", inConfig, ready.hopChained)
            assertTrue("$name: servers must be chained, got ${ready.hopChained}", ready.hopChained >= 1448)
            assertTrue("$name: the build took ${millis} ms (${ready.phases})", millis < 10_000)
        }
    }
}
