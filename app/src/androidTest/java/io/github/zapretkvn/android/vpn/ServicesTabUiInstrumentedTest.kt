package io.github.zapretkvn.android.vpn

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.slots.ServerRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The «Сервисы» tab (third tab, after «Серверы»): the services are listed, a tap expands one, picking a server writes a
 * profile-qualified pin into the stored settings and «Автоматически» removes it. Runs on the debug package's own data.
 */
class ServicesTabUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container

    private var profileId: String? = null

    @Before
    fun prepare() = runBlocking {
        container.profileStore.initialize()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        val profile = container.profileStore.create("Daily VPN", PROFILE, ProfileSource.RawJson)
        profileId = profile.id
        container.uiSettingsStore.setActiveProfile(profile.id)
        container.uiSettingsStore.setHideServerAddresses(false)
        container.uiSettingsStore.setServiceSlots(true)
        container.uiSettingsStore.setSlotPin("youtube", null)
    }

    @After
    fun cleanup() = runBlocking {
        profileId?.let { container.profileStore.delete(it) }
        container.uiSettingsStore.setActiveProfile(null)
        container.uiSettingsStore.setSlotPin("youtube", null)
        container.uiSettingsStore.setServiceSlots(false)
    }

    @Test
    fun theTabListsTheServicesAndAPickWritesAQualifiedPin() = runBlocking {
        composeRule.onNodeWithText("Сервисы").performClick()
        composeRule.onNodeWithTag("services-screen").assertExists()
        composeRule.onNodeWithTag("service-youtube").assertExists()
        composeRule.onNodeWithTag("service-telegram").assertExists()
        composeRule.onNodeWithTag("service-ai").assertExists()

        composeRule.onNodeWithText("YouTube").performClick()
        composeRule.onNodeWithText("Автоматически").assertExists()
        composeRule.onNodeWithText("Paris").performClick()
        val expected = ServerRef(profileId!!, "Paris").encode()
        composeRule.waitUntil(5_000) {
            runBlocking { container.uiSettingsStore.settings.first().slotPins["youtube"] } == expected
        }
        assertEquals(expected, container.uiSettingsStore.settings.first().slotPins["youtube"])

        composeRule.onNodeWithText("Автоматически").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.uiSettingsStore.settings.first().slotPins["youtube"] } == null
        }
        assertNull(container.uiSettingsStore.settings.first().slotPins["youtube"])
    }

    private companion object {
        const val PROFILE = """
            {"outbounds":[
              {"type":"vless","tag":"Moscow","server":"vpn.example","server_port":443,"uuid":"00000000-0000-0000-0000-000000000001"},
              {"type":"vless","tag":"Paris","server":"paris.example","server_port":443,"uuid":"00000000-0000-0000-0000-000000000002"},
              {"type":"selector","tag":"zapret-proxy","outbounds":["Moscow","Paris"],"default":"Moscow"}
             ],"route":{"final":"zapret-proxy"}}
        """
    }
}
