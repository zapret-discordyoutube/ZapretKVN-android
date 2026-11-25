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
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The WARP entry hop through the REAL libbox service (owner decision 2026-11-25).
 *
 * Needs a device or emulator with internet access. Cloudflare's WARP API is used for real: each test registers a
 * throwaway device, which Cloudflare removes by itself.
 *
 * Where a test needs traffic to cross the tunnel, WARP itself is the exit of a test-only profile (shipped configs never
 * use WARP as an exit) and the `com.android.shell` user is the app whose traffic enters the VPN: an app cannot route its
 * own package through its own VPN. What a real server behind the hop adds is covered by the unit tests of the config pass
 * and, on the host, by the chain test with a SOCKS stand-in for the hop; the rest needs a public node.
 */
@RunWith(AndroidJUnit4::class)
class WarpEntryHopInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container: AppContainer = (context.applicationContext as ZapretApplication).container
    private val accountFile = File(context.filesDir, "warp-instrumented-account.json")
    private val shippedAccountFile = File(context.filesDir, "warp-entry-profile.json")
    private var profile: ProfileMetadata? = null

    @Before
    fun allowVpn() {
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        accountFile.delete()
        File(accountFile.path + ".edge").delete()
        shippedAccountFile.delete()
    }

    @After
    fun stopAndClean() {
        runBlocking {
            container.uiSettingsStore.setWarpEntryHop(false)
            container.vpnController.setDiagnosticsVisible(false)
            if (container.vpnController.state.value !is VpnConnectionState.Stopped) stopVpn()
            profile?.let { container.profileStore.delete(it.id) }
        }
        accountFile.delete()
        File(accountFile.path + ".edge").delete()
        shippedAccountFile.delete()
        shell("appops set ${context.packageName} ACTIVATE_VPN default")
    }

    /**
     * The shipped hop: with the setting on and a profile whose only server is unreachable on purpose, the real core must
     * accept the hop endpoint (`mtu`, `profile.cache_path`, the registration outbound), start, and register an account
     * into the app's private files without any byte going to that server.
     */
    @Test
    fun hopEndpointStartsInsideTheRealCoreAndKeepsAnAccountFile() = runBlocking {
        container.uiSettingsStore.setWarpEntryHop(true)
        createProfile(UNREACHABLE_SERVER_PROFILE)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        startVpn()
        withTimeout(60_000) { while (!shippedAccountFile.isFile || shippedAccountFile.length() == 0L) delay(250) }
        val account = JSONObject(shippedAccountFile.readText())
        // The app registers the account itself (lowercase keys); the core's own saves use the Go field name (`Peers`).
        assertTrue(
            "the account file must hold the key and the peers",
            account.has("private_key") && (account.has("peers") || account.has("Peers")),
        )
    }

    /** WARP is the exit of a test profile: the shell user's egress address must change. */
    @Test
    fun warpTunnelCarriesTheAppsTrafficInsideTheRealService() = runBlocking {
        val echo = resolveEchoAddress()
        val direct = egressIp(echo)
        assertTrue("no direct internet to compare with", direct.isNotEmpty())
        createProfile(exitProfile())
        container.appSelectionStore.replaceAllowlist(setOf(SHELL_PACKAGE))
        container.vpnController.setDiagnosticsVisible(true)
        startVpn()
        val through = awaitEgressChange(echo, direct)
        assertTrue("traffic did not leave through WARP: direct=$direct${coreLog()}", through.isNotEmpty())
    }

    /** A restart reuses the account from its file (no new registration) and the tunnel carries traffic again. */
    @Test
    fun restartReusesTheCachedAccountAndCarriesTraffic() = runBlocking {
        val echo = resolveEchoAddress()
        val direct = egressIp(echo)
        assertTrue("no direct internet to compare with", direct.isNotEmpty())
        createProfile(exitProfile())
        container.appSelectionStore.replaceAllowlist(setOf(SHELL_PACKAGE))
        startVpn()
        awaitAccountFile()
        val registered = accountFile.readText()
        stopVpn()
        container.vpnController.setDiagnosticsVisible(true)
        startVpn()
        val through = awaitEgressChange(echo, direct)
        assertTrue("the restart did not carry traffic: direct=$direct${coreLog()}", through.isNotEmpty())
        assertTrue("the restart must reuse the account, not register a new one", accountFile.readText() == registered)
    }

    /**
     * Start-up recovery (core patch 0023): after a first start registered the account, its assigned address is replaced
     * by an unreachable one (TEST-NET), so the account's own candidates can never answer. The restart must abandon them
     * and bring the tunnel up through the fixed fallback address.
     */
    @Test
    fun recoveryRotatesPastAnUnreachableAssignedAddress() = runBlocking {
        val echo = resolveEchoAddress()
        val direct = egressIp(echo)
        assertTrue("no direct internet to compare with", direct.isNotEmpty())
        createProfile(exitProfile())
        container.appSelectionStore.replaceAllowlist(setOf(SHELL_PACKAGE))
        startVpn()
        awaitAccountFile()
        stopVpn()
        blockAssignedAddress()
        container.vpnController.setDiagnosticsVisible(true)
        startVpn()
        val through = awaitEgressChange(echo, direct)
        assertTrue("recovery did not bring the tunnel up: direct=$direct${coreLog()}", through.isNotEmpty())
        assertTrue(
            "the unreachable candidates must have been abandoned${coreLog()}",
            coreMessages().any { "trying the next address" in it },
        )
    }

    /**
     * Candidates and edge memory (core patch 0025): with the account's own address a black hole the tunnel comes up
     * through another WARP edge, the edge that answered is kept next to the account file, and the next start tries it
     * first and does not walk the list again.
     */
    @Test
    fun recoveryRemembersTheEdgeThatAnsweredAndUsesItFirst() = runBlocking {
        val echo = resolveEchoAddress()
        val direct = egressIp(echo)
        assertTrue("no direct internet to compare with", direct.isNotEmpty())
        createProfile(exitProfile())
        container.appSelectionStore.replaceAllowlist(setOf(SHELL_PACKAGE))
        startVpn()
        awaitAccountFile()
        stopVpn()
        blockAssignedAddress()
        container.vpnController.setDiagnosticsVisible(true)
        startVpn()
        assertTrue("no tunnel through another edge: direct=$direct${coreLog()}", awaitEgressChange(echo, direct).isNotEmpty())
        val edge = File(accountFile.path + ".edge")
        withTimeout(30_000) { while (!edge.isFile || edge.length() == 0L) delay(250) }
        stopVpn()
        startVpn()
        assertTrue("the restart did not carry traffic: direct=$direct${coreLog()}", awaitEgressChange(echo, direct).isNotEmpty())
        assertTrue(
            "the remembered edge must be tried first${coreLog()}",
            coreMessages().any { "trying the edge that answered last time first" in it },
        )
    }

    /** The account's assigned address becomes a TEST-NET black hole; a leftover remembered edge is forgotten first. */
    private fun blockAssignedAddress() {
        File(accountFile.path + ".edge").delete()
        val account = JSONObject(accountFile.readText())
        val key = if (account.has("Peers")) "Peers" else "peers"
        account.getJSONArray(key).getJSONObject(0).getJSONObject("endpoint").put("v4", "192.0.2.1:0")
        accountFile.writeText(account.toString())
    }

    private suspend fun createProfile(rawJson: String) {
        container.profileStore.initialize()
        profile = container.profileStore.create(name = "WARP instrumented", rawJson = rawJson, source = ProfileSource.RawJson)
    }

    private suspend fun startVpn() {
        VpnTestHooks.succeedNextHealthCheck()
        val before = container.vpnController.state.value
        container.vpnController.start(checkNotNull(profile).id)
        val state = withTimeout(30_000) {
            container.vpnController.state.first {
                it != before && (it is VpnConnectionState.Connected || it is VpnConnectionState.Error)
            }
        }
        assertTrue("the VPN did not start: $state", state is VpnConnectionState.Connected)
    }

    private suspend fun stopVpn() {
        container.vpnController.stop()
        withTimeout(20_000) { container.vpnController.state.first { it is VpnConnectionState.Stopped } }
        withTimeout(10_000) { while (hasVpnNetwork()) delay(50) }
    }

    private suspend fun awaitAccountFile() =
        withTimeout(60_000) { while (!accountFile.isFile || accountFile.length() == 0L) delay(250) }

    /** A test-only profile that sends everything the shell user does into a WARP endpoint (the exit). */
    private fun exitProfile(): String = EXIT_PROFILE.replace("ACCOUNT_PATH", accountFile.absolutePath)

    /** Polls the egress address until it differs from [direct]; empty when it never does within a minute. */
    private suspend fun awaitEgressChange(echo: String, direct: String): String {
        var through = ""
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && (through.isEmpty() || through == direct)) {
            through = egressIp(echo)
            if (through.isEmpty() || through == direct) delay(2_000)
        }
        return if (through == direct) "" else through
    }

    /** Resolved BEFORE the VPN starts: a raw test profile has no DNS handling inside the tunnel. */
    private fun resolveEchoAddress(): String =
        InetAddress.getAllByName("ifconfig.me").first { it is Inet4Address }.hostAddress!!

    /**
     * The address the internet sees for the shell user: a plain HTTP request to the echo service, empty without an answer.
     *
     * `executeShellCommand` splits on whitespace and runs no shell, so the whole pipe is one `sh -c` token without
     * spaces (`${IFS}` and printf's octal escapes stand in for them). The client keeps the connection open for a few
     * seconds: through sing-box a client that half-closes right after its request does not get the reply, and real
     * apps do not half-close.
     */
    private fun egressIp(echo: String): String = shell(
        "sh -c (printf\${IFS}'GET\\040/ip\\040HTTP/1.1\\r\\nHost:\\040ifconfig.me\\r\\nUser-Agent:\\040curl/8.5.0\\r\\nAccept:\\040*/*\\r\\nConnection:\\040close\\r\\n\\r\\n';sleep\${IFS}4)" +
            "|nc\${IFS}-w\${IFS}8\${IFS}$echo\${IFS}80",
    ).trim().lines().lastOrNull().orEmpty().trim().takeIf { it.matches(Regex("[0-9a-fA-F:.]{7,45}")) }.orEmpty()

    private fun coreMessages(): List<String> = container.vpnController.diagnostics.value.coreLogs.map { it.message }

    /** The core's WARP lines (handshakes, recovery) without the per-connection and per-worker noise, for failure messages. */
    private fun coreLog(): String = "\ncore log:\n" + coreMessages()
        .filter { "warp" in it.lowercase() && "connection" !in it && "uapi:" !in it && "routine:" !in it }
        .takeLast(30).joinToString("\n")

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

        /** WARP as the exit, registered through its own direct outbound (plain Go HTTP has no DNS on Android). */
        val EXIT_PROFILE = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                {"type":"direct","tag":"api"},
                {"type":"direct","tag":"direct"}
              ],
              "endpoints":[{"type":"warp","tag":"test-warp","profile":{"cache_path":"ACCOUNT_PATH","detour":"api"}}],
              "route":{"auto_detect_interface":true,"final":"test-warp"}
            }
        """.trimIndent()

        /** The only server is a SOCKS outbound to a closed loopback port: the hop is built around it, nothing is sent. */
        val UNREACHABLE_SERVER_PROFILE = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                {"type":"socks","tag":"server-a","server":"127.0.0.1","server_port":1},
                {"type":"selector","tag":"zapret-proxy","outbounds":["server-a"],"default":"server-a","interrupt_exist_connections":true},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """.trimIndent()
    }
}
