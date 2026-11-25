package io.github.zapretkvn.android.vpn

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.ui.home.WarpOfferSpotlight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A connection that failed with the WARP entry off offers it (owner 2026-11-26): the card says so, the button opens
 * «Дополнительно» with the switch highlighted and a line saying what to tap, and turning the entry on ends the highlight.
 * Real MainActivity, real stores, the real screens.
 */
class WarpOfferUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container

    private var profileId: String? = null

    @Before
    fun prepareFailedConnection() = runBlocking {
        container.profileStore.initialize()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        val profile = container.profileStore.create("Daily VPN", HomeUiInstrumentedTest.PROFILE, ProfileSource.RawJson)
        profileId = profile.id
        container.uiSettingsStore.setActiveProfile(profile.id)
        container.uiSettingsStore.setWarpEntryHop(false)
        WarpOfferSpotlight.clear()
        val generation = container.vpnController.nextGeneration()
        container.vpnController.publish(generation, VpnConnectionState.Error("DNS через VPN заблокирован"))
    }

    @After
    fun cleanup() = runBlocking {
        WarpOfferSpotlight.clear()
        container.uiSettingsStore.setWarpEntryHop(false)
        container.vpnController.setHomeVisible(false)
        val generation = container.vpnController.nextGeneration()
        container.vpnController.publish(generation, VpnConnectionState.Stopped)
        profileId?.let { container.profileStore.delete(it) }
        container.uiSettingsStore.setActiveProfile(null)
    }

    private fun waitFor(tag: String) = composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theFailureCardOffersWarpAndTheButtonHighlightsTheSwitch() {
        composeRule.onNodeWithTag("home-list").performScrollToNode(hasTestTag("vpn-error-warp"))
        composeRule.onNodeWithTag("vpn-error-warp-hint").assertExists()
        composeRule.onNodeWithTag("vpn-error-warp").performClick()

        waitFor("warp-entry-spotlight-hint")
        composeRule.onNodeWithTag("warp-entry-spotlight").assertExists()

        // The user taps the switch: the entry is on, the highlight is over.
        composeRule.onNodeWithContentDescription("Вход через Cloudflare WARP").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("warp-entry-spotlight-hint").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(runBlocking { container.uiSettingsStore.settings.first().warpEntryHop })
    }

    @Test
    fun withTheEntryOnTheCardOffersNothing() {
        runBlocking { container.uiSettingsStore.setWarpEntryHop(true) }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("vpn-error").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("vpn-error-warp").fetchSemanticsNodes().isEmpty())
    }
}
