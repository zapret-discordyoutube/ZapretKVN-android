package io.github.zapretkvn.android.routing

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ManagedServer
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.vpn.AppScopeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class RoutingUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container

    @Before
    fun prepareProfile() = runBlocking {
        container.profileStore.initialize()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        val profile = container.profileStore.create("Routing UI", profile(), ProfileSource.RawJson)
        container.uiSettingsStore.setActiveProfile(profile.id)
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.appSelectionStore.setMode(AppScopeMode.Include)
    }

    @After
    fun restoreScope() = runBlocking {
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(emptySet())
    }

    @Test
    fun presetSummaryManagedDiffAndDomainBlockMatchStoredJson() {
        composeRule.onNodeWithText("Маршруты").performClick()
        composeRule.waitUntil(20_000) {
            runCatching {
                composeRule.onNodeWithText("Изменить режим").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithText("Изменить режим").performClick()
        composeRule.onNodeWithText("Россия напрямую").performClick()
        composeRule.waitUntil(20_000) {
            runBlocking {
                val id = container.uiSettingsStore.settings.first().activeProfileId ?: return@runBlocking false
                container.profileStore.read(id).json.contains("zapret-ru-domains")
            }
        }
        composeRule.onNodeWithText("Россия и LAN → напрямую, остальное → VPN").assertExists()
        composeRule.onNodeWithTag("routing-list")
            .performScrollToNode(hasText("Последний diff zapret-*"))
        composeRule.onNodeWithText("Последний diff zapret-*").assertExists()

        composeRule.onNodeWithTag("routing-list").performScrollToNode(hasText("Добавить"))
        composeRule.onNodeWithText("Добавить").performClick()
        composeRule.onNodeWithTag("routing-rule-values").performTextInput("blocked.example")
        composeRule.onNodeWithText("Блокировать").performClick()
        composeRule.onNodeWithText("Сохранить").performClick()
        var stored = ""
        for (attempt in 0 until 100) {
            stored = runBlocking {
                val id = requireNotNull(container.uiSettingsStore.settings.first().activeProfileId)
                container.profileStore.read(id).json
            }
            if (stored.contains("blocked.example") && stored.contains("\"action\": \"reject\"")) {
                break
            }
            Thread.sleep(100)
        }
        assertFalse(stored, stored.contains("package_name"))
        assertTrue(stored, stored.contains("blocked.example"))
        assertTrue(stored, stored.contains("\"action\": \"reject\""))
    }

    @Test
    fun addRuleButtonKeepsItsLabelHorizontal() {
        composeRule.onNodeWithText("Маршруты").performClick()
        composeRule.waitUntil(20_000) {
            runCatching {
                composeRule.onNodeWithText("Изменить режим").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithTag("routing-list")
            .performScrollToNode(hasText("Добавить"))

        val buttonBounds = composeRule.onNodeWithTag("routing-add-rule")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Кнопка «Добавить» не должна сжиматься до вертикального текста: $buttonBounds",
            buttonBounds.width > buttonBounds.height,
        )
    }

    @Test
    fun excludeModeShowsWarningAndPersistsAdvancedScope() {
        composeRule.onNodeWithText("Маршруты").performClick()
        composeRule.onNodeWithText("Приложения напрямую").performClick()
        composeRule.waitUntil(10_000) {
            runBlocking { container.appSelectionStore.selection.first().mode == AppScopeMode.Exclude }
        }
        composeRule.onNodeWithText("Режим «Приложения напрямую»:", substring = true).assertExists()
    }

    @Test
    fun advancedJsonOpensTheActiveProfileAndBackReturnsToRouting() {
        composeRule.onNodeWithText("Маршруты").performClick()
        composeRule.waitUntil(20_000) {
            runCatching {
                composeRule.onNode(
                    hasText("Расширенный JSON") and hasClickAction() and isEnabled(),
                )
                    .fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithTag("routing-list")
            .performScrollToNode(hasText("Расширенный JSON"))
        composeRule.onNode(
            hasText("Расширенный JSON") and hasClickAction() and isEnabled(),
        ).performClick()
        composeRule.waitUntil(20_000) {
            runCatching {
                composeRule.onNodeWithContentDescription("Назад").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithText("Routing UI").assertExists()
        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.onNodeWithText("Правило трафика").assertExists()
    }

    @Test
    fun everyPresetSummaryMatchesStoredEffectiveJson() {
        composeRule.onNodeWithText("Маршруты").performClick()
        composeRule.waitUntil(20_000) {
            runCatching { composeRule.onNodeWithText("Изменить режим").fetchSemanticsNode() }.isSuccess
        }
        val sequence = listOf(
            RoutingPreset.BypassLan,
            RoutingPreset.OnlySelectedSites,
            RoutingPreset.RussiaDirect,
            RoutingPreset.RussiaVpn,
            RoutingPreset.Custom,
            RoutingPreset.AllThroughVpn,
        )
        sequence.forEach { preset ->
            composeRule.waitUntil(10_000) {
                runCatching {
                    composeRule.onNodeWithTag("routing-change-preset").fetchSemanticsNode()
                }.isSuccess
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("routing-change-preset")
                .performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(10_000) {
                runCatching {
                    composeRule.onNodeWithTag("routing-preset-options").fetchSemanticsNode()
                }.isSuccess
            }
            composeRule.onNodeWithTag("routing-preset-options")
                .performScrollToNode(hasText(preset.title))
            composeRule.onNodeWithText(preset.title).performClick()
            composeRule.waitUntil(10_000) {
                runCatching {
                    composeRule.onNodeWithTag("routing-preset-options").fetchSemanticsNode()
                }.isFailure
            }
            composeRule.waitUntil(20_000) {
                runBlocking {
                    val id = container.uiSettingsStore.settings.first().activeProfileId
                        ?: return@runBlocking false
                    RoutingConfigEditor.inspect(container.profileStore.read(id).json).preset == preset
                }
            }
            composeRule.onNodeWithText(preset.detail).assertExists()
            val stored = runBlocking {
                val id = requireNotNull(container.uiSettingsStore.settings.first().activeProfileId)
                container.profileStore.read(id).json
            }
            val inspection = RoutingConfigEditor.inspect(stored)
            assertTrue(inspection.summary.startsWith(preset.detail))
            assertFalse(stored.contains("package_name"))
        }
    }

    private fun profile(): String = ManagedProfileFactory.single(
        ManagedServer(
            displayName = "UI",
            identityKey = "ui|server",
            outbound = JsonObject(mapOf("type" to JsonPrimitive("direct"))),
        ),
    )
}
