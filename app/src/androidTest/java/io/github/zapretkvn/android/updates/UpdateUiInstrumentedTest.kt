package io.github.zapretkvn.android.updates

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.ui.settings.ReleaseUi
import io.github.zapretkvn.android.ui.settings.UpdateControls
import io.github.zapretkvn.android.ui.settings.UpdateSectionUi
import io.github.zapretkvn.android.ui.update.BannerAction
import io.github.zapretkvn.android.ui.update.UpdateBanner
import io.github.zapretkvn.android.ui.update.UpdateBannerUi
import io.github.zapretkvn.core.designsystem.ZapretTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class UpdateUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container

    @Before
    fun resetUpdater() = runBlocking {
        container.updateController.cancelAndDelete()
        container.updateController.state.first { it == UpdateState.Idle }
        container.uiSettingsStore.setUpdateChannel(UpdateChannel.Stable)
    }

    @After
    fun cleanup() = runBlocking {
        container.updateController.cancelAndDelete()
        container.uiSettingsStore.setUpdateChannel(UpdateChannel.Stable)
    }

    @Test
    fun availableUpdateShowsFormattedReleaseChangesInSettingsWithoutAnyDialog() {
        val notes = "### Поддержка форматирования\n\n**Важно:** исправлен Beta-канал и очистка APK."
        composeRule.activity.setContent {
            ZapretTheme(darkTheme = false) {
                UpdateControls(
                    ui = UpdateSectionUi.Available(ReleaseUi("0.2.0-test.12", "Ядро v1.13.14", notes)),
                    onCheck = {},
                    onDownload = {},
                    onInstall = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("Поддержка форматирования").assertExists()
        composeRule.onNodeWithText("Важно: исправлен Beta-канал и очистка APK.").assertExists()
        composeRule.onNodeWithText("### Поддержка форматирования").assertDoesNotExist()
        composeRule.onNodeWithText("Скачать").assertExists()
    }

    @Test
    fun readyBannerOffersInstallAndCanBeDismissed() {
        var action: BannerAction? = null
        var dismissed = false
        composeRule.activity.setContent {
            ZapretTheme(darkTheme = false) {
                UpdateBanner(
                    ui = UpdateBannerUi(500, "Обновление 0.5.2 готово", BannerAction.Install, null, true),
                    onAction = { action = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithTag("update-banner").assertExists()
        composeRule.onNodeWithText("Обновление 0.5.2 готово").assertExists()
        composeRule.onNodeWithText("Установить").performClick()
        composeRule.onNodeWithContentDescription("Скрыть для этой версии").performClick()
        composeRule.waitForIdle()
        assertEquals(BannerAction.Install, action)
        assertTrue(dismissed)
    }

    @Test
    fun settingsPersistChannelAndKeepManualCheckAvailable() {
        composeRule.onNode(hasText("Настройки") and hasClickAction()).performClick()
        // Разовая проверка при запуске приложения стартует асинхронно (и без сети кончается
        // ошибкой): дожидаемся её начала, затем сбрасываем состояние, чтобы страница
        // стартовала из Idle с кнопкой «Проверить сейчас».
        runCatching {
            composeRule.waitUntil(5_000) { container.updateController.state.value != UpdateState.Idle }
        }
        runBlocking {
            container.updateController.cancelAndDelete()
            container.updateController.state.first { it == UpdateState.Idle }
        }
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Обновления"))
        composeRule.onNodeWithText("Обновления").assertExists()
        // Строка может оказаться под нижней панелью: нажимаем семантическим действием.
        composeRule.onNodeWithText("Обновления").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Проверить сейчас").assertExists()
        composeRule.onNode(hasText("Beta") and hasClickAction()).performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { container.uiSettingsStore.settings.first().updateChannel == UpdateChannel.Beta }
        }
        composeRule.onNodeWithText("Обновления").assertExists()
    }
}
