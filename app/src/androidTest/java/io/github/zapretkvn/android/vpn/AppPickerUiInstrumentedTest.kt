package io.github.zapretkvn.android.vpn

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.ui.AppRow
import io.github.zapretkvn.android.ui.theme.ZapretTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AppPickerUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container

    @Before
    fun clearSelection() = runBlocking {
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.appSelectionStore.replaceBlocklist(emptySet())
        container.appSelectionStore.setMode(AppScopeMode.Include)
    }

    @After
    fun cleanSelection() = runBlocking {
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.appSelectionStore.replaceBlocklist(emptySet())
        container.appSelectionStore.setMode(AppScopeMode.Include)
    }

    @Test
    fun userFindsSystemAppSelectsItAndSelectionSurvivesRecreation() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            runBlocking { container.appSelectionStore.selection.first().initialized }
        }
        composeRule.onNodeWithText("Маршруты").performClick()
        composeRule.onNodeWithText("Выбрать приложения").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("show-system-apps").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("show-system-apps").performClick()
        composeRule.onNodeWithTag("app-search").performTextInput(SETTINGS_PACKAGE)
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("app-row-$SETTINGS_PACKAGE")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("app-row-$SETTINGS_PACKAGE").assertExists().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking {
                SETTINGS_PACKAGE in container.appSelectionStore.selection.first().allowedPackages
            }
        }
        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.onNodeWithText("Через VPN: 1").assertExists()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Через VPN: 1").assertExists()
    }

    @Test
    fun disabledInstalledAppCanStillBeSelectedForVpn() {
        val selected = AtomicBoolean(false)
        composeRule.activity.setContent {
            ZapretTheme {
                AppRow(
                    app = InstalledApp(
                        packageName = "com.example.disabled.system",
                        label = "Disabled system app",
                        system = true,
                        enabled = false,
                        suggestion = "Disabled system app",
                    ),
                    selected = false,
                    onSelectedChange = selected::set,
                )
            }
        }

        composeRule.onNodeWithTag("app-row-com.example.disabled.system")
            .assertIsEnabled()
            .performClick()

        assertTrue(selected.get())
    }

    @Test
    fun blocklistStartsEmptyAndSelectedAppSurvivesRecreation() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            runBlocking { container.appSelectionStore.selection.first().initialized }
        }
        composeRule.onNodeWithText("Маршруты").performClick()
        composeRule.onNodeWithText("Блокировка приложений: список пуст").assertExists()
        composeRule.onNodeWithText("Блокировать приложения").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("show-system-apps").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("show-system-apps").performClick()
        composeRule.onNodeWithTag("app-search").performTextInput(SETTINGS_PACKAGE)
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("app-row-$SETTINGS_PACKAGE")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("app-row-$SETTINGS_PACKAGE").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking {
                SETTINGS_PACKAGE in container.appSelectionStore.selection.first().blockedPackages
            }
        }
        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.onNodeWithText("Заблокировано приложений: 1").assertExists()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Заблокировано приложений: 1").assertExists()
    }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
