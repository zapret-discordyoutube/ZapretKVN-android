package io.github.zapretkvn.android.ui.settings

import androidx.compose.runtime.Immutable
import io.github.zapretkvn.android.diagnostics.DiagnosticAttemptOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticConnectionAttempt
import io.github.zapretkvn.android.diagnostics.DiagnosticLogLine
import io.github.zapretkvn.android.diagnostics.DiagnosticNetworkState
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.diagnostics.DiagnosticStageTiming
import io.github.zapretkvn.android.diagnostics.DiagnosticState
import io.github.zapretkvn.android.diagnostics.DiagnosticStopAttempt
import io.github.zapretkvn.android.diagnostics.DiagnosticStopOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticVpnPolicy
import io.github.zapretkvn.android.diagnostics.AppProcessExitRecord
import io.github.zapretkvn.android.diagnostics.RecordedRuntimeFailure
import io.github.zapretkvn.android.vpn.VpnConnectionState
import java.util.Locale

/** Ключи секций аккордеона. Нужны и для сохранения состояния, и для тестов. */
internal object DiagnosticsSections {
    const val Error = "error"
    const val Connect = "connect"
    const val Stop = "stop"
    const val Crash = "crash"
    const val Environment = "environment"
    const val Network = "network"
    const val CoreErrors = "core-errors"
    const val Overlay = "overlay"
}

/** Сводка наверху экрана: «Всё в порядке» или «Есть проблемы: …». */
@Immutable
internal data class DiagnosticsSummary(
    val ok: Boolean,
    val headline: String,
    val problems: List<String>,
)

internal fun summarizeDiagnostics(
    vpnState: VpnConnectionState,
    diagnostics: DiagnosticState,
): DiagnosticsSummary {
    val problems = buildList {
        if (vpnState is VpnConnectionState.Error ||
            (vpnState is VpnConnectionState.Stopped && diagnostics.lastFailure != null)
        ) {
            add("ошибка подключения")
        }
        if (diagnostics.previousCrash != null) add("приложение недавно закрылось с ошибкой")
        if (diagnostics.previousProcessExit?.isCrash() == true) add("Android остановил процесс из-за сбоя")
        val policy = diagnostics.vpnPolicy
        if (policy != null && policy.statusAvailable && (policy.alwaysOn || policy.lockdown)) {
            add("включён режим Always-on или блокировка VPN в Android")
        }
    }
    return if (problems.isEmpty()) {
        DiagnosticsSummary(true, "Всё в порядке", emptyList())
    } else {
        DiagnosticsSummary(false, "Есть проблемы: ${problems.joinToString(", ")}", problems)
    }
}

internal fun AppProcessExitRecord.isCrash(): Boolean = reason == "native_crash" || reason == "anr"

/** Ключи секций, раскрытых по умолчанию: только «Последняя ошибка», если она есть. */
internal fun defaultExpandedSections(hasLastError: Boolean): Set<String> =
    if (hasLastError) setOf(DiagnosticsSections.Error) else emptySet()

/** Раскрыта ли секция: умолчание, перевёрнутое тем, что пользователь уже нажимал. */
internal fun isSectionExpanded(key: String, defaults: Set<String>, toggled: List<String>): Boolean =
    (key in defaults) != (key in toggled)

internal fun toggleSection(key: String, toggled: List<String>): List<String> =
    if (key in toggled) toggled - key else toggled + key

internal fun VpnConnectionState.diagnosticLabel(): String = when (this) {
    VpnConnectionState.Stopped -> "VPN выключен"
    is VpnConnectionState.Starting -> "Подключение: $message"
    is VpnConnectionState.Connected -> "Подключено: $profileName"
    is VpnConnectionState.Paused -> "Пауза: $message"
    is VpnConnectionState.Stopping -> "Отключение"
    is VpnConnectionState.Reconnecting -> "Переподключение: $message"
    is VpnConnectionState.Error -> "Ошибка VPN"
}

internal fun formatDiagnosticDuration(value: Long?): String = when {
    value == null -> "не измерено"
    value < 1_000 -> "$value мс"
    else -> "%.2f с".format(Locale.US, value / 1_000.0)
}

internal fun connectionOutcomeTitle(outcome: DiagnosticAttemptOutcome, totalMillis: Long?): String =
    when (outcome) {
        DiagnosticAttemptOutcome.Running -> "Подключение выполняется"
        DiagnosticAttemptOutcome.Connected -> "Подключено за ${formatDiagnosticDuration(totalMillis)}"
        DiagnosticAttemptOutcome.Failed -> "Ошибка через ${formatDiagnosticDuration(totalMillis)}"
        DiagnosticAttemptOutcome.Cancelled -> "Попытка отменена через ${formatDiagnosticDuration(totalMillis)}"
    }

internal fun stopTitle(attempt: DiagnosticStopAttempt): String =
    if (attempt.outcome == DiagnosticStopOutcome.Running) {
        "Остановка выполняется"
    } else {
        "Остановлено за ${formatDiagnosticDuration(attempt.totalDurationMillis)}"
    }

/** Короткая строка для шапки секции, пока она свёрнута. */
internal fun connectSectionSummary(attempt: DiagnosticConnectionAttempt?): String =
    attempt?.let { connectionOutcomeTitle(it.outcome, it.totalDurationMillis) }
        ?: "Замеры появятся после следующего подключения"

internal fun stopSectionSummary(attempt: DiagnosticStopAttempt?): String =
    attempt?.let(::stopTitle) ?: "Замеры появятся после следующей остановки"

/** Одна строка таймлайна этапов — общая для подключения и остановки. */
@Immutable
internal data class StageLineUi(val text: String, val failed: Boolean)

internal fun DiagnosticStageStatus.symbol(): String = when (this) {
    DiagnosticStageStatus.Running -> "…"
    DiagnosticStageStatus.Success -> "✓"
    DiagnosticStageStatus.Recovered -> "↻"
    DiagnosticStageStatus.Failed -> "×"
    DiagnosticStageStatus.Cancelled -> "—"
}

internal fun stageLines(stages: List<DiagnosticStageTiming>): List<StageLineUi> = stages.map { stage ->
    StageLineUi(
        text = "${stage.status.symbol()} ${stage.label} — " +
            (stage.durationMillis?.let(::formatDiagnosticDuration) ?: "выполняется") +
            (stage.detail?.let { " · $it" } ?: ""),
        failed = stage.status == DiagnosticStageStatus.Failed,
    )
}

internal fun runningStageText(stages: List<DiagnosticStageTiming>): String? =
    stages.lastOrNull { it.status == DiagnosticStageStatus.Running }?.let { "Сейчас: ${it.label}" }

internal fun slowestStageText(stage: DiagnosticStageTiming?): String? = stage?.let {
    "Самый долгий этап: ${it.label} — ${formatDiagnosticDuration(it.durationMillis)}"
}

internal fun previousAttemptLine(previous: DiagnosticConnectionAttempt): String =
    "${previous.trigger}: " +
        connectionOutcomeTitle(previous.outcome, previous.totalDurationMillis) +
        (previous.failure?.let { " · ${it.supportCode}" } ?: "") +
        (previous.slowestCompletedStage?.let { " · ${it.label}" } ?: "")

internal fun logLineText(line: DiagnosticLogLine): String =
    "${line.levelName}/${line.category.code}: ${line.message}" +
        if (line.repeatCount > 1) " ×${line.repeatCount}" else ""

internal fun runtimeErrorText(record: RecordedRuntimeFailure): String {
    val error = record.failure
    return "[${error.component}][${error.stage}] ${error.message}\n${error.code}; событий: ${record.occurrences}"
}

@Immutable
internal data class PolicyText(val text: String, val warning: Boolean)

internal fun vpnPolicyText(policy: DiagnosticVpnPolicy?): PolicyText = when {
    policy == null -> PolicyText("Always-on и блокировка VPN: статус появится при запуске VPN.", false)
    !policy.statusAvailable ->
        PolicyText("Always-on и блокировка VPN: Android 9 и старше не сообщает статус.", false)
    policy.alwaysOn || policy.lockdown -> PolicyText(
        "Always-on: ${policy.alwaysOn.yesNo()} · блокировка: ${policy.lockdown.yesNo()}. " +
            "Эти режимы пока не поддерживаются — отключите их в настройках Android.",
        true,
    )
    else -> PolicyText("Always-on: нет · блокировка: нет", false)
}

internal fun networkLines(network: DiagnosticNetworkState?): List<String> =
    if (network == null) {
        listOf("Состояние появится при подключении или экспорте отчёта.")
    } else {
        listOf(
            "${network.transport} · ${network.interfaceName ?: "интерфейс не определён"}",
            "Validated: ${network.validated.yesNo()} · Metered: ${network.metered.yesNo()}",
            "Private DNS: ${network.privateDnsMode} · active: ${network.privateDnsActive.yesNo()}",
        )
    }

internal fun logsSectionHint(streamActive: Boolean): String = if (streamActive) {
    "Поток логов ядра работает, пока открыт этот экран."
} else {
    "Поток логов ядра сейчас не работает; в памяти только последние строки текущего запуска."
}

internal fun logsToggleLabel(expanded: Boolean, count: Int): String =
    if (expanded) "Скрыть ($count)" else "Показать ($count)"

internal fun Boolean.yesNo(): String = if (this) "да" else "нет"
