package io.github.zapretkvn.android.vpn

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.profiles.ProfileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HomeUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container

    private var profileId: String? = null

    @Before
    fun prepareConnectedDashboard() = runBlocking {
        container.profileStore.initialize()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        val profile = container.profileStore.create("Daily VPN", PROFILE, ProfileSource.RawJson)
        profileId = profile.id
        container.uiSettingsStore.setActiveProfile(profile.id)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.appSelectionStore.replaceBlocklist(emptySet())
        val generation = container.vpnController.nextGeneration()
        container.vpnController.publish(
            generation,
            VpnConnectionState.Connected(profile.id, profile.name, System.currentTimeMillis() - 65_000),
        )
        container.vpnController.publishGroups(
            generation,
            listOf(
                RuntimeSelectorGroup(
                    tag = "zapret-proxy",
                    type = "selector",
                    selected = "Moscow",
                    selectable = true,
                    items = listOf(
                        RuntimeOutboundItem(
                            tag = "Moscow",
                            type = "vless",
                            endpoint = "vpn.example:443",
                            pingMillis = 42,
                            pingMeasuredAtEpochSeconds = 1,
                        ),
                    ),
                ),
            ),
        )
        container.vpnController.publishPing(generation, 42)
        container.vpnController.publishExternalIp(generation, "203.0.113.9")
        container.vpnController.publishStatusStream(generation, true)
        container.vpnController.publishTraffic(generation, 1_024, 2_048, 4_096, 8_192)
    }

    @After
    fun cleanup() = runBlocking {
        container.vpnController.setHomeVisible(false)
        val generation = container.vpnController.nextGeneration()
        container.vpnController.publish(generation, VpnConnectionState.Stopped)
        profileId?.let { container.profileStore.delete(it) }
        container.uiSettingsStore.setActiveProfile(null)
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.appSelectionStore.replaceBlocklist(emptySet())
    }

    @Test
    fun compactCardAndSelectorSheetExposeSessionData() {
        composeRule.onNodeWithText("VPN подключён").assertExists()
        composeRule.onNodeWithText("Daily VPN").assertExists()
        composeRule.onNodeWithText("203.0.113.9").assertExists()
        composeRule.onAllNodesWithText("42 мс").assertCountEquals(2)
        composeRule.onNodeWithText("↓ 8.0 КБ").assertExists()
        composeRule.onNodeWithText("↑ 4.0 КБ").assertExists()
        composeRule.onNodeWithContentDescription("График загрузки и отдачи за последние 60 секунд").assertExists()

        composeRule.onNodeWithText("Moscow").performClick()
        composeRule.onNodeWithText("Серверы").assertExists()
        composeRule.onAllNodesWithText("VLESS · vpn.example:443").assertCountEquals(2)
        composeRule.onNodeWithText("Проверить").assertExists()
    }

    @Test
    fun homeRendersEveryConnectionStateWithoutChangingScreens() {
        val profile = checkNotNull(profileId)
        var generation = container.vpnController.nextGeneration()
        container.vpnController.publish(generation, VpnConnectionState.Stopped)
        composeRule.onNodeWithText("VPN выключен").assertExists()
        composeRule.onNodeWithText("Подключить").assertExists()

        generation = container.vpnController.nextGeneration()
        container.vpnController.publish(
            generation,
            VpnConnectionState.Starting(profile, "Проверка sing-box"),
        )
        composeRule.onNodeWithText("Подключение").assertExists()
        composeRule.onNodeWithText("Проверка sing-box").assertExists()
        composeRule.onNodeWithText("Остановить").assertExists()

        container.vpnController.publish(generation, VpnConnectionState.Stopping(profile))
        composeRule.onNodeWithText("Отключение").assertExists()
        composeRule.onNodeWithText("Остановить").assertExists()

        container.vpnController.publish(
            generation,
            VpnConnectionState.Error("DNS через VPN заблокирован token=home-secret"),
        )
        composeRule.onNodeWithText("Ошибка VPN").assertExists()
        composeRule.onNodeWithText("Код: DNS-200").assertExists()
        composeRule.onNodeWithText("DNS через VPN заблокирован token=•••").assertExists()
        composeRule.onNodeWithText("home-secret", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Подключить").assertExists()

        container.vpnController.publish(
            generation,
            VpnConnectionState.Connected(profile, "Daily VPN", System.currentTimeMillis()),
        )
        composeRule.onNodeWithText("VPN подключён").assertExists()
        composeRule.onNodeWithText("Отключить").assertExists()
    }

    @Test
    fun homeBlocksConnectionUntilProfileAndApplicationAreSelected() {
        runBlocking { container.uiSettingsStore.setActiveProfile(null) }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Добавить профиль").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Добавить профиль").assertExists()

        runBlocking {
            val generation = container.vpnController.nextGeneration()
            container.vpnController.publish(generation, VpnConnectionState.Stopped)
            container.uiSettingsStore.setActiveProfile(profileId)
            container.appSelectionStore.replaceAllowlist(emptySet())
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Выбрать приложения").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Выберите хотя бы одно приложение для VPN.").assertExists()
        composeRule.onNodeWithText("Выбрать приложения").assertExists()
    }

    @Test
    fun connectedSessionCanStillBeStoppedAfterItsOnlyAppIsRemoved() {
        runBlocking {
            container.appSelectionStore.replaceAllowlist(emptySet())
            container.appSelectionStore.replaceBlocklist(emptySet())
        }

        composeRule.onNodeWithText("VPN подключён").assertExists()
        composeRule.onNodeWithText("Отключить").assertExists()
        composeRule.onNodeWithText("Выбрать приложения").assertDoesNotExist()
    }

    @Test
    fun startingProgressDoesNotEraseCommandGroupSnapshot() {
        val generation = container.vpnController.nextGeneration()
        container.vpnController.publish(
            generation,
            VpnConnectionState.Starting(checkNotNull(profileId), "Создание TUN"),
        )
        val groups = listOf(
            RuntimeSelectorGroup(
                tag = "zapret-proxy",
                type = "selector",
                selected = "Moscow",
                selectable = true,
                items = emptyList(),
            ),
        )
        container.vpnController.publishGroups(generation, groups)

        container.vpnController.publish(
            generation,
            VpnConnectionState.Starting(checkNotNull(profileId), "Проверка DNS и HTTPS"),
        )

        assertEquals(groups, container.vpnController.selectorGroups.value)
    }

    private companion object {
        const val PROFILE = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true,"route_address":["0.0.0.0/0","::/0"]}],
              "outbounds":[
                {"type":"vless","tag":"Moscow","server":"vpn.example","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"},
                {"type":"selector","tag":"zapret-proxy","outbounds":["Moscow"],"default":"Moscow"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """
    }
}
