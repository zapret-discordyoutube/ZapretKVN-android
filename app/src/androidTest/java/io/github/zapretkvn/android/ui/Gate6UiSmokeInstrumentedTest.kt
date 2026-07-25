package io.github.zapretkvn.android.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.vpn.AppScopeMode
import io.github.zapretkvn.android.vpn.VpnConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class Gate6UiSmokeInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container

    @Before
    fun resetState() = runBlocking {
        container.profileStore.initialize()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        container.uiSettingsStore.setActiveProfile(null)
        container.uiSettingsStore.setThemeMode(ThemeMode.System)
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.appSelectionStore.setMode(AppScopeMode.Include)
        val generation = container.vpnController.nextGeneration()
        container.vpnController.publish(generation, VpnConnectionState.Stopped)
    }

    @After
    fun cleanup() = runBlocking {
        container.uiSettingsStore.setThemeMode(ThemeMode.System)
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.appSelectionStore.setMode(AppScopeMode.Include)
    }

    @Test
    fun fourBottomTabsExposeTheirPrimaryContent() {
        openTab("Главная")
        composeRule.onNodeWithText("VPN выключен").assertIsDisplayed()

        openTab("Профили")
        composeRule.onNodeWithText("Профилей пока нет").assertIsDisplayed()

        openTab("Маршруты")
        composeRule.onNodeWithText("Область VPN").assertIsDisplayed()
        composeRule.onNodeWithText("Правило трафика").assertIsDisplayed()

        openTab("Настройки")
        composeRule.onNodeWithText("Оформление").assertIsDisplayed()
    }

    @Test
    fun lightDarkAndAmoledThemesKeepNavigationLabeledAndTouchTargetsLarge() {
        listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.Amoled).forEach { mode ->
            runBlocking { container.uiSettingsStore.setThemeMode(mode) }
            composeRule.waitUntil(10_000) {
                runBlocking { container.uiSettingsStore.settings.first().themeMode == mode }
            }
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()

            listOf("Главная", "Профили", "Маршруты", "Настройки").forEach { label ->
                composeRule.onNodeWithContentDescription(label, useUnmergedTree = true).assertExists()
                composeRule.onNode(hasText(label) and hasClickAction())
                    .assertHeightIsAtLeast(48.dp)
            }
            composeRule.onNode(hasText("Добавить профиль") and hasClickAction())
                .assertHeightIsAtLeast(48.dp)

            composeRule.runOnUiThread {
                val window = composeRule.activity.window
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                val expectDarkIcons = mode == ThemeMode.Light
                assertEquals(expectDarkIcons, insetsController.isAppearanceLightStatusBars)
                assertEquals(expectDarkIcons, insetsController.isAppearanceLightNavigationBars)
            }
        }
    }

    private fun openTab(label: String) {
        composeRule.onNode(hasText(label) and hasClickAction()).performClick()
        composeRule.waitForIdle()
    }
}
