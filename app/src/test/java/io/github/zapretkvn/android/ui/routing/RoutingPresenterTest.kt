package io.github.zapretkvn.android.ui.routing

import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.apps.AppsUiState
import io.github.zapretkvn.android.apps.InstalledApp
import io.github.zapretkvn.android.routing.ManagedRoutingRule
import io.github.zapretkvn.android.routing.RoutingMatchType
import io.github.zapretkvn.android.routing.RoutingRuleAction
import io.github.zapretkvn.core.designsystem.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPresenterTest {
    private val modelSummary =
        "Россия и LAN → напрямую, остальное → VPN. Пользовательских правил: 2; блокирующих: 1. " +
            "Остальные приложения: напрямую, вне VPN."

    @Test
    fun includeModeKeepsModelSummaryAsIs() {
        assertEquals(modelSummary, routingSummary(AppScopeMode.Include, modelSummary))
    }

    @Test
    fun excludeModeReplacesOtherAppsTail() {
        assertEquals(
            "Россия и LAN → напрямую, остальное → VPN. Пользовательских правил: 2; блокирующих: 1. " +
                "Приложения напрямую: вне VPN и TUN; все остальные входят в TUN.",
            routingSummary(AppScopeMode.Exclude, modelSummary),
        )
    }

    @Test
    fun excludeModeWithoutMarkerAppendsTailToWholeText() {
        assertEquals(
            "Что-то другое" + "Приложения напрямую: вне VPN и TUN; все остальные входят в TUN.",
            routingSummary(AppScopeMode.Exclude, "Что-то другое"),
        )
    }

    @Test
    fun evaluationOrderDependsOnBlockedApps() {
        assertEquals(
            "Порядок: reject → direct → VPN → final. Package route rules не создаются.",
            evaluationOrder(hasBlockedApps = false),
        )
        assertEquals(
            "Порядок: блокировка package → reject → direct → VPN → final.",
            evaluationOrder(hasBlockedApps = true),
        )
    }

    @Test
    fun remoteRuleSetNoteOnlyWhenThereAreSets() {
        assertNull(remoteRuleSetNote(0))
        assertTrue(remoteRuleSetNote(3)!!.contains("3"))
    }

    // --- область VPN ---

    private fun apps(
        mode: AppScopeMode = AppScopeMode.Include,
        allowed: Set<String> = emptySet(),
        blocked: Set<String> = emptySet(),
        initialized: Boolean = true,
        installed: List<String> = emptyList(),
        catalogLoaded: Boolean = true,
    ) = AppsUiState(
        apps = installed.map { InstalledApp(it, it, system = false, enabled = true) },
        allowedPackages = allowed,
        blockedPackages = blocked,
        scopeMode = mode,
        initialized = initialized,
        catalogLoaded = catalogLoaded,
    )

    @Test
    fun includeWithAppsCountsThem() {
        val summary = scopeSummary(apps(allowed = setOf("a", "b"), installed = listOf("a", "b")))
        assertEquals("Через VPN: 2", summary.countText)
        assertEquals(Tone.Accent, summary.countTone)
        assertFalse(summary.needsSelection)
        assertEquals("Выбрать приложения", summary.pickerLabel)
        assertNull(summary.missingText)
    }

    @Test
    fun includeWithoutAppsNeedsSelection() {
        val summary = scopeSummary(apps())
        assertEquals("Приложения не выбраны", summary.countText)
        assertEquals(Tone.Bad, summary.countTone)
        assertTrue(summary.needsSelection)
    }

    @Test
    fun includeWithOnlyBlockedAppsIsReady() {
        val summary = scopeSummary(apps(blocked = setOf("x"), installed = listOf("x")))
        assertEquals("TUN используется только для блокировки", summary.countText)
        assertFalse(summary.needsSelection)
    }

    @Test
    fun notInitializedStateDoesNotShowWarning() {
        val summary = scopeSummary(apps(initialized = false))
        assertFalse(summary.needsSelection)
        assertEquals(Tone.Neutral, summary.countTone)
    }

    @Test
    fun excludeModeUsesDirectWordingAndKeepsTestedCaption() {
        val summary = scopeSummary(apps(mode = AppScopeMode.Exclude, allowed = setOf("a"), installed = listOf("a")))
        assertEquals("Напрямую вне TUN: 1", summary.countText)
        assertTrue(summary.caption.startsWith("Режим «Приложения напрямую»:"))
        assertEquals(Tone.Warning, summary.captionTone)
        assertEquals("Выбрать приложения напрямую", summary.pickerLabel)
    }

    @Test
    fun excludeWithoutAppsNeedsSelection() {
        val summary = scopeSummary(apps(mode = AppScopeMode.Exclude))
        assertTrue(summary.needsSelection)
        assertEquals("Приложения напрямую не выбраны: запуск заблокирован", summary.countText)
    }

    @Test
    fun missingPackagesAreCountedAcrossBothLists() {
        val summary = scopeSummary(
            apps(allowed = setOf("a", "gone1"), blocked = setOf("gone2"), installed = listOf("a")),
        )
        assertEquals(
            "Не найдено на устройстве: 2 приложения. Они будут пропущены при подключении.",
            summary.missingText,
        )
    }

    @Test
    fun blockSummaryTextsMatchTests() {
        assertEquals(BlockSummary("Блокировка приложений: список пуст", Tone.Neutral), blockSummary(0))
        assertEquals(BlockSummary("Заблокировано приложений: 1", Tone.Bad), blockSummary(1))
    }

    // --- правила ---

    @Test
    fun ruleReadsAsSentence() {
        val row = ruleRow(
            ManagedRoutingRule(RoutingMatchType.DomainSuffix, listOf("ntc.party"), RoutingRuleAction.Proxy),
        )
        assertEquals("ntc.party · суффикс домена → Через VPN", row.sentence)
        assertEquals(Tone.Accent, row.tone)
        assertNull(row.outbound)
    }

    @Test
    fun blockRuleIsBadToneAndOutboundOnlyForProxy() {
        val block = ruleRow(
            ManagedRoutingRule(RoutingMatchType.IpCidr, listOf("10.0.0.0/8"), RoutingRuleAction.Block, "x"),
        )
        assertEquals(Tone.Bad, block.tone)
        assertNull(block.outbound)
        val proxy = ruleRow(
            ManagedRoutingRule(RoutingMatchType.Domain, listOf("a.example"), RoutingRuleAction.Proxy, "tag"),
        )
        assertEquals("tag", proxy.outbound)
    }

    @Test
    fun manyValuesAreShortened() {
        assertEquals("a, b, c", ruleValueText(listOf("a", "b", "c")))
        assertEquals("a, b, c и ещё 2", ruleValueText(listOf("a", "b", "c", "d", "e")))
        assertEquals("…", ruleValueText(emptyList()))
    }

    @Test
    fun previewFollowsUnsavedInput() {
        assertEquals(
            "blocked.example · точный домен → Блокировать",
            rulePreview(RoutingMatchType.Domain, listOf("blocked.example"), RoutingRuleAction.Block),
        )
        assertEquals(
            "… · набор IP-адресов → Напрямую",
            rulePreview(RoutingMatchType.IpRuleSet, emptyList(), RoutingRuleAction.Direct),
        )
    }

    @Test
    fun dohWarningOnlyForDomainBlocks() {
        assertTrue(isDomainBlock(RoutingMatchType.Domain, RoutingRuleAction.Block))
        assertTrue(isDomainBlock(RoutingMatchType.DomainRuleSet, RoutingRuleAction.Block))
        assertFalse(isDomainBlock(RoutingMatchType.IpCidr, RoutingRuleAction.Block))
        assertFalse(isDomainBlock(RoutingMatchType.Domain, RoutingRuleAction.Proxy))
    }
}
