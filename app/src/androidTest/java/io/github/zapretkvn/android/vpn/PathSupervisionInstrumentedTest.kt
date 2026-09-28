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
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.diagnostics.VpnRuntimeMetrics
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Путь «подсказка ядра → проба сервера → переключение» на устройстве: два
 * SOCKS-сервера группы, отказ подменён хуками, селектор настоящий.
 */
@RunWith(AndroidJUnit4::class)
class PathSupervisionInstrumentedTest {
    @Test
    fun aliveProbeIsFalseAlarmAndKeepsServer() = runBlocking {
        withTwoProxyProfile { container, profileId ->
            VpnTestHooks.reportNextCoreHint(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT)
            VpnTestHooks.forceNextLivenessProbe(alive = true)
            connect(container, profileId)
            awaitTimeline(container, "ложная тревога")
            assertEquals(describe(container, profileId), "server-a", runtimeSelected(container))
            assertEquals(describe(container, profileId), "server-a", savedSelected(container, profileId))
            assertTrue(container.vpnController.state.value is VpnConnectionState.Connected)
        }
    }

    @Test
    fun deadProbeSwitchesExactlyOnceWithoutCoreRestart() = runBlocking {
        withTwoProxyProfile { container, profileId ->
            VpnTestHooks.reportNextCoreHint(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT)
            VpnTestHooks.forceNextLivenessProbe(alive = false)
            VpnTestHooks.succeedNextSwitchVerification()
            val connected = connect(container, profileId)
            val created = VpnRuntimeMetrics.libboxCreationCount()
            withTimeout(20_000) {
                container.vpnController.selectorGroups.first { groups ->
                    groups.any { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG && it.selected == "server-b" }
                }
            }
            withTimeout(10_000) { while (savedSelected(container, profileId) != "server-b") delay(50) }
            val state = container.vpnController.state.value
            assertTrue(state is VpnConnectionState.Connected)
            assertEquals(connected.connectedAtEpochMillis, (state as VpnConnectionState.Connected).connectedAtEpochMillis)
            assertEquals(created, VpnRuntimeMetrics.libboxCreationCount())
            assertEquals(1, VpnRuntimeMetrics.snapshot().activeSessions)
        }
    }

    @Test
    fun failedReplacementKeepsOriginalServerAndTunnel() = runBlocking {
        withTwoProxyProfile { container, profileId ->
            VpnTestHooks.reportNextCoreHint(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT)
            VpnTestHooks.forceNextLivenessProbe(alive = false)
            VpnTestHooks.failNextHysteriaReplacement()
            connect(container, profileId)
            awaitTimeline(container, "тоже не прошёл проверку")
            val restored = withTimeoutOrNull(10_000) {
                while (runtimeSelected(container) != "server-a") delay(50)
                true
            }
            assertEquals(describe(container, profileId), true, restored)
            assertEquals(describe(container, profileId), "server-a", savedSelected(container, profileId))
            assertTrue(container.vpnController.state.value is VpnConnectionState.Connected)
        }
    }

    private suspend fun withTwoProxyProfile(block: suspend (AppContainer, String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as ZapretApplication).container
        allowVpn(context.packageName)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        GateEchoServer().use { echo ->
            GateSocksServer(echo.reachableAddress, echo.port).use { socks ->
                container.profileStore.initialize()
                val profile = container.profileStore.create(
                    "Path supervision",
                    twoProxyConfig(echo.reachableAddress, socks.port),
                    ProfileSource.RawJson,
                )
                try {
                    VpnTestHooks.reset()
                    block(container, profile.id)
                } finally {
                    VpnTestHooks.reset()
                    stopIfNeeded(container, context)
                    container.profileStore.delete(profile.id)
                }
            }
        }
    }

    private suspend fun connect(container: AppContainer, profileId: String): VpnConnectionState.Connected {
        val controller = container.vpnController
        VpnTestHooks.succeedNextHealthCheck()
        val before = controller.state.value
        controller.start(profileId)
        withTimeout(20_000) { controller.state.first { it != before } }
        val terminal = withTimeout(30_000) {
            controller.state.first { it is VpnConnectionState.Connected || it is VpnConnectionState.Error }
        }
        assertTrue("VPN failed: $terminal", terminal is VpnConnectionState.Connected)
        return terminal as VpnConnectionState.Connected
    }

    private suspend fun awaitTimeline(container: AppContainer, fragment: String) {
        val found = withTimeoutOrNull(20_000) {
            while (container.vpnController.diagnostics.value.applicationLogs.none { fragment in it.message }) delay(50)
            true
        }
        assertEquals(
            "No timeline line with '$fragment': " +
                container.vpnController.diagnostics.value.applicationLogs.map { it.message },
            true,
            found,
        )
    }

    private suspend fun describe(container: AppContainer, profileId: String): String =
        "groups=${container.vpnController.selectorGroups.value.map { it.tag to it.selected }} " +
            "saved=${savedSelected(container, profileId)} " +
            "log=${container.vpnController.diagnostics.value.applicationLogs.map { it.message }.takeLast(12)}"

    private fun runtimeSelected(container: AppContainer): String? =
        container.vpnController.selectorGroups.value.firstOrNull { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG }?.selected

    private suspend fun savedSelected(container: AppContainer, profileId: String): String? =
        ConfigAnalyzer.selectorGroups(container.profileStore.read(profileId).json)
            .firstOrNull { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG }?.default

    private suspend fun stopIfNeeded(container: AppContainer, context: Context) {
        val controller = container.vpnController
        if (controller.state.value !is VpnConnectionState.Stopped) {
            controller.stop()
            withTimeoutOrNull(15_000) {
                controller.state.first { it is VpnConnectionState.Stopped || it is VpnConnectionState.Error }
            }
        }
        val idle = withTimeoutOrNull(15_000) {
            while (!VpnRuntimeMetrics.snapshot().isIdle || hasVpnNetwork(context)) delay(50)
            true
        }
        assertEquals("Cleanup failed: ${VpnRuntimeMetrics.snapshot()}", true, idle)
    }

    private fun hasVpnNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun allowVpn(packageName: String) {
        shell("appops set $packageName ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
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

    private fun twoProxyConfig(address: String, port: Int): String = """
        {
          "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
          "outbounds":[
            {"type":"socks","tag":"server-a","server":"$address","server_port":$port,"version":"5"},
            {"type":"socks","tag":"server-b","server":"$address","server_port":$port,"version":"5"},
            {"type":"selector","tag":"${ConfigAnalyzer.MANAGED_SELECTOR_TAG}","outbounds":["server-a","server-b"],"default":"server-a","interrupt_exist_connections":true},
            {"type":"direct","tag":"direct"}
          ],
          "route":{"auto_detect_interface":true,"final":"${ConfigAnalyzer.MANAGED_SELECTOR_TAG}"}
        }
    """.trimIndent()
}
