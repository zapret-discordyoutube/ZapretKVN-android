package io.github.zapretkvn.android.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ui.servers.ProfileHeaderUi
import io.github.zapretkvn.android.ui.servers.ProfileImporter
import io.github.zapretkvn.android.ui.servers.ServerGrouping
import io.github.zapretkvn.android.ui.servers.ServersScreen
import io.github.zapretkvn.android.ui.servers.ServersScreenActions
import io.github.zapretkvn.android.ui.servers.ServersUi
import io.github.zapretkvn.android.ui.servers.liveServerGroups
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.core.designsystem.ZapretTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Группировка вкладки «Серверы» на настоящих ключах владельца (1500 штук, адреса замаскированы): меню → диалог → папки.
 * Снимки экрана кладутся в `getExternalFilesDir/grouping-*.png` — из них берутся картинки для руководства.
 */
@RunWith(AndroidJUnit4::class)
class ServerGroupingUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val chosen = mutableListOf<String>()

    private fun realUi(): ServersUi.Content {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val items = assets.open("real_server_keys.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .split('\n').filter { it.isNotEmpty() }.map { line ->
                val (scheme, name) = line.split('\t', limit = 2)
                RuntimeOutboundItem(name, scheme, null)
            } + RuntimeOutboundItem("🇩🇪 Edge · NaiveProxy", "naive", null)
        val group = RuntimeSelectorGroup("zapret-proxy", "selector", items.first().tag, true, items, primary = true)
        val groups = liveServerGroups(listOf(group), false, System.currentTimeMillis())
        return ServersUi.Content("p", ProfileHeaderUi("Подписка", "1501 сервер", null, 0, "Обновлено только что"), groups)
    }

    private fun show(saved: String) {
        composeRule.activity.setContent {
            ZapretTheme {
                ServersScreen(
                    ui = realUi(),
                    busy = false,
                    importer = ProfileImporter({}, {}, {}, {}),
                    actions = ServersScreenActions(
                        onSelect = { _, _ -> },
                        onMeasure = { _, _ -> },
                        onStopMeasure = {},
                        onOpenProfiles = {},
                        onOpenEditor = {},
                        onFavorite = { _, _ -> },
                        onGrouping = { chosen += it },
                    ),
                    contentPadding = PaddingValues(0.dp),
                    savedGrouping = saved,
                )
            }
        }
    }

    private fun shot(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(900) // dialog and menu animations run in their own windows: wait for them to finish
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "grouping-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theMenuOpensTheDialogAndAPresetGroupsTheRealList() {
        show(ServerGrouping.AUTO)
        composeRule.onNodeWithContentDescription("Сортировка и вид").performClick()
        composeRule.onNodeWithTag("servers-grouping-open").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("grouping-country+server+protocol").assertIsDisplayed()
        shot("dialog")
        composeRule.onNodeWithTag("grouping-country+server+protocol").performClick()
        assertEquals(listOf("country+server+protocol"), chosen)
        shot("country-server-protocol")
        // a custom order: «Свой порядок…» opens the levels window, the result is a mode string
        composeRule.onNodeWithContentDescription("Сортировка и вид").performClick()
        composeRule.onNodeWithTag("servers-grouping-open").performClick()
        composeRule.onNodeWithTag("grouping-custom").performClick()
        composeRule.onNodeWithTag("grouping-level-0-protocol").performClick()
        composeRule.onNodeWithTag("grouping-level-1-country").performClick()
        shot("custom")
        composeRule.onNodeWithTag("grouping-apply-custom").performClick()
        assertEquals(listOf("country+server+protocol", "protocol+country"), chosen)
        // the menu now names the active grouping
        composeRule.onNodeWithContentDescription("Сортировка и вид").performClick()
        composeRule.onNodeWithText("Группировка: Протокол › Страна").assertIsDisplayed()
    }

    @Test
    fun aSavedProtocolGroupingShowsTheNaiveProxyFolder() {
        show("protocol")
        composeRule.waitForIdle()
        shot("protocol")
        // the selected key's folder (VLESS, 1455 keys) is open above it: scroll down to the NaiveProxy folder
        composeRule.onNodeWithTag("profile-servers-sheet").performScrollToNode(hasText("NaiveProxy"))
        composeRule.onNodeWithText("NaiveProxy").assertIsDisplayed()
        shot("protocol-naive")
    }

    @Test
    fun theChosenGroupingIsReadBackByAFreshStore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = UiSettingsStore(context)
        val before = store.settings.first().serverGrouping
        try {
            store.setServerGrouping("country+server")
            assertEquals("country+server", UiSettingsStore(context).settings.first().serverGrouping)
        } finally {
            store.setServerGrouping(before)
        }
        assertEquals("auto", UiSettings().serverGrouping)
    }
}
