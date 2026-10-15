package io.github.zapretkvn.android.vpn

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.diagnostics.VpnRuntimeMetrics
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.profiles.ProfileSource
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Тот же сценарий, что в [ManualSelectionInstrumentedTest], но нажатием на строку на экране «Серверы». */
class ManualSelectionUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container
    private val packageName
        get() = composeRule.activity.packageName

    @Before
    fun prepare() = runBlocking<Unit> {
        shell("appops set $packageName ACTIVATE_VPN allow")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shell("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        VpnTestHooks.reset()
        container.profileStore.initialize()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
    }

    @After
    fun cleanup() = runBlocking<Unit> {
        val controller = container.vpnController
        if (controller.state.value !is VpnConnectionState.Stopped) {
            controller.stop()
            withTimeout(20_000) { controller.state.first { it is VpnConnectionState.Stopped } }
        }
        withTimeout(10_000) { while (!VpnRuntimeMetrics.snapshot().isIdle) delay(25) }
        VpnTestHooks.reset()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        container.uiSettingsStore.setActiveProfile(null)
        container.appSelectionStore.replaceAllowlist(emptySet())
        shell("appops set $packageName ACTIVATE_VPN default")
    }

    @Test
    fun tappingServerRowDoesNotRestartVpn() = runBlocking<Unit> {
        val profile = container.profileStore.create("UI selection", CONFIG, ProfileSource.RawJson)
        container.uiSettingsStore.setActiveProfile(profile.id)
        val controller = container.vpnController
        VpnTestHooks.succeedNextHealthCheck()
        controller.start(profile.id)
        val connected = withTimeout(40_000) { controller.state.first { it is VpnConnectionState.Connected } }
            as VpnConnectionState.Connected
        val generation = controller.currentGeneration()
        val attempts = controller.diagnostics.value.recentConnectionAttempts.map { it.generation to it.trigger }

        composeRule.onNode(hasText("Серверы") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        for (target in listOf("server-b", "server-c", "server-a")) {
            composeRule.waitUntil(20_000) {
                runCatching { composeRule.onNodeWithText(target).fetchSemanticsNode() }.isSuccess
            }
            composeRule.onNodeWithText(target).performClick()
            withTimeout(20_000) {
                controller.selectorGroups.first { groups ->
                    groups.any { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG && it.selected == target }
                }
            }
            delay(5_000)
            val state = controller.state.value
            val log = controller.diagnostics.value.applicationLogs.map { it.message }.takeLast(8)
            assertTrue("left Connected after $target: $state attempts=$attempts log=$log", state is VpnConnectionState.Connected)
            assertEquals(connected.connectedAtEpochMillis, (state as VpnConnectionState.Connected).connectedAtEpochMillis)
            assertEquals(generation, controller.currentGeneration())
            assertEquals(
                attempts,
                controller.diagnostics.value.recentConnectionAttempts.map { it.generation to it.trigger },
            )
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8).trim() }
            }

    private companion object {
        val CONFIG = """
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
