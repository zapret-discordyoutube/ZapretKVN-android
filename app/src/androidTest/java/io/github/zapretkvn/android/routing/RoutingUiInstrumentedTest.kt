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
        container.routingPolicyStore.set(
            GlobalRoutingPolicy(RoutingPreset.AllThroughVpn, emptyList()),
        )
        container.appSelectionStore.replaceAllowlist(setOf("com.android.settings"))
        container.appSelectionStore.setMode(AppScopeMode.Include)
    }

    @After
    fun restoreScope() = runBlocking {
        container.routingPolicyStore.set(
            GlobalRoutingPolicy(RoutingPreset.AllThroughVpn, emptyList()),
        )
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(emptySet())
    }

    @Test
    fun presetSummaryAndDomainBlockMatchTheGlobalEffectivePolicy() {
        openRoutingTrafficCard()
        composeRule.waitUntil(20_000) {
            runCatching {
                composeRule.onNodeWithText("Изменить режим").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithTag("routing-change-preset")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(10_000) {
            runCatching {
                composeRule.onNodeWithTag("routing-preset-options").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithTag("routing-preset-options")
            .performScrollToNode(hasText("Россия напрямую"))
        composeRule.onNodeWithText("Россия напрямую").performClick()
        composeRule.waitUntil(20_000) {
            runBlocking {
                container.routingPolicyStore.policy.first()?.preset == RoutingPreset.RussiaDirect
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
        composeRule.waitUntil(20_000) {
            runBlocking {
                container.routingPolicyStore.policy.first()?.rules?.any { rule ->
                    rule.values == listOf("blocked.example") &&
                        rule.action == RoutingRuleAction.Block
                } == true
            }
        }
        val base = runBlocking {
            val id = requireNotNull(container.uiSettingsStore.settings.first().activeProfileId)
            container.profileStore.read(id).json
        }
        val policy = runBlocking { requireNotNull(container.routingPolicyStore.policy.first()) }
        val installed = runBlocking { container.ruleSetAssetManager.ensureInstalled() }
        val effective = RoutingConfigEditor.apply(
            base,
            policy.preset,
            policy.rules,
            installed,
        ).json
        assertFalse(base, base.contains("blocked.example"))
        assertFalse(effective, effective.contains("package_name"))
        assertTrue(effective, effective.contains("blocked.example"))
        assertTrue(effective, effective.contains("\"action\": \"reject\""))
    }

    @Test
    fun addRuleButtonKeepsItsLabelHorizontal() {
        openRoutingTrafficCard()
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
    fun routingExplainsThatProfileJsonDoesNotStoreGlobalRules() {
        openRoutingTrafficCard()
        composeRule.onNodeWithTag("routing-list")
            .performScrollToNode(hasText("Исходный JSON профиля"))
        composeRule.onNodeWithText("Исходный JSON профиля").assertExists()
        composeRule.onNodeWithText(
            "Общие правила накладываются на исходный JSON при запуске VPN и в нём не сохраняются.",
        ).assertExists()
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
        openRoutingTrafficCard()
        composeRule.waitUntil(20_000) {
            runCatching {
                composeRule.onNode(
                    hasText("Исходный JSON профиля") and hasClickAction() and isEnabled(),
                )
                    .fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithTag("routing-list")
            .performScrollToNode(hasText("Исходный JSON профиля"))
        composeRule.onNode(
            hasText("Исходный JSON профиля") and hasClickAction() and isEnabled(),
        ).performClick()
        composeRule.waitUntil(20_000) {
            runCatching {
                composeRule.onNodeWithContentDescription("Назад").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithText("Routing UI").assertExists()
        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.onNodeWithTag("routing-list")
            .performScrollToNode(hasText("Правило трафика"))
        composeRule.onNodeWithText("Правило трафика").assertExists()
    }

    @Test
    fun everyPresetSummaryMatchesCompiledEffectiveJson() {
        openRoutingTrafficCard()
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
            composeRule.onNodeWithTag("routing-list")
                .performScrollToNode(hasText("Изменить режим"))
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
                    container.routingPolicyStore.policy.first()?.preset == preset
                }
            }
            composeRule.onNodeWithText(preset.detail).assertExists()
            val base = runBlocking {
                val id = requireNotNull(container.uiSettingsStore.settings.first().activeProfileId)
                container.profileStore.read(id).json
            }
            val policy = runBlocking {
                requireNotNull(container.routingPolicyStore.policy.first())
            }
            val installed = runBlocking { container.ruleSetAssetManager.ensureInstalled() }
            val effective = RoutingConfigEditor.apply(
                base,
                policy.preset,
                policy.rules,
                installed,
            ).json
            val inspection = RoutingConfigEditor.inspect(effective)
            assertTrue(inspection.summary.startsWith(preset.detail))
            assertFalse(effective.contains("package_name"))
        }
    }

    private fun openRoutingTrafficCard() {
        composeRule.onNodeWithText("Маршруты").performClick()
        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNodeWithTag("routing-list").fetchSemanticsNode() }.isSuccess
        }
        composeRule.onNodeWithTag("routing-list")
            .performScrollToNode(hasText("Правило трафика"))
    }

    private fun profile(): String = ManagedProfileFactory.single(
        ManagedServer(
            displayName = "UI",
            identityKey = "ui|server",
            outbound = JsonObject(mapOf("type" to JsonPrimitive("direct"))),
        ),
    )
}
