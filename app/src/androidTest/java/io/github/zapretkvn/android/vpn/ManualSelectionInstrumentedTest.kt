package io.github.zapretkvn.android.vpn

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.diagnostics.VpnRuntimeMetrics
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.profiles.ProtocolOutboundBuilders
import io.github.zapretkvn.android.profiles.TlsSettings
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ручной выбор сервера при подключённом VPN идёт через selector без перезапуска:
 * ни новая попытка подключения, ни новое поколение, ни новый TUN, ни новый libbox.
 * Проверка ждёт дольше, чем любой отложенный перезапуск (в диагностике он стартовал
 * через 1,3-1,9 с после выбора).
 */
@RunWith(AndroidJUnit4::class)
class ManualSelectionInstrumentedTest {
    @Test
    fun manualSelectionDoesNotRestartVpn() = runBlocking<Unit> {
        withProfile { container, profileId, context ->
            val controller = container.vpnController
            val connected = connect(container, profileId)
            val tun = waitForVpnNetwork(context)
            val attempts = controller.diagnostics.value.recentConnectionAttempts.map { it.generation to it.trigger }
            val created = VpnRuntimeMetrics.libboxCreationCount()
            val generation = controller.currentGeneration()
            awaitSelected(container, "server-a")

            for (target in listOf("server-b", "server-c", "server-a")) {
                VpnTestHooks.succeedNextSwitchVerification()
                controller.selectOutbound(profileId, ConfigAnalyzer.MANAGED_SELECTOR_TAG, target)
                awaitSelected(container, target)
                withTimeout(10_000) { while (savedSelected(container, profileId) != target) delay(50) }
                delay(5_000)

                val state = controller.state.value
                val diag = describe(container)
                assertTrue("VPN left Connected after selecting $target: $diag", state is VpnConnectionState.Connected)
                assertEquals(
                    "connectedAt changed after selecting $target: $diag",
                    connected.connectedAtEpochMillis,
                    (state as VpnConnectionState.Connected).connectedAtEpochMillis,
                )
                assertEquals("generation changed after selecting $target: $diag", generation, controller.currentGeneration())
                assertEquals(
                    "connection attempts changed after selecting $target: $diag",
                    attempts,
                    controller.diagnostics.value.recentConnectionAttempts.map { it.generation to it.trigger },
                )
                assertEquals("TUN changed after selecting $target: $diag", tun, waitForVpnNetwork(context))
                assertEquals("libbox recreated after selecting $target: $diag", created, VpnRuntimeMetrics.libboxCreationCount())
                assertEquals(target, runtimeSelected(container))
            }
        }
    }

    /** Ручной выбор проходит ту же проверку связи, что failover: при отказе прежний сервер возвращается. */
    @Test
    fun manualSelectionThatFailsVerificationRollsBackWithoutRestart() = runBlocking<Unit> {
        withProfile { container, profileId, context ->
            val controller = container.vpnController
            val connected = connect(container, profileId)
            val tun = waitForVpnNetwork(context)
            val generation = controller.currentGeneration()
            val attempts = controller.diagnostics.value.recentConnectionAttempts.map { it.generation to it.trigger }
            awaitSelected(container, "server-a")
            controller.consumeMessage()

            VpnTestHooks.failNextHysteriaReplacement()
            controller.selectOutbound(profileId, ConfigAnalyzer.MANAGED_SELECTOR_TAG, "server-b")
            val message = withTimeoutOrNull(20_000) { controller.message.first { it != null } }
            assertTrue("No rollback message: ${describe(container)}", message?.contains("не прошёл проверку связи") == true)
            withTimeout(10_000) { while (runtimeSelected(container) != "server-a") delay(50) }
            delay(3_000)

            assertEquals("server-a", runtimeSelected(container))
            assertEquals("server-a", savedSelected(container, profileId))
            val state = controller.state.value
            assertTrue(describe(container), state is VpnConnectionState.Connected)
            assertEquals(connected.connectedAtEpochMillis, (state as VpnConnectionState.Connected).connectedAtEpochMillis)
            assertEquals(generation, controller.currentGeneration())
            assertEquals(attempts, controller.diagnostics.value.recentConnectionAttempts.map { it.generation to it.trigger })
            assertEquals(tun, waitForVpnNetwork(context))
            assertTrue(
                describe(container),
                controller.diagnostics.value.applicationLogs.any { "не прошёл проверку связи" in it.message },
            )

            // Следующий выбор без отказа проходит.
            VpnTestHooks.succeedNextSwitchVerification()
            controller.selectOutbound(profileId, ConfigAnalyzer.MANAGED_SELECTOR_TAG, "server-c")
            awaitSelected(container, "server-c")
            withTimeout(10_000) { while (savedSelected(container, profileId) != "server-c") delay(50) }
        }
    }

    /**
     * Подписка из трёх Hysteria2-серверов, как у пользователя. Нужен настоящий сервер Hysteria 2:
     * `-e hysteria2Server 10.0.2.2:44301` (пароль `test-password`, самоподписанный сертификат,
     * `hysteria server` на хосте эмулятора). Без аргумента тест пропускается.
     */
    @Test
    fun manualSelectionOnHysteria2SubscriptionDoesNotRestartVpn() = runBlocking<Unit> {
        val endpoint = InstrumentationRegistry.getArguments().getString("hysteria2Server")
        Assume.assumeTrue("hysteria2Server=host:port не задан", !endpoint.isNullOrBlank())
        val host = endpoint!!.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':').toInt()
        val servers = listOf("Alpha", "Bravo", "Charlie").map { name ->
            ProtocolOutboundBuilders.hysteria2(
                displayName = name,
                server = host,
                serverPort = port,
                password = "test-password",
                tls = TlsSettings(enabled = true, serverName = "gate.test", insecure = true),
            )
        }
        val tags = ManagedProfileFactory.stableTags(servers)
        withProfile(ManagedProfileFactory.subscription(servers), ProfileSource.Subscription) { container, profileId, context ->
            val controller = container.vpnController
            container.uiSettingsStore.setDnsMode(DnsMode.Android)
            container.uiSettingsStore.setProxyIpv4Only(true)
            container.appSelectionStore.replaceAllowlist(setOf("com.android.settings", context.packageName))
            val traffic = AtomicBoolean(true)
            val sent = AtomicInteger()
            val trafficThread = Thread {
                while (traffic.get()) {
                    runCatching {
                        (URL("https://example.com/").openConnection() as HttpURLConnection).run {
                            connectTimeout = 3_000
                            readTimeout = 3_000
                            inputStream.use { it.readBytes() }
                            sent.incrementAndGet()
                        }
                    }
                    Thread.sleep(300)
                }
            }.apply { isDaemon = true; start() }
            try {
            val connected = connect(container, profileId, fakeHealth = false)
            val tun = waitForVpnNetwork(context)
            val generation = controller.currentGeneration()
            val attempts = controller.diagnostics.value.recentConnectionAttempts.map { it.generation to it.trigger }
            awaitSelected(container, tags[0])
            for (target in listOf(tags[1], tags[2], tags[0])) {
                controller.selectOutbound(profileId, ConfigAnalyzer.MANAGED_SELECTOR_TAG, target)
                awaitSelected(container, target)
                delay(8_000)
                val state = controller.state.value
                val diag = describe(container)
                assertTrue("VPN left Connected after selecting $target: $diag", state is VpnConnectionState.Connected)
                assertEquals(connected.connectedAtEpochMillis, (state as VpnConnectionState.Connected).connectedAtEpochMillis)
                assertEquals("generation changed after $target: $diag", generation, controller.currentGeneration())
                assertEquals(
                    "attempts changed after $target: $diag",
                    attempts,
                    controller.diagnostics.value.recentConnectionAttempts.map { it.generation to it.trigger },
                )
                assertEquals(tun, waitForVpnNetwork(context))
            }
            } finally {
                traffic.set(false)
                container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
                assertTrue("No traffic crossed the VPN", sent.get() > 0)
            }
        }
    }

    private suspend fun withProfile(block: suspend (AppContainer, String, Context) -> Unit) =
        withProfile(THREE_SERVER_DIRECT_CONFIG, ProfileSource.RawJson, block)

    private suspend fun withProfile(
        json: String,
        source: ProfileSource,
        block: suspend (AppContainer, String, Context) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as ZapretApplication).container
        val packageName = context.packageName
        shell("appops set $packageName ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.profileStore.initialize()
        val profile = container.profileStore.create("Manual selection", json, source)
        try {
            VpnTestHooks.reset()
            block(container, profile.id, context)
        } finally {
            VpnTestHooks.reset()
            val controller = container.vpnController
            if (controller.state.value !is VpnConnectionState.Stopped) {
                controller.stop()
                withTimeoutOrNull(20_000) {
                    controller.state.first { it is VpnConnectionState.Stopped || it is VpnConnectionState.Error }
                }
            }
            withTimeoutOrNull(15_000) {
                while (!VpnRuntimeMetrics.snapshot().isIdle || vpnNetwork(context) != null) delay(50)
            }
            container.profileStore.delete(profile.id)
            shell("appops set $packageName ACTIVATE_VPN default")
        }
    }

    private suspend fun connect(
        container: AppContainer,
        profileId: String,
        fakeHealth: Boolean = true,
    ): VpnConnectionState.Connected {
        val controller = container.vpnController
        if (fakeHealth) VpnTestHooks.succeedNextHealthCheck()
        val before = controller.state.value
        controller.start(profileId)
        withTimeout(20_000) { controller.state.first { it != before } }
        val terminal = withTimeout(30_000) {
            controller.state.first { it is VpnConnectionState.Connected || it is VpnConnectionState.Error }
        }
        assertTrue("VPN failed: $terminal", terminal is VpnConnectionState.Connected)
        return terminal as VpnConnectionState.Connected
    }

    private suspend fun awaitSelected(container: AppContainer, tag: String) {
        val found = withTimeoutOrNull(20_000) {
            container.vpnController.selectorGroups.first { groups ->
                groups.any { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG && it.selected == tag }
            }
        }
        assertTrue("Selector did not publish $tag: ${describe(container)}", found != null)
    }

    private fun describe(container: AppContainer): String {
        val diagnostics = container.vpnController.diagnostics.value
        return "state=${container.vpnController.state.value} " +
            "attempts=${diagnostics.recentConnectionAttempts.map { "${it.generation}:${it.trigger}:${it.outcome}" }} " +
            "log=${diagnostics.applicationLogs.map { it.message }.takeLast(10)}"
    }

    private fun runtimeSelected(container: AppContainer): String? =
        container.vpnController.selectorGroups.value
            .firstOrNull { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG }?.selected

    private suspend fun savedSelected(container: AppContainer, profileId: String): String? =
        ConfigAnalyzer.selectorGroups(container.profileStore.read(profileId).json)
            .firstOrNull { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG }?.default

    private suspend fun waitForVpnNetwork(context: Context): Network = withTimeout(10_000) {
        while (true) {
            vpnNetwork(context)?.let { return@withTimeout it }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE")
        error("VPN network not found")
    }

    @Suppress("DEPRECATION")
    private fun vpnNetwork(context: Context): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.firstOrNull { network ->
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
        val THREE_SERVER_DIRECT_CONFIG = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                {"type":"direct","tag":"server-a"},
                {"type":"direct","tag":"server-b"},
                {"type":"direct","tag":"server-c"},
                {"type":"selector","tag":"zapret-proxy","outbounds":["server-a","server-b","server-c"],"default":"server-a","interrupt_exist_connections":true},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """.trimIndent()
    }
}
