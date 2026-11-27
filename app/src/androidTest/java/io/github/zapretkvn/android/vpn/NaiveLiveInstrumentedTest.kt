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
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A REAL NaiveProxy key through the real libbox service and the stock `libnaive.so` (owner decisions 2026-11-25 and
 * 2026-11-27: tests run on real data, naive runs on the stock client). The canary key is pushed to
 * `/data/local/tmp/kvn-test-naive-key` (`adb push`); without it the test skips. The app's own importer turns the line into
 * a profile; the service starts for the `com.android.shell` user (an app cannot route its own package through its own VPN)
 * WITHOUT the health-check shortcut, so a connected state already means a real HTTPS request went through the key. Then an
 * echo service must see an address other than the emulator's own. Nothing is printed except counts.
 */
@RunWith(AndroidJUnit4::class)
class NaiveLiveInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container: AppContainer = (context.applicationContext as ZapretApplication).container
    private var profile: ProfileMetadata? = null

    @Before
    fun allowVpn() {
        assumeTrue("no real NaiveProxy key on the device (adb push to $KEY_FILE)", key() != null)
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }

    @After
    fun stopAndClean() {
        runBlocking {
            if (container.vpnController.state.value !is VpnConnectionState.Stopped) stopVpn()
            profile?.let { container.profileStore.delete(it.id) }
        }
        shell("appops set ${context.packageName} ACTIVATE_VPN default")
    }

    @Test
    fun aRealKeyBringsARealAnswerThroughTheStockClient() = runBlocking {
        val candidate = ImportParser.parse(key()!!, ProfileSource.Link) as ImportCandidate.Managed
        val raw = ManagedProfileFactory.subscription(candidate.servers)
        val echo = resolveEchoAddress()
        val direct = egressIp(echo)
        assertTrue("no direct internet to compare with", direct.isNotEmpty())
        container.appSelectionStore.replaceAllowlist(setOf(SHELL_PACKAGE))
        container.profileStore.initialize()
        profile = container.profileStore.create(name = "NaiveProxy real key", rawJson = raw, source = ProfileSource.RawJson)
        container.vpnController.setDiagnosticsVisible(true)
        val before = container.vpnController.state.value
        container.vpnController.start(checkNotNull(profile).id)
        val state = withTimeout(120_000) {
            container.vpnController.state.first {
                it != before && (it is VpnConnectionState.Connected || it is VpnConnectionState.Error)
            }
        }
        assertTrue("the service did not connect through the naive key: $state${naiveLog()}", state is VpnConnectionState.Connected)
        var through = ""
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline && (through.isEmpty() || through == direct)) {
            through = egressIp(echo)
            if (through.isEmpty() || through == direct) delay(2_000)
        }
        assertTrue("the echo service still sees the device's own address${naiveLog()}", through.isNotEmpty() && through != direct)
    }

    private suspend fun stopVpn() {
        container.vpnController.stop()
        withTimeout(20_000) { container.vpnController.state.first { it is VpnConnectionState.Stopped } }
        withTimeout(10_000) { while (hasVpnNetwork()) delay(50) }
    }

    private fun resolveEchoAddress(): String =
        InetAddress.getAllByName("ifconfig.me").first { it is Inet4Address }.hostAddress!!

    private fun egressIp(echo: String): String = shell(
        "sh -c (printf\${IFS}'GET\\040/ip\\040HTTP/1.1\\r\\nHost:\\040ifconfig.me\\r\\nUser-Agent:\\040curl/8.5.0\\r\\nAccept:\\040*/*\\r\\nConnection:\\040close\\r\\n\\r\\n';sleep\${IFS}4)" +
            "|nc\${IFS}-w\${IFS}8\${IFS}$echo\${IFS}80",
    ).trim().lines().lastOrNull().orEmpty().trim().takeIf { it.matches(Regex("[0-9a-fA-F:.]{7,45}")) }.orEmpty()

    /** Only the stock client's own lines, with every address and the key taken out: failure messages must not print servers. */
    private fun naiveLog(): String = "\ncore log:\n" + container.vpnController.diagnostics.value.coreLogs.map { it.message }
        .filter { "naive" in it.lowercase() }
        .takeLast(15)
        .joinToString("\n") { it.replace(Regex("[0-9a-fA-F:.]{7,}|[\\w-]+(\\.[\\w-]+){2,}"), "<x>") }

    private fun key(): String? = shell("cat $KEY_FILE").takeIf { it.startsWith("naive+https://") }

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
        const val KEY_FILE = "/data/local/tmp/kvn-test-naive-key"
    }
}
