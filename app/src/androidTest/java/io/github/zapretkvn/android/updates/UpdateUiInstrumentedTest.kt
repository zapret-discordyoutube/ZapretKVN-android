package io.github.zapretkvn.android.updates

import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.ui.UpdateAvailableDialog
import io.github.zapretkvn.android.ui.theme.ZapretTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
    fun availableDialogShowsReleaseChanges() {
        val candidate = UpdateCandidate(
            release = GitHubRelease(
                tag = "v0.2.0-test.12",
                title = "Test 12",
                body = "### Поддержка форматирования\n\n**Важно:** исправлен Beta-канал и очистка APK.",
                pageUrl = "https://github.com/release",
                draft = false,
                prerelease = true,
                assets = emptyList(),
            ),
            metadata = ReleaseMetadata(
                versionName = "0.2.0-test.12",
                versionCode = 200120,
                applicationId = "io.github.zapretkvn.android.debug",
                coreTag = "v1.13.14-extended-2.5.2",
                coreCommit = "ff11f007ec798136a5de258f947a4f34011a37ea",
                abi = listOf("arm64-v8a"),
                apkFile = "update.apk",
                apkSha256 = "0".repeat(64),
                apkSize = 1,
            ),
            apkAsset = GitHubAsset("update.apk", "https://github.com/apk", 1, null),
            checksumAsset = GitHubAsset("update.apk.sha256", "https://github.com/sha", 1, null),
        )
        composeRule.activity.setContent {
            ZapretTheme(darkTheme = false) {
                UpdateAvailableDialog(candidate, onDownload = {}, onLater = {})
            }
        }

        composeRule.onNodeWithTag("update-available-dialog").assertExists()
        composeRule.onNodeWithText("Поддержка форматирования").assertExists()
        composeRule.onNodeWithText("Важно: исправлен Beta-канал и очистка APK.").assertExists()
        composeRule.onNodeWithText("### Поддержка форматирования").assertDoesNotExist()
        composeRule.onNodeWithText("Скачать").assertExists()
        composeRule.onNodeWithText("Позже").assertExists()
    }

    @Test
    fun settingsPersistChannelAndKeepManualCheckAvailable() {
        composeRule.onNode(hasText("Настройки") and hasClickAction()).performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Обновления"))
        composeRule.onNodeWithText("Обновления").assertExists()
        composeRule.onNodeWithText("Проверить обновления").assertExists()
        composeRule.onNode(hasText("Beta") and hasClickAction()).performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.uiSettingsStore.settings.first().updateChannel == UpdateChannel.Beta }
        }
        composeRule.onNodeWithText("Обновления").assertExists()
    }
}
