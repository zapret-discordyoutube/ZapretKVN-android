package io.github.zapretkvn.android.profiles

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.DnsOverride
import io.github.zapretkvn.android.hardening.TunMtuMode
import io.github.zapretkvn.android.ui.NetworkTransportSetting
import io.github.zapretkvn.android.updates.UpdateChannel
import io.github.zapretkvn.android.vpn.VpnConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container

    @Before
    fun clearProfiles() = runBlocking {
        resetDiagnostics()
        container.profileStore.initialize()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        container.uiSettingsStore.setActiveProfile(null)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.uiSettingsStore.setDnsOverride(
            DnsOverride.DEFAULT_HOSTNAME,
            DnsOverride.DEFAULT_IPV4_ADDRESS,
        )
        container.uiSettingsStore.setDnsOverrideEnabled(true)
        container.uiSettingsStore.setUpdateChannel(UpdateChannel.Stable)
        container.uiSettingsStore.setVpnHidingBlockLocalEndpoints(true)
        container.uiSettingsStore.setVpnHidingNeutralSessionName(false)
        container.uiSettingsStore.setVpnHidingTunMtuMode(TunMtuMode.CoreDefault)
        resetNetworkAutomation()
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
    }

    @After
    fun cleanProfiles() = runBlocking {
        resetDiagnostics()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        container.uiSettingsStore.setActiveProfile(null)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.uiSettingsStore.setDnsOverride(
            DnsOverride.DEFAULT_HOSTNAME,
            DnsOverride.DEFAULT_IPV4_ADDRESS,
        )
        container.uiSettingsStore.setDnsOverrideEnabled(true)
        container.uiSettingsStore.setUpdateChannel(UpdateChannel.Stable)
        container.uiSettingsStore.setVpnHidingBlockLocalEndpoints(true)
        container.uiSettingsStore.setVpnHidingNeutralSessionName(false)
        container.uiSettingsStore.setVpnHidingTunMtuMode(TunMtuMode.CoreDefault)
        resetNetworkAutomation()
    }

    @Test
    fun userImportsValidatesEditsSavesAndReopensProfile() {
        composeRule.runOnUiThread {
            val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("profile", VALID_DIRECT))
        }

        composeRule.onNode(hasText("Профили") and hasClickAction()).performClick()
        composeRule.onNodeWithText("Буфер").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onNodeWithText("Предпросмотр импорта").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithText("Предпросмотр импорта").assertExists()
        composeRule.onNodeWithText("Новый профиль").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            container.profileStore.profiles.value.isNotEmpty()
        }
        composeRule.onNodeWithText("Профиль готов").assertDoesNotExist()
        composeRule.onNodeWithText("Профиль из буфера").assertExists()
        composeRule.onNodeWithText("Буфер обмена").assertExists()
        composeRule.onNodeWithText("Обновлено:", substring = true).assertExists()
        composeRule.onNodeWithText("JSON").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2
        }
        composeRule.onAllNodes(hasSetTextAction())[1].performTextReplacement(UPDATED_DIRECT)
        composeRule.onNodeWithText("Validate").performClick()
        composeRule.onNodeWithText("Конфигурация корректна.").assertExists()
        composeRule.onNodeWithText("Сохранить").performClick()
        val profileId = container.profileStore.profiles.value.single().id
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runBlocking { container.profileStore.read(profileId).json == UPDATED_DIRECT }
        }
        composeRule.onNodeWithContentDescription("Назад").performClick()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("Профили") and hasClickAction()).performClick()
        composeRule.onNodeWithText("Профиль из буфера").assertExists()
        composeRule.onNodeWithText("JSON").performClick()
        composeRule.onAllNodes(hasSetTextAction())[1].assertTextContains(UPDATED_DIRECT)
    }

    @Test
    fun userSwitchesServerInsideSubscriptionProfileWithoutEditingJson() {
        composeRule.runOnUiThread {
            val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("subscription", SUBSCRIPTION_LINKS))
        }

        composeRule.onNode(hasText("Профили") and hasClickAction()).performClick()
        composeRule.onNodeWithText("Буфер").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onNodeWithText("Предпросмотр импорта").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithText("Один профиль-группа").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            container.profileStore.profiles.value.isNotEmpty()
        }
        val profileId = container.profileStore.profiles.value.single().id

        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runCatching { composeRule.onNodeWithText("Серверы").fetchSemanticsNode() }.isSuccess
        }
        composeRule.onNodeWithText("Серверов: 2", substring = true).assertExists()
        composeRule.onNodeWithText("Серверы").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onNodeWithTag("profile-servers-sheet").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithText("second").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runBlocking {
                ConfigAnalyzer.selectorGroups(container.profileStore.read(profileId).json)
                    .single()
                    .default == "second"
            }
        }
    }

    @Test
    fun userSplitsSubscriptionIntoOneProfilePerServer() {
        composeRule.runOnUiThread {
            val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("subscription", SUBSCRIPTION_LINKS))
        }

        composeRule.onNode(hasText("Профили") and hasClickAction()).performClick()
        composeRule.onNodeWithText("Буфер").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onNodeWithText("Предпросмотр импорта").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithTag("import-split-profiles").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            container.profileStore.profiles.value.size == 2
        }

        assertEquals(
            listOf("first", "second"),
            container.profileStore.profiles.value.map(ProfileMetadata::name),
        )
        val singleServerProfiles = runBlocking {
            container.profileStore.profiles.value.all { profile ->
                ConfigAnalyzer.selectorGroups(container.profileStore.read(profile.id).json)
                    .single()
                    .outbounds
                    .size == 1
            }
        }
        assertTrue("Каждый профиль должен содержать ровно один сервер", singleServerProfiles)
        composeRule.onNodeWithText("first").assertExists()
        composeRule.onNodeWithText("second").assertExists()
    }

    @Test
    fun settingsExposeAllFourDnsModesAndPersistSelection() {
        composeRule.onNode(hasText("Настройки") and hasClickAction()).performClick()
        val modes = listOf(
            "Автоматически",
            "DNS Android",
            "Защищённый через VPN",
            "Из JSON",
        )
        modes.forEach { label ->
            composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText(label))
            composeRule.onNodeWithText(label).assertExists()
        }

        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Защищённый через VPN"))
        composeRule.onNodeWithText("Защищённый через VPN").performClick()
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runBlocking { container.uiSettingsStore.settings.first().dnsMode == DnsMode.Secure }
        }
        composeRule.onNodeWithText(
            "Перехватывается TCP/UDP 53; встроенный DoH, DoT и mDNS не перехватываются.",
        ).assertExists()

        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Beta"))
        composeRule.onNodeWithText("Beta").performClick()
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runBlocking {
                container.uiSettingsStore.settings.first().updateChannel == UpdateChannel.Beta
            }
        }

        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Скрытие VPN"))
        composeRule.onNodeWithText("Скрытие VPN").performClick()
        composeRule.onNodeWithText("Возможности rootless-режима").assertExists()
        composeRule.onNodeWithTag("vpn-hiding-session-name").performScrollTo().performClick()
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runBlocking {
                container.uiSettingsStore.settings.first().vpnHiding.neutralSessionName
            }
        }
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onNodeWithTag("vpn-hiding-mtu-CoreDefault").assertIsSelected()
            }.isSuccess
        }
        composeRule.onNodeWithTag("vpn-hiding-mtu-Normalize1500")
            .performScrollTo()
            .performClick()
        val persisted = runBlocking {
            withTimeoutOrNull(5_000) {
                container.uiSettingsStore.settings.first { settings ->
                    settings.vpnHiding.let { options ->
                    options.neutralSessionName &&
                        options.tunMtuMode == TunMtuMode.Normalize1500
                    }
                }
            }
        }
        assertTrue(
            "VPN hiding options were not persisted: " +
                runBlocking { container.uiSettingsStore.settings.first().vpnHiding },
            persisted != null,
        )
    }

    @Test
    fun settingsConfigureAndPersistNetworkAutomation() {
        composeRule.onNode(hasText("Настройки") and hasClickAction()).performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Автоматизация VPN"))
        composeRule.onNodeWithText("Автоматизация VPN").performClick()

        composeRule.onNodeWithTag("network-automation-enabled").performClick()
        composeRule.onNodeWithTag("network-automation-wifi").performScrollTo().performClick()
        composeRule.onNodeWithTag("trusted-wifi-add-manual").performScrollTo().performClick()
        composeRule.onNodeWithText("Имя сети (SSID)").performTextReplacement("Office Wi-Fi")
        composeRule.onNodeWithText("Добавить").performClick()

        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runBlocking {
                container.uiSettingsStore.settings.first().networkAutomation.let { automation ->
                    automation.enabled &&
                        !automation.useVpnOnWifi &&
                        "Office Wi-Fi" in automation.trustedWifiSsids
                }
            }
        }
        composeRule.onNodeWithText("Office Wi-Fi").assertExists()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        val persisted = runBlocking { container.uiSettingsStore.settings.first().networkAutomation }
        assertTrue(persisted.enabled)
        assertTrue(!persisted.useVpnOnWifi)
        assertTrue("Office Wi-Fi" in persisted.trustedWifiSsids)
    }

    @Test
    fun settingsEditDisableAndPersistDnsOverride() {
        runBlocking { container.uiSettingsStore.setDnsMode(DnsMode.Secure) }
        composeRule.onNode(hasText("Настройки") and hasClickAction()).performClick()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasText("ntc.party → 130.255.77.28"))
        composeRule.onNodeWithText("ntc.party → 130.255.77.28").assertExists()
        composeRule.onNodeWithTag("dns-override-edit").performScrollTo().performClick()
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 2
        }
        composeRule.onAllNodes(hasSetTextAction())[0]
            .performTextReplacement("Example.TEST.")
        composeRule.onAllNodes(hasSetTextAction())[1]
            .performTextReplacement("203.0.113.8")
        composeRule.onNodeWithText("Сохранить").performClick()
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runBlocking {
                container.uiSettingsStore.settings.first().dnsOverride.let {
                    it.hostname == "example.test" && it.ipv4Address == "203.0.113.8"
                }
            }
        }
        composeRule.onNodeWithContentDescription("DNS-переопределение включено").performClick()
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runBlocking { !container.uiSettingsStore.settings.first().dnsOverride.enabled }
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasText("example.test → 203.0.113.8"))
        composeRule.onNodeWithText("example.test → 203.0.113.8").assertExists()
    }

    @Test
    fun settingsSubpagesHaveBackNavigationAndCommunityIsIsolated() {
        composeRule.onNode(hasText("Настройки") and hasClickAction()).performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Сообщество"))
        composeRule.onNodeWithText("Сообщество").performClick()
        composeRule.onNodeWithText("Zapret KVN").assertExists()
        composeRule.onNodeWithText("VPN Discord YouTube").assertExists()
        composeRule.onNodeWithText("Zapret VPN bot").assertExists()
        composeRule.onNodeWithContentDescription("Назад").performClick()

        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Диагностика"))
        composeRule.runOnUiThread {
            val token = container.vpnController.nextGeneration()
            container.vpnController.publish(
                token,
                VpnConnectionState.Error("DNS через VPN заблокирован token=visible-secret"),
            )
            container.vpnController.publishCoreDiagnosticLog(token, 3, "token=visible-secret")
        }
        composeRule.onNodeWithText("Диагностика").performClick()
        composeRule.onNodeWithText("Текущее состояние").assertExists()
        composeRule.onNodeWithText("DNS-200 · DNS через VPN").assertExists()
        composeRule.onNodeWithText("DNS через VPN заблокирован token=•••").assertExists()
        composeRule.onNodeWithText("visible-secret", substring = true).assertDoesNotExist()
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            container.vpnController.diagnosticsVisible.value
        }
        composeRule.onNodeWithTag("diagnostic-logs-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("Скрыть", substring = true).assertExists()
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            !container.vpnController.diagnosticsVisible.value
        }

        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("О приложении"))
        composeRule.onNodeWithText("О приложении").performClick()
        composeRule.onNodeWithText("Ядро").assertExists()
        composeRule.onNodeWithText("Известные ограничения MVP").assertExists()
        composeRule.onNodeWithText("Clash YAML", substring = true).assertExists()
    }

    private fun resetDiagnostics() {
        container.vpnController.setDiagnosticsVisible(false)
        val token = container.vpnController.nextGeneration()
        container.vpnController.publish(token, VpnConnectionState.Starting("", "Сброс теста"))
        container.vpnController.publish(token, VpnConnectionState.Stopped)
    }

    private suspend fun resetNetworkAutomation() {
        val trusted = container.uiSettingsStore.settings.first()
            .networkAutomation
            .trustedWifiSsids
        trusted.forEach { container.uiSettingsStore.removeTrustedWifi(it) }
        container.uiSettingsStore.setUseVpnOnNetwork(NetworkTransportSetting.Wifi, true)
        container.uiSettingsStore.setUseVpnOnNetwork(NetworkTransportSetting.Cellular, true)
        container.uiSettingsStore.setUseVpnOnNetwork(NetworkTransportSetting.Ethernet, true)
        container.uiSettingsStore.setUseVpnOnNetwork(NetworkTransportSetting.Other, true)
        container.uiSettingsStore.setPauseOnTrustedWifi(true)
        container.uiSettingsStore.setNetworkAutomationEnabled(false)
    }

    private companion object {
        const val UI_TIMEOUT_MILLIS = 120_000L
        const val VALID_DIRECT =
            """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}"""
        const val UPDATED_DIRECT =
            """{"outbounds":[{"type":"direct","tag":"edited"}],"route":{"final":"edited"}}"""
        val SUBSCRIPTION_LINKS = listOf(
            "vless://11111111-1111-4111-8111-111111111111@one.example:443" +
                "?security=tls&sni=one.example#first",
            "vless://22222222-2222-4222-8222-222222222222@two.example:8443" +
                "?security=tls&sni=two.example#second",
        ).joinToString("\n")
    }
}
