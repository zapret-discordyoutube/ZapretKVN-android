package io.github.zapretkvn.android.ui.settings

import io.github.zapretkvn.android.diagnostics.AppCrashRecord
import io.github.zapretkvn.android.diagnostics.AppProcessExitRecord
import io.github.zapretkvn.android.diagnostics.DiagnosticAttemptOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticErrorType
import io.github.zapretkvn.android.diagnostics.DiagnosticFailure
import io.github.zapretkvn.android.diagnostics.DiagnosticNetworkState
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.diagnostics.DiagnosticStageTiming
import io.github.zapretkvn.android.diagnostics.DiagnosticState
import io.github.zapretkvn.android.diagnostics.DiagnosticVpnPolicy
import io.github.zapretkvn.android.vpn.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsPresenterTest {
    private val failure = DiagnosticFailure(
        type = DiagnosticErrorType.VpnDns,
        supportCode = "DNS-200",
        message = "DNS через VPN заблокирован",
        occurredAtEpochMillis = 1,
    )

    private fun stage(label: String, status: DiagnosticStageStatus, duration: Long?, detail: String? = null) =
        DiagnosticStageTiming(
            key = label,
            label = label,
            startedAtEpochMillis = 0,
            startedAtElapsedRealtimeMillis = 0,
            durationMillis = duration,
            status = status,
            detail = detail,
        )

    @Test
    fun `clean state is all good`() {
        val summary = summarizeDiagnostics(VpnConnectionState.Stopped, DiagnosticState())
        assertTrue(summary.ok)
        assertEquals("Всё в порядке", summary.headline)
    }

    @Test
    fun `error state reports a connection problem`() {
        val summary = summarizeDiagnostics(
            VpnConnectionState.Error("упало"),
            DiagnosticState(lastFailure = failure),
        )
        assertFalse(summary.ok)
        assertEquals("Есть проблемы: ошибка подключения", summary.headline)
    }

    @Test
    fun `old failure does not spoil a working connection`() {
        val connected = VpnConnectionState.Connected(
            profileId = "p",
            profileName = "Gate",
            connectedAtEpochMillis = 0,
        )
        assertTrue(summarizeDiagnostics(connected, DiagnosticState(lastFailure = failure)).ok)
        // После остановки с сохранённой ошибкой проблема остаётся видимой.
        assertFalse(summarizeDiagnostics(VpnConnectionState.Stopped, DiagnosticState(lastFailure = failure)).ok)
    }

    @Test
    fun `crash exit and vpn policy add their own problems`() {
        val state = DiagnosticState(
            previousCrash = AppCrashRecord(1, "main", "IllegalStateException", null, emptyList(), emptyList()),
            previousProcessExit = AppProcessExitRecord(1, 6, "anr", 0, 100, 1, 1, null),
            vpnPolicy = DiagnosticVpnPolicy(statusAvailable = true, alwaysOn = true, lockdown = false),
        )
        val summary = summarizeDiagnostics(VpnConnectionState.Stopped, state)
        assertEquals(3, summary.problems.size)
        assertTrue(summary.headline.startsWith("Есть проблемы: "))
    }

    @Test
    fun `quiet process exit and unavailable policy are not problems`() {
        val state = DiagnosticState(
            previousProcessExit = AppProcessExitRecord(1, 10, "user_requested", 0, 100, 1, 1, null),
            vpnPolicy = DiagnosticVpnPolicy(statusAvailable = false, alwaysOn = true, lockdown = true),
        )
        assertTrue(summarizeDiagnostics(VpnConnectionState.Stopped, state).ok)
    }

    @Test
    fun `only the last error section is open by default`() {
        assertEquals(setOf(DiagnosticsSections.Error), defaultExpandedSections(hasLastError = true))
        assertEquals(emptySet<String>(), defaultExpandedSections(hasLastError = false))
    }

    @Test
    fun `toggling flips the default state and flips back`() {
        val defaults = defaultExpandedSections(hasLastError = true)
        var toggled = emptyList<String>()
        assertTrue(isSectionExpanded(DiagnosticsSections.Error, defaults, toggled))
        assertFalse(isSectionExpanded(DiagnosticsSections.Connect, defaults, toggled))

        toggled = toggleSection(DiagnosticsSections.Error, toggled)
        toggled = toggleSection(DiagnosticsSections.Connect, toggled)
        assertFalse(isSectionExpanded(DiagnosticsSections.Error, defaults, toggled))
        assertTrue(isSectionExpanded(DiagnosticsSections.Connect, defaults, toggled))

        toggled = toggleSection(DiagnosticsSections.Error, toggled)
        assertTrue(isSectionExpanded(DiagnosticsSections.Error, defaults, toggled))
    }

    @Test
    fun `stage lines show status duration and detail for connect and stop alike`() {
        val lines = stageLines(
            listOf(
                stage("Профиль", DiagnosticStageStatus.Success, 150),
                stage("Core и TUN", DiagnosticStageStatus.Failed, 1500, "таймаут"),
                stage("Финализация", DiagnosticStageStatus.Running, null),
            ),
        )
        assertEquals("✓ Профиль — 150 мс", lines[0].text)
        assertEquals("× Core и TUN — 1.50 с · таймаут", lines[1].text)
        assertEquals("… Финализация — выполняется", lines[2].text)
        assertEquals(listOf(false, true, false), lines.map { it.failed })
    }

    @Test
    fun `running and slowest stage texts`() {
        val stages = listOf(
            stage("A", DiagnosticStageStatus.Success, 10),
            stage("B", DiagnosticStageStatus.Running, null),
        )
        assertEquals("Сейчас: B", runningStageText(stages))
        assertEquals(null, runningStageText(stages.take(1)))
        assertEquals("Самый долгий этап: A — 10 мс", slowestStageText(stages.first()))
        assertEquals(null, slowestStageText(null))
    }

    @Test
    fun `durations are formatted in ms and seconds`() {
        assertEquals("не измерено", formatDiagnosticDuration(null))
        assertEquals("999 мс", formatDiagnosticDuration(999))
        assertEquals("1.00 с", formatDiagnosticDuration(1000))
        assertEquals(
            "Подключено за 1.25 с",
            connectionOutcomeTitle(DiagnosticAttemptOutcome.Connected, 1250),
        )
    }

    @Test
    fun `vpn state labels keep texts used by tests`() {
        assertEquals("VPN выключен", VpnConnectionState.Stopped.diagnosticLabel())
        assertEquals("Ошибка VPN", VpnConnectionState.Error("x").diagnosticLabel())
        assertEquals(
            "Подключено: Gate SS",
            VpnConnectionState.Connected("p", "Gate SS", 0).diagnosticLabel(),
        )
    }

    @Test
    fun `policy text warns only about always on and lockdown`() {
        assertFalse(vpnPolicyText(null).warning)
        assertFalse(vpnPolicyText(DiagnosticVpnPolicy(false, false, false)).warning)
        assertFalse(vpnPolicyText(DiagnosticVpnPolicy(true, false, false)).warning)
        val warning = vpnPolicyText(DiagnosticVpnPolicy(true, true, false))
        assertTrue(warning.warning)
        assertTrue("Always-on: да" in warning.text)
    }

    @Test
    fun `network lines handle missing state`() {
        assertEquals(1, networkLines(null).size)
        val lines = networkLines(DiagnosticNetworkState(transport = "wifi", interfaceName = "wlan0", validated = true))
        assertEquals("wifi · wlan0", lines[0])
        assertEquals("Validated: да · Metered: нет", lines[1])
    }

    @Test
    fun `logs toggle label counts lines`() {
        assertEquals("Показать (12)", logsToggleLabel(expanded = false, count = 12))
        assertEquals("Скрыть (12)", logsToggleLabel(expanded = true, count = 12))
    }
}
