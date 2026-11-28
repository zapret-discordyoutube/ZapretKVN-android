package io.github.zapretkvn.android.masking

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ui.settings.MaskingScreen
import io.github.zapretkvn.core.designsystem.ZapretTheme
import io.github.zapretkvn.masking.merge.MaskingSettings
import io.github.zapretkvn.masking.presets.ServerChoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner report 2026-11-28: parameters set on the «Маскировка» screen were gone after leaving it, only the
 * master switch stayed (it is written at once, the rest was written by «Применить» only). The real screen, the
 * real actions and the real DataStore: leaving the screen saves a valid draft.
 */
@RunWith(AndroidJUnit4::class)
class MaskingLeaveSavesInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: MaskingSettingsStore
    private lateinit var before: MaskingSettings

    @Before
    fun remember() {
        store = MaskingSettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)
        before = runBlocking { store.settings.first() }
        runBlocking { store.update { MaskingSettings.OFF } }
    }

    @After
    fun restore() {
        runBlocking { store.update { before } }
    }

    private fun showPage(onApplied: () -> Unit = {}): (Boolean) -> Unit {
        val actions = MaskingActions(store, scope) { onApplied() }
        val visible = mutableStateOf(true)
        composeRule.activity.setContent {
            ZapretTheme {
                val settings by actions.settings.collectAsState(initial = MaskingSettings.OFF)
                if (visible.value) {
                    MaskingScreen(
                        contentPadding = PaddingValues(),
                        settings = settings,
                        onEnabledChange = actions::setEnabled,
                        onApply = actions::setGlobal,
                    )
                } else {
                    Text("left")
                }
            }
        }
        return { visible.value = it }
    }

    @Test
    fun aDraftIsSavedWhenTheScreenIsLeft() {
        val setVisible = showPage()
        composeRule.onNodeWithTag("masking-enabled").performClick()
        composeRule.onNodeWithTag("masking-wireguard").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("masking-jc").performScrollTo().performTextReplacement("7")
        composeRule.waitForIdle()
        // nothing but the switch is stored before leaving
        assertTrue(runBlocking { store.settings.first() }.global.isEmpty)

        composeRule.runOnUiThread { setVisible(false) }
        composeRule.waitForIdle()
        val saved = awaitSaved { !it.global.isEmpty }
        assertTrue(saved.enabled)
        assertNotNull(saved.global.wireguard)
        assertEquals(7, saved.global.wireguard?.jc)
    }

    @Test
    fun aDraftWithAnErrorIsNotSaved() {
        val setVisible = showPage()
        composeRule.onNodeWithTag("masking-wireguard").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("masking-jc").performScrollTo().performTextReplacement("99999")
        composeRule.waitForIdle()
        composeRule.runOnUiThread { setVisible(false) }
        composeRule.waitForIdle()
        Thread.sleep(500)
        assertTrue(runBlocking { store.settings.first() }.global.isEmpty)
    }

    @Test
    fun everyPerServerChoiceSurvivesAReadFromTheFile() {
        val actions = MaskingActions(store, scope) { }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("vless", "wireguard", "shadowsocks").forEach { type ->
            ServerChoices.forType(type).forEach { choice ->
                val key = "0123456789abcdef0123456789abcdef|server-$type"
                actions.setOverride(key, choice.override)
                val read = awaitSaved { it.overrideFor(key) == choice.override }
                assertEquals(choice.override, MaskingSettingsStore(context).let { runBlocking { it.settings.first() } }.overrideFor(key))
                assertEquals(ServerChoices.label(choice.override), ServerChoices.label(read.overrideFor(key)))
            }
        }
    }

    private fun awaitSaved(accept: (MaskingSettings) -> Boolean): MaskingSettings {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val now = runBlocking { store.settings.first() }
            if (accept(now)) return now
            Thread.sleep(100)
        }
        error("the draft was not saved when the screen was left")
    }
}
