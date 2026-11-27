package io.github.zapretkvn.android.vpn

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A MEASUREMENT, not a check: what the WARP entry hop costs a start on the REAL service (owner/WARP-session question
 * 2026-11-27: on the owner's phone with the hop on, the stage «DNS через TUN (UDP)» took 2.35-2.51 s against 0.08-0.83 s
 * with it off, 2 hop-on starts against 5 hop-off ones: too small a sample to change code on).
 *
 * It runs only when asked (`-e kvnWarpSeries 10` = ten starts per arm), because it takes minutes and proves nothing by
 * passing. It starts the same real server of the vpnbot test person (the first VLESS server that answers without the hop)
 * in three arms in rotation (hop off; hop on with the remembered edge; hop on with the edge FORGOTTEN, which is what the
 * first start after a network change is: the core forgets what the old network taught), with the REAL health check (no `succeedNextHealthCheck`: the DNS stage belongs to
 * it), reads the start stages the Diagnostics screen shows (`DiagnosticConnectionAttempt.stages`) and the core's own
 * WARP lines, stops, pauses and goes on. One warm-up start per arm is not counted (the first hop start registers or loads
 * the accounts). Rows go to `getExternalFilesDir/warp-hop-series.jsonl`, one JSON object per start; nothing else is printed.
 */
@RunWith(AndroidJUnit4::class)
class WarpHopStartSeriesInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container: AppContainer = (context.applicationContext as ZapretApplication).container
    private var profile: ProfileMetadata? = null
    private var previousDnsMode: DnsMode? = null

    private val eachServer: Boolean by lazy {
        InstrumentationRegistry.getArguments().getString("kvnEachServer") == "1"
    }

    private val perArm: Int by lazy {
        InstrumentationRegistry.getArguments().getString("kvnWarpSeries")?.toIntOrNull() ?: 0
    }

    @Before
    fun allowVpn() {
        assumeTrue(
            "opt-in: pass -e kvnWarpSeries <starts per arm> or -e kvnEachServer 1",
            perArm > 0 || eachServer,
        )
        assumeTrue("no real test subscription on the device (adb push to $SUBSCRIPTION)", subscription() != null)
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }

    @After
    fun stopAndClean() {
        runBlocking {
            container.uiSettingsStore.setWarpEntryHop(false)
            previousDnsMode?.let { container.uiSettingsStore.setDnsMode(it) }
            container.vpnController.setDiagnosticsVisible(false)
            if (container.vpnController.state.value !is VpnConnectionState.Stopped) stopVpn()
            profile?.let { container.profileStore.delete(it.id) }
        }
        shell("appops set ${context.packageName} ACTIVATE_VPN default")
    }

    /**
     * Every real VLESS and Hysteria2 server of the test person started once, hop OFF, with the real health check and the
     * phone's DNS mode: which servers answer the UDP DNS probe at once and which need the 2.5 s timeout and the TCP
     * fallback (a transport that cannot carry UDP costs every start that wait). One row per server: the transport label
     * only (ws/grpc/tcp/h2, never an address), the stage durations and outcomes. `warp-hop-servers.jsonl`.
     */
    @Test
    fun eachRealServerOnceAndItsDnsProbe() = runBlocking {
        assumeTrue("opt-in: pass -e kvnEachServer 1", eachServer)
        val vless = (ImportParser.parse(
            fetch(JSONObject(subscription()!!).getString("vless_subscription_url")), ProfileSource.Url,
        ) as ImportCandidate.Managed).servers
        val hysteria = (ImportParser.parse(
            fetch(JSONObject(subscription()!!).getString("hysteria2_subscription_url")), ProfileSource.Url,
        ) as ImportCandidate.Managed).servers
        container.appSelectionStore.replaceAllowlist(setOf(SHELL_PACKAGE))
        container.profileStore.initialize()
        container.vpnController.setDiagnosticsVisible(true)
        previousDnsMode = container.uiSettingsStore.settings.first().dnsMode
        container.uiSettingsStore.setDnsMode(DnsMode.Secure)
        container.uiSettingsStore.setWarpEntryHop(false)
        val out = File(context.getExternalFilesDir(null), "warp-hop-servers.jsonl")
        out.writeText("")
        for ((kind, list) in listOf("vless" to vless, "hysteria2" to hysteria)) {
            list.forEachIndexed { index, server ->
                val label = transportLabel(kind, server.outbound)
                profile = container.profileStore.create(
                    name = "per-server",
                    rawJson = ManagedProfileFactory.subscription(listOf(server)),
                    source = ProfileSource.RawJson,
                )
                val row = try {
                    start(hop = false) ?: JSONObject().put("connected", false)
                } catch (error: Throwable) {
                    JSONObject().put("connected", false).put("error", error.javaClass.simpleName)
                }
                out.appendText(row.put("kind", kind).put("transport", label).put("n", index).toString() + "\n")
                if (container.vpnController.state.value !is VpnConnectionState.Stopped) stopVpn()
                container.profileStore.delete(checkNotNull(profile).id)
                profile = null
                delay(PAUSE_MILLIS / 2)
            }
        }
        assertTrue("rows written", out.readLines().size == vless.size + hysteria.size)
    }

    /** Only a transport label, never an address or a name: for sing-box outbounds `transport.type` (ws, grpc, ...) and tls/reality. */
    private fun transportLabel(kind: String, outbound: kotlinx.serialization.json.JsonObject): String {
        if (kind == "hysteria2") return "hysteria2"
        fun text(element: kotlinx.serialization.json.JsonElement?) =
            (element as? kotlinx.serialization.json.JsonPrimitive)?.content
        val transport = (outbound["transport"] as? kotlinx.serialization.json.JsonObject)?.get("type")
        val tls = outbound["tls"] as? kotlinx.serialization.json.JsonObject
        val reality = (tls?.get("reality") as? kotlinx.serialization.json.JsonObject)?.get("enabled")
        val security = when {
            text(reality) == "true" -> "reality"
            text(tls?.get("enabled")) == "true" -> "tls"
            else -> "none"
        }
        return "${text(transport) ?: "tcp"}+$security"
    }

    @Test
    fun theHopAgainstNoHopOnTheSameRealServer() = runBlocking {
        assumeTrue("opt-in: pass -e kvnWarpSeries <starts per arm>", perArm > 0)
        val servers = (ImportParser.parse(
            fetch(JSONObject(subscription()!!).getString("vless_subscription_url")),
            ProfileSource.Url,
        ) as ImportCandidate.Managed).servers
        assertTrue("the test person has VLESS servers", servers.isNotEmpty())
        container.appSelectionStore.replaceAllowlist(setOf(SHELL_PACKAGE))
        container.profileStore.initialize()
        container.vpnController.setDiagnosticsVisible(true)
        // the owner's phone runs «Secure» (the stage «DNS через TUN (UDP)» belongs to it); the emulator default is another path
        previousDnsMode = container.uiSettingsStore.settings.first().dnsMode
        container.uiSettingsStore.setDnsMode(DnsMode.Secure)

        // the first server that answers without the hop: a server that is down would measure nothing about the hop
        container.uiSettingsStore.setWarpEntryHop(false)
        var chosen: String? = null
        for (server in servers.take(5)) {
            profile = container.profileStore.create(
                name = "WARP series",
                rawJson = ManagedProfileFactory.subscription(listOf(server)),
                source = ProfileSource.RawJson,
            )
            if (start(hop = false) != null) {
                stopVpn()
                chosen = checkNotNull(profile).id
                break
            }
            if (container.vpnController.state.value !is VpnConnectionState.Stopped) stopVpn()
            container.profileStore.delete(checkNotNull(profile).id)
            profile = null
        }
        assumeTrue("no real VLESS server answers from this device even without the hop", chosen != null)

        val out = File(context.getExternalFilesDir(null), "warp-hop-series.jsonl")
        out.writeText("")
        // warm-up, not counted: the first hop start loads or registers the accounts
        for (hop in listOf(true, false)) {
            start(hop)
            if (container.vpnController.state.value !is VpnConnectionState.Stopped) stopVpn()
            delay(PAUSE_MILLIS)
        }
        val edge = File(context.filesDir, "warp-entry-profile.json.edge")
        val arms = listOf("hop_off", "hop_on_warm", "hop_on_cold")
        var rows = 0
        repeat(perArm * arms.size) { index ->
            val arm = arms[index % arms.size]
            // a cold start has no remembered edge; a warm one finds what the previous hop start wrote
            if (arm == "hop_on_cold") edge.delete()
            val row = start(arm != "hop_off")
            val record = row ?: JSONObject().put("connected", false)
            out.appendText(record.put("arm", arm).put("index", index).put("edge_file_before", arm == "hop_on_warm" && edge.exists()).toString() + "\n")
            if (container.vpnController.state.value !is VpnConnectionState.Stopped) stopVpn()
            rows++
            delay(PAUSE_MILLIS)
        }
        assertTrue("every start left a row", rows == perArm * arms.size)
    }

    /** One start with the REAL health check; the row of what the attempt reports, or null when it did not connect. */
    private suspend fun start(hop: Boolean): JSONObject? {
        container.uiSettingsStore.setWarpEntryHop(hop)
        val before = container.vpnController.state.value
        container.vpnController.start(checkNotNull(profile).id)
        val state = withTimeout(90_000) {
            container.vpnController.state.first {
                it != before && (it is VpnConnectionState.Connected || it is VpnConnectionState.Error)
            }
        }
        // the attempt closes a moment after the state flips
        delay(1_500)
        val diagnostics = container.vpnController.diagnostics.value
        val attempt = diagnostics.recentConnectionAttempts.lastOrNull() ?: return null
        val row = JSONObject()
            .put("connected", state is VpnConnectionState.Connected)
            .put("total_ms", attempt.totalDurationMillis ?: -1)
        val stages = JSONArray()
        for (stage in attempt.stages) {
            stages.put(
                JSONObject().put("key", stage.key).put("ms", stage.durationMillis ?: -1)
                    .put("status", stage.status.code).put("detail", stage.detail ?: "")
                    .put("started_at", stage.startedAtEpochMillis - attempt.startedAtEpochMillis),
            )
        }
        row.put("stages", stages)
        // the WARP tunnel's own lines (handshake progress), time relative to the start of the attempt: whether a probe
        // started while the handshake was still running
        val warp = JSONArray()
        for (line in diagnostics.coreLogs) {
            val message = line.message
            if (line.receivedAtEpochMillis >= attempt.startedAtEpochMillis &&
                ("warp" in message.lowercase() || "handshake" in message.lowercase())
            ) {
                warp.put(
                    JSONObject().put("t", line.receivedAtEpochMillis - attempt.startedAtEpochMillis)
                        .put("m", message.take(140)),
                )
            }
            if (warp.length() >= 12) break
        }
        row.put("warp_lines", warp)
        return if (state is VpnConnectionState.Connected) row else row.put("connected", false)
    }

    private suspend fun stopVpn() {
        container.vpnController.stop()
        withTimeout(20_000) { container.vpnController.state.first { it is VpnConnectionState.Stopped } }
        withTimeout(10_000) { while (hasVpnNetwork()) delay(50) }
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "ZapretKVN")
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun subscription(): String? = shell("cat $SUBSCRIPTION").takeIf { it.startsWith("{") }

    private fun hasVpnNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().readText().trim()
        }

    private companion object {
        const val SUBSCRIPTION = "/data/local/tmp/kvn-test-subscription"
        const val SHELL_PACKAGE = "com.android.shell"
        const val PAUSE_MILLIS = 10_000L
    }
}
