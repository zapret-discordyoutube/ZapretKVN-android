package io.github.zapretkvn.android.vpn

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
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
        container.uiSettingsStore.setHideServerAddresses(true)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.appSelectionStore.replaceBlocklist(emptySet())
        val measuredAt = System.currentTimeMillis()
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
                            icmp = LatencyProbeState.Success(
                                LatencySample(42, measuredAt, null),
                            ),
                            relay = LatencyProbeState.Success(
                                LatencySample(84, measuredAt, null),
                            ),
                        ),
                        // Плашка сервера на Главной открывает «Серверы» только когда есть из чего
                        // выбирать, поэтому в группе два сервера.
                        RuntimeOutboundItem(
                            tag = "Berlin",
                            type = "vless",
                            endpoint = "berlin.example:443",
                        ),
                    ),
                ),
            ),
        )
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
        composeRule.onNodeWithText("Всё работает").assertExists()
        composeRule.onNodeWithText("84 мс · быстро").assertExists()
        composeRule.onNodeWithText("vpn.example", substring = true).assertDoesNotExist()

        // Карточки ниже героя лениво создаются при прокрутке.
        val list = composeRule.onNodeWithTag("home-list")
        list.performScrollToNode(hasText("203.0.113.9"))
        composeRule.onNodeWithText("203.0.113.9").assertExists()
        list.performScrollToNode(hasText("ICMP · 42 мс"))
        composeRule.onNodeWithText("ICMP · 42 мс").assertExists()
        composeRule.onNodeWithText("Relay HTTPS · 84 мс").assertExists()
        list.performScrollToNode(hasText("↓ 8.0 КБ"))
        composeRule.onNodeWithText("↓ 8.0 КБ").assertExists()
        composeRule.onNodeWithText("↑ 4.0 КБ").assertExists()
        composeRule.onNodeWithContentDescription("График загрузки и отдачи за последние 60 секунд").assertExists()

        // Плашка сервера ведёт на вкладку «Серверы».
        list.performScrollToNode(hasText("Moscow"))
        composeRule.onNodeWithText("Moscow").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("profile-servers-sheet").fetchSemanticsNodes().isNotEmpty()
        }
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
        composeRule.onNodeWithText("VPN подключился, но DNS через него не отвечает").assertExists()
        composeRule.onNodeWithText("Код: DNS-200").assertExists()
        composeRule.onNodeWithText("Отправить диагностику").assertExists()
        composeRule.onNodeWithText("DNS через VPN заблокирован token=•••").assertDoesNotExist()
        composeRule.onNodeWithText("Подробности").performClick()
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
        // Работающая сессия всегда остаётся управляемой («Отключить»), поэтому «Начнём с
        // профиля» показывается только в покое: сначала останавливаем VPN из подготовки.
        runBlocking {
            val stopped = container.vpnController.nextGeneration()
            container.vpnController.publish(stopped, VpnConnectionState.Stopped)
            container.uiSettingsStore.setActiveProfile(null)
        }
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
                {"type":"vless","tag":"Berlin","server":"berlin.example","server_port":443,"uuid":"00000000-0000-4000-8000-000000000002"},
                {"type":"selector","tag":"zapret-proxy","outbounds":["Moscow","Berlin"],"default":"Moscow"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """
    }
}
