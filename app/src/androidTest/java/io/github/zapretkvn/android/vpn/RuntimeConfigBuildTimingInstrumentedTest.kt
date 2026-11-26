package io.github.zapretkvn.android.vpn

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.EntryHopOptions
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.config.RuntimeConfigBuilder
import io.github.zapretkvn.android.config.RuntimeConfigOptions
import io.github.zapretkvn.android.config.RuntimeConfigResult
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The runtime-overlay stage on a thousand and a half servers, measured on ART (owner 2026-11-26, "optimize the 5.4 s": the
 * phone spent 5.4 s between "WARP account ready" and "servers through the hop", of which the build was 0.2 s and a regular
 * expression over the finished text the rest; the count now comes from the builder). The profile is the
 * real one the app stores for the vpnbot test person's VLESS servers, its real servers repeated under new tags up to the
 * owner's list size. Only timings are logged (tag `KVNTIMING`).
 */
@RunWith(AndroidJUnit4::class)
class RuntimeConfigBuildTimingInstrumentedTest {
    @Test
    fun timings() {
        val raw = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /data/local/tmp/kvn-test-subscription")
            .use { FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8) }
        assumeTrue(raw.startsWith("{"))
        val url = (JsonConfig.parse(raw) as JsonObject).getValue("vless_subscription_url").jsonPrimitive.content
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "ZapretKVN")
        val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val managed = ImportParser.parse(body, ProfileSource.Url) as ImportCandidate.Managed
        val profile = scaled(ManagedProfileFactory.subscription(managed.servers), 1448)
        for (hop in listOf(false, true)) {
            repeat(3) { round ->
                val parseStarted = System.nanoTime()
                val parsed = ParsedConfig.parse(profile)
                val parseMs = (System.nanoTime() - parseStarted) / 1_000_000
                val options = RuntimeConfigOptions(
                    dnsMode = DnsMode.Secure,
                    blockQuic = true,
                    entryHop = EntryHopOptions("/data/user/0/app/files/warp-profile.json").takeIf { hop },
                )
                val started = System.nanoTime()
                val result = RuntimeConfigBuilder.build(parsed, enableTrafficStats = true, options = options) as RuntimeConfigResult.Ready
                val buildMs = (System.nanoTime() - started) / 1_000_000
                if (hop) assertTrue("every server must be chained, got ${result.hopChained}", result.hopChained >= 1448)
                assertTrue("the build took ${buildMs} ms on this device", buildMs < 5_000)
                Log.i("KVNTIMING", "hop=$hop round=$round parse=${parseMs}ms build=${buildMs}ms chained=${result.hopChained} size=${result.json.length / 1024}KB phases: ${result.phases}")
            }
        }
    }

    private fun scaled(raw: String, count: Int): String {
        val root = JsonConfig.parse(raw) as JsonObject
        val outbounds = (root["outbounds"] as JsonArray).map { it as JsonObject }
        val servers = outbounds.filter { it["server"] != null && (it["type"] as? JsonPrimitive)?.content !in setOf("direct", "block", "dns") }
        val extra = mutableListOf<JsonObject>()
        var index = 0
        while (servers.size + extra.size < count) {
            val source = servers[index % servers.size]
            extra += JsonObject(source + ("tag" to JsonPrimitive("${(source["tag"] as JsonPrimitive).content}~$index")))
            index++
        }
        val extraTags = extra.map { (it["tag"] as JsonPrimitive).content }
        val grown = outbounds.map { outbound ->
            val type = (outbound["type"] as? JsonPrimitive)?.content
            if (type == "selector" || type == "urltest") {
                val members = (outbound["outbounds"] as? JsonArray).orEmpty()
                JsonObject(outbound + ("outbounds" to JsonArray(members + extraTags.map { JsonPrimitive(it) })))
            } else {
                outbound
            }
        } + extra
        return JsonConfig.compact.encodeToString(JsonObject.serializer(), JsonObject(root + ("outbounds" to JsonArray(grown))))
    }
}
