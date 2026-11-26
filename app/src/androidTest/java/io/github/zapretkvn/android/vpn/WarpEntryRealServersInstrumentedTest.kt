package io.github.zapretkvn.android.vpn

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The WARP entry hop on the REAL servers of the vpnbot test person, through the real libbox service (owner decisions
 * 2026-11-25/26: the hop carries every protocol, tests run on real data only).
 *
 * The test person's file is pushed to `/data/local/tmp/kvn-test-subscription` (`adb push`); without it the tests skip.
 * The app's own importer turns the live subscriptions into profiles. Each test starts the real service with the hop on
 * for the `com.android.shell` user (an app cannot route its own package through its own VPN) and asks an echo service
 * which address the internet sees: it must be the address of OUR server, not the emulator's own, and the core's log
 * must show the connection to that server leaving through the WARP endpoint. Nothing is printed except counts.
 */
@RunWith(AndroidJUnit4::class)
class WarpEntryRealServersInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container: AppContainer = (context.applicationContext as ZapretApplication).container
    private val shippedAccountFile = File(context.filesDir, "warp-entry-profile.json")
    private var profile: ProfileMetadata? = null

    @Before
    fun allowVpn() {
        assumeTrue("no real test subscription on the device (adb push to $SUBSCRIPTION)", subscription() != null)
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        File(shippedAccountFile.path + ".edge").delete()
    }

    @After
    fun stopAndClean() {
        runBlocking {
            container.uiSettingsStore.setWarpEntryHop(false)
            container.vpnController.setDiagnosticsVisible(false)
            if (container.vpnController.state.value !is VpnConnectionState.Stopped) stopVpn()
            profile?.let { container.profileStore.delete(it.id) }
        }
        shell("appops set ${context.packageName} ACTIVATE_VPN default")
    }

    @Test
    fun aRealVlessServerAnswersThroughTheHop() = runBlocking {
        val servers = servers("vless_subscription_url")
        assertTrue("the test person has VLESS servers", servers.isNotEmpty())
        assertAnyAnswersThroughTheHop(servers.map { ManagedProfileFactory.subscription(listOf(it)) })
    }

    @Test
    fun aRealHysteria2ServerAnswersThroughTheHop() = runBlocking {
        val servers = servers("hysteria2_subscription_url")
        assertTrue("the test person has Hysteria2 servers", servers.isNotEmpty())
        assertAnyAnswersThroughTheHop(servers.map { ManagedProfileFactory.subscription(listOf(it)) })
    }

    @Test
    fun theRealAmneziaWgServerAnswersThroughTheHop() = runBlocking {
        val conf = JSONObject(subscription()!!).getString("awg_conf")
        val candidate = ImportParser.parse(conf, ProfileSource.Link) as ImportCandidate.WireGuard
        assertAnyAnswersThroughTheHop(listOf(candidate.json))
    }

    private fun servers(key: String) = (ImportParser.parse(
        fetch(JSONObject(subscription()!!).getString(key)),
        ProfileSource.Url,
    ) as ImportCandidate.Managed).servers

    /**
     * A control first: without the hop, the first server that answers from this device is the one that proves anything
     * (servers may be down, or unreachable from the emulator's network, for reasons that have nothing to do with the hop).
     * Then that very server must answer with the hop on, and the core's log must show the connection leaving through it.
     */
    private suspend fun assertAnyAnswersThroughTheHop(profiles: List<String>) {
        val echo = resolveEchoAddress()
        val direct = egressIp(echo)
        assertTrue("no direct internet to compare with", direct.isNotEmpty())
        container.appSelectionStore.replaceAllowlist(setOf(SHELL_PACKAGE))
        container.uiSettingsStore.setWarpEntryHop(false)
        var working: String? = null
        for (raw in profiles.take(5)) {
            if (answers(raw, echo, direct)) {
                working = raw
                break
            }
        }
        assumeTrue("no real server of this kind answers from this device even without the hop: unproven here", working != null)
        container.uiSettingsStore.setWarpEntryHop(true)
        container.vpnController.setDiagnosticsVisible(true)
        val through = answers(working!!, echo, direct)
        assertTrue("a server that answers directly did not answer through the hop${hopLog()}", through)
        assertTrue(
            "the connection to the server did not leave through the WARP endpoint${hopLog()}",
            coreMessages().any { "warp[kvn-warp-entry]" in it && "outbound" in it && "connection to" in it },
        )
    }

    /** Starts the service on this profile and reports whether the internet then sees an address other than [direct]. */
    private suspend fun answers(raw: String, echo: String, direct: String): Boolean {
        container.profileStore.initialize()
        profile = container.profileStore.create(name = "WARP real server", rawJson = raw, source = ProfileSource.RawJson)
        val created = profile
        try {
            if (!tryStart()) return false
            val seen = awaitEgressChange(echo, direct)
            stopVpn()
            return seen.isNotEmpty()
        } finally {
            if (container.vpnController.state.value !is VpnConnectionState.Stopped) stopVpn()
            created?.let { container.profileStore.delete(it.id) }
            profile = null
        }
    }

    private suspend fun tryStart(): Boolean {
        VpnTestHooks.succeedNextHealthCheck()
        val before = container.vpnController.state.value
        container.vpnController.start(checkNotNull(profile).id)
        val state = withTimeout(30_000) {
            container.vpnController.state.first {
                it != before && (it is VpnConnectionState.Connected || it is VpnConnectionState.Error)
            }
        }
        return state is VpnConnectionState.Connected
    }

    private suspend fun stopVpn() {
        container.vpnController.stop()
        withTimeout(20_000) { container.vpnController.state.first { it is VpnConnectionState.Stopped } }
        withTimeout(10_000) { while (hasVpnNetwork()) delay(50) }
    }

    private suspend fun awaitEgressChange(echo: String, direct: String): String {
        var through = ""
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline && (through.isEmpty() || through == direct)) {
            through = egressIp(echo)
            if (through.isEmpty() || through == direct) delay(2_000)
        }
        return if (through == direct) "" else through
    }

    private fun resolveEchoAddress(): String =
        InetAddress.getAllByName("ifconfig.me").first { it is Inet4Address }.hostAddress!!

    private fun egressIp(echo: String): String = shell(
        "sh -c (printf\${IFS}'GET\\040/ip\\040HTTP/1.1\\r\\nHost:\\040ifconfig.me\\r\\nUser-Agent:\\040curl/8.5.0\\r\\nAccept:\\040*/*\\r\\nConnection:\\040close\\r\\n\\r\\n';sleep\${IFS}4)" +
            "|nc\${IFS}-w\${IFS}8\${IFS}$echo\${IFS}80",
    ).trim().lines().lastOrNull().orEmpty().trim().takeIf { it.matches(Regex("[0-9a-fA-F:.]{7,45}")) }.orEmpty()

    private fun coreMessages(): List<String> = container.vpnController.diagnostics.value.coreLogs.map { it.message }

    /** Only the hop's own lines, with every address taken out: failure messages must not print servers. */
    private fun hopLog(): String = "\ncore log:\n" + coreMessages()
        .filter { "warp" in it.lowercase() && "uapi:" !in it && "routine:" !in it }
        .takeLast(15)
        .joinToString("\n") { it.replace(Regex("[0-9a-fA-F:.]{7,}|[\\w-]+(\\.[\\w-]+){2,}"), "<x>") }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "ZapretKVN")
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun subscription(): String? = shell("cat $SUBSCRIPTION").takeIf { it.startsWith("{") }

    @Suppress("DEPRECATION")
    private fun hasVpnNetwork(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    input.readBytes().toString(Charsets.UTF_8).trim()
                }
            }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val SUBSCRIPTION = "/data/local/tmp/kvn-test-subscription"
    }
}
