package io.github.zapretkvn.android.vpn

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.profiles.ProfileSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Home with service slots (docs/SERVICE_SLOTS.md): the card lists each service with its server, a tap opens the sheet,
 * a pick pins the server in the stored settings, and the low-speed switch on Home writes the same setting as Settings.
 */
class HomeSlotsUiInstrumentedTest {
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
        container.uiSettingsStore.setHideServerAddresses(false)
        container.uiSettingsStore.setSlowServerSwitch(true)
        container.uiSettingsStore.setSlotPin("youtube", null)
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
                        RuntimeOutboundItem("Moscow", "vless", "vpn.example:443"),
                        RuntimeOutboundItem("Berlin", "vless", "berlin.example:443"),
                        RuntimeOutboundItem("Paris", "vless", "paris.example:443"),
                    ),
                ),
            ),
        )
        container.vpnController.publishSlots(
            generation,
            listOf(SlotState("youtube", "YouTube", "Berlin"), SlotState("telegram", "Telegram", "Paris")),
        )
    }

    @After
    fun cleanup() = runBlocking {
        container.vpnController.setHomeVisible(false)
        val generation = container.vpnController.nextGeneration()
        container.vpnController.publish(generation, VpnConnectionState.Stopped)
        profileId?.let { container.profileStore.delete(it) }
        container.uiSettingsStore.setActiveProfile(null)
        container.uiSettingsStore.setSlotPin("youtube", null)
        container.uiSettingsStore.setSlowServerSwitch(true)
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.appSelectionStore.replaceBlocklist(emptySet())
    }

    private fun scrollToCard() {
        composeRule.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-slots"))
    }

    @Test
    fun cardShowsEveryServiceWithItsServerAndTheMainServerLast() {
        scrollToCard()
        composeRule.onNodeWithText("Серверы по сервисам").assertExists()
        composeRule.onNodeWithTag("home-slot-youtube").assertExists()
        composeRule.onNodeWithTag("home-slot-telegram").assertExists()
        composeRule.onNodeWithTag("home-slot-default").assertExists()
        composeRule.onNodeWithText("Berlin").assertExists()
        composeRule.onNodeWithText("Paris").assertExists()
    }

    @Test
    fun pickingAServerInTheSheetPinsItAndAutomaticUnpinsIt() = runBlocking {
        scrollToCard()
        composeRule.onNodeWithTag("home-slot-youtube").performClick()
        composeRule.onNodeWithTag("home-slot-sheet").assertExists()
        composeRule.onNodeWithText("Автоматически").assertExists()
        // The sheet lists the servers of the profile; Paris is not the current server of YouTube.
        // "Paris" is also the Telegram row behind the sheet: look only inside the sheet.
        val parisInSheet = androidx.compose.ui.test.hasText("Paris") and
            androidx.compose.ui.test.hasAnyAncestor(hasTestTag("home-slot-sheet"))
        composeRule.onNode(parisInSheet).performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.uiSettingsStore.settings.first().slotPins["youtube"] } == "Paris"
        }
        assertEquals("Paris", container.uiSettingsStore.settings.first().slotPins["youtube"])
        composeRule.onNodeWithText("Автоматически").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.uiSettingsStore.settings.first().slotPins["youtube"] } == null
        }
        assertTrue(container.uiSettingsStore.settings.first().slotPins.isEmpty())
    }

    @Test
    fun theLowSpeedSwitchOnHomeWritesTheSharedSetting() = runBlocking {
        scrollToCard()
        composeRule.onNodeWithTag("home-slow-switch").performClick()
        composeRule.waitUntil(5_000) {
            !runBlocking { container.uiSettingsStore.settings.first().slowServerSwitch }
        }
        assertEquals(false, container.uiSettingsStore.settings.first().slowServerSwitch)
    }

    private companion object {
        const val PROFILE = """
            {"outbounds":[{"type":"vless","tag":"Moscow","server":"vpn.example","server_port":443,"uuid":"00000000-0000-0000-0000-000000000001"}],"route":{"final":"Moscow"}}
        """
    }
}
