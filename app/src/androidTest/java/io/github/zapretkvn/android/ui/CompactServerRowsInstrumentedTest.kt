package io.github.zapretkvn.android.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ui.servers.DASH
import io.github.zapretkvn.android.ui.servers.LatencyDisplay
import io.github.zapretkvn.android.ui.servers.LatencyIcon
import io.github.zapretkvn.android.ui.servers.RowPosition
import io.github.zapretkvn.android.ui.servers.ServerRow
import io.github.zapretkvn.android.ui.servers.ServerRowUi
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ZapretTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** «Компактные строки» вкладки «Серверы»: настройка переживает перезапуск, строка прячет протокол и адрес. */
@RunWith(AndroidJUnit4::class)
class CompactServerRowsInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun row(latency: LatencyDisplay, badge: String? = null) = ServerRowUi(
        key = "g/t",
        groupTag = "g",
        tag = "t",
        title = "Frankfurt 01",
        flag = null,
        countryCode = null,
        countryName = null,
        protocol = "VLESS",
        monogram = "F",
        endpoint = "203.0.113.5:443",
        latency = latency,
        selected = false,
        badge = badge,
        searchText = "frankfurt",
        a11y = "Frankfurt 01, VLESS, 84 мс, быстро",
    )

    private val fast = LatencyDisplay("84 мс", "быстро", Tone.Good, LatencyIcon.Ok, 84, null)
    private val untested = LatencyDisplay(DASH, "не проверен", Tone.Neutral, LatencyIcon.None, null, null)

    private fun show(compact: Boolean, row: ServerRowUi) {
        composeRule.activity.setContent {
            ZapretTheme { ServerRow(row = row, position = RowPosition.Only, onClick = null, compact = compact) }
        }
    }

    @Test
    fun settingIsOffByDefaultAndSurvivesARestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = UiSettingsStore(context)
        val before = store.settings.first().compactServerRows
        try {
            store.setCompactServerRows(false)
            assertFalse(UiSettingsStore(context).settings.first().compactServerRows)
            store.setCompactServerRows(true)
            // Новый экземпляр читает тот же файл — как приложение после перезапуска.
            assertTrue(UiSettingsStore(context).settings.first().compactServerRows)
        } finally {
            store.setCompactServerRows(before)
        }
        assertEquals(false, UiSettings().compactServerRows)
    }

    @Test
    fun detailedRowShowsProtocolAndAddress() {
        show(compact = false, row = row(fast))
        composeRule.onNodeWithText("Frankfurt 01").assertIsDisplayed()
        composeRule.onNodeWithText("VLESS").assertIsDisplayed()
        composeRule.onNodeWithText("203.0.113.5:443").assertIsDisplayed()
    }

    @Test
    fun compactRowKeepsNameAndLatencyAndHidesProtocolAndAddress() {
        show(compact = true, row = row(fast))
        composeRule.onNodeWithText("Frankfurt 01").assertIsDisplayed()
        composeRule.onNodeWithText("84 мс").assertIsDisplayed()
        composeRule.onNodeWithText("VLESS").assertDoesNotExist()
        composeRule.onNodeWithText("203.0.113.5:443").assertDoesNotExist()
    }

    @Test
    fun compactRowKeepsTheBestBadgeNextToTheName() {
        show(compact = true, row = row(fast, badge = "★ Лучший"))
        composeRule.onNodeWithText("★ Лучший").assertIsDisplayed()
    }

    @Test
    fun compactRowShowsAShortWordInsteadOfANumberUntilTheServerIsTested() {
        show(compact = true, row = row(untested))
        composeRule.onNodeWithText("не проверен").assertIsDisplayed()
    }
}
