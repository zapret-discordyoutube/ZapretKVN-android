package io.github.zapretkvn.android.vpn

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.diagnostics.VpnRuntimeMetrics
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.ui.ThemeMode
import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class Gate6EndToEndInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container
    private val packageName
        get() = composeRule.activity.packageName
    private var preflightServer: ServerSocket? = null

    @Before
    fun prepare() = runBlocking {
        allowVpn()
        VpnTestHooks.reset()
        container.vpnController.setHomeVisible(false)
        container.vpnController.setDiagnosticsVisible(false)
        if (container.vpnController.state.value !is VpnConnectionState.Stopped) {
            container.vpnController.stop()
            withTimeout(20_000) {
                container.vpnController.state.first { it is VpnConnectionState.Stopped }
            }
        }
        container.profileStore.initialize()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        container.uiSettingsStore.setActiveProfile(null)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.uiSettingsStore.setThemeMode(ThemeMode.System)
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.diagnosticExporter.cleanupStaleFiles()
        preflightServer = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    }

    @After
    fun cleanup() = runBlocking {
        container.vpnController.setHomeVisible(false)
        container.vpnController.setDiagnosticsVisible(false)
        if (container.vpnController.state.value !is VpnConnectionState.Stopped) {
            container.vpnController.stop()
            withTimeout(20_000) {
                container.vpnController.state.first { it is VpnConnectionState.Stopped }
            }
        }
        withTimeout(10_000) {
            while (!VpnRuntimeMetrics.snapshot().isIdle) delay(25)
        }
        VpnTestHooks.reset()
        preflightServer?.close()
        preflightServer = null
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        container.uiSettingsStore.setActiveProfile(null)
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.diagnosticExporter.cleanupStaleFiles()
        denyVpn()
    }

    @Test
    fun shareLinkToConnectedDiagnosticsNeedsNoRawJsonAndSurvivesRotation() = runBlocking {
        val credentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:gate-password".toByteArray())
        val shareLink = "ss://$credentials@127.0.0.1:${checkNotNull(preflightServer).localPort}#Gate%20SS"
        composeRule.runOnUiThread {
            val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("share link", shareLink))
        }

        openTab("Профили")
        composeRule.onNodeWithText("Буфер").performClick()
        composeRule.waitUntil(20_000) {
            runCatching { composeRule.onNodeWithText("Предпросмотр импорта").fetchSemanticsNode() }.isSuccess
        }
        composeRule.onNodeWithText("Серверов: 1").assertExists()
        composeRule.onNodeWithText("Новый профиль").performClick()
        composeRule.waitUntil(20_000) { container.profileStore.profiles.value.size == 1 }
        composeRule.onNodeWithText("Профиль готов").assertExists()
        composeRule.onNodeWithText("Выбрать приложения").performClick()

        composeRule.onNodeWithTag("show-system-apps").performClick()
        composeRule.onNodeWithTag("app-search").performTextInput(SETTINGS_PACKAGE)
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithTag("app-row-$SETTINGS_PACKAGE")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("app-row-$SETTINGS_PACKAGE").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking {
                SETTINGS_PACKAGE in container.appSelectionStore.selection.first().allowedPackages
            }
        }
        composeRule.onNodeWithContentDescription("Назад").performClick()

        openTab("Главная")
        composeRule.onNodeWithText("Gate SS").assertExists()
        composeRule.onNodeWithText("Подключить").assertExists()
        assertEquals(null, container.vpnController.permissionIntent())
        VpnTestHooks.succeedNextHealthCheck()
        composeRule.onNodeWithText("Подключить").performClick()
        val connected = withTimeout(30_000) {
            container.vpnController.state.first {
                it is VpnConnectionState.Connected || it is VpnConnectionState.Error
            }
        }
        assertTrue("VPN did not connect: $connected", connected is VpnConnectionState.Connected)
        connected as VpnConnectionState.Connected
        composeRule.onNodeWithText("VPN подключён").assertExists()

        val createdBeforeRotation = VpnRuntimeMetrics.libboxCreationCount()
        val snapshotBeforeRotation = VpnRuntimeMetrics.snapshot()
        val activityBeforeRotation = composeRule.activity.hashCode()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            container.vpnController.state.value is VpnConnectionState.Connected
        }
        val afterRotation = container.vpnController.state.value as VpnConnectionState.Connected
        assertEquals(connected.connectedAtEpochMillis, afterRotation.connectedAtEpochMillis)
        assertEquals(createdBeforeRotation, VpnRuntimeMetrics.libboxCreationCount())
        assertEquals(1, VpnRuntimeMetrics.snapshot().activeSessions)
        assertEquals(1, VpnRuntimeMetrics.snapshot().activeLibboxInstances)
        assertEquals(snapshotBeforeRotation.activeTunDescriptors, VpnRuntimeMetrics.snapshot().activeTunDescriptors)
        assertNotEquals(activityBeforeRotation, composeRule.activity.hashCode())

        openTab("Настройки")
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Диагностика"))
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Профиль сохранён. Подключение не запускалось.")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription(
            "Диагностика. Состояние VPN, версии и обслуживание DNS",
        ).performClick()
        composeRule.onNodeWithText("Подключено: Gate SS").assertExists()
        composeRule.onNodeWithText("Среда").assertExists()
        composeRule.onNodeWithTag("export-diagnostics").assertExists()
        composeRule.onNodeWithText("Экспортировать диагностику").assertIsDisplayed()
        composeRule.waitUntil(10_000) {
            container.vpnController.diagnosticsVisible.value &&
                VpnRuntimeMetrics.snapshot().activeLogClients == 1 &&
                VpnRuntimeMetrics.snapshot().activeStatusClients == 0
        }
    }

    private fun openTab(label: String) {
        composeRule.onNode(hasText(label) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }

    private fun allowVpn() {
        shell("appops set $packageName ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }

    private fun denyVpn() {
        shell("appops set $packageName ACTIVATE_VPN default")
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
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
