package io.github.zapretkvn.android.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.diagnostics.DiagnosticAttemptOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticConnectionAttempt
import io.github.zapretkvn.android.diagnostics.DiagnosticState
import io.github.zapretkvn.android.diagnostics.DiagnosticStopAttempt
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.DiagnosticShareButton
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.core.designsystem.FactRow
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.toneColor
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DiagnosticsRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    ReportDiagnosticsShown(nav)
    val vpnState by deps.vpn.state.collectAsStateWithLifecycle()
    val diagnostics by deps.vpn.diagnostics.collectAsStateWithLifecycle()
    DiagnosticsScreen(
        contentPadding = padding,
        vpnState = vpnState,
        diagnostics = diagnostics,
        onCreateDiagnosticShare = deps.createDiagnosticShare,
        onClearDnsCache = deps.vpn::clearDnsCache,
    )
}

@Composable
internal fun DiagnosticsScreen(
    contentPadding: PaddingValues,
    vpnState: VpnConnectionState,
    diagnostics: DiagnosticState,
    onCreateDiagnosticShare: suspend () -> Intent,
    onClearDnsCache: () -> Unit,
) {
    var toggled by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var logsExpanded by rememberSaveable { mutableStateOf(false) }
    val hasError = diagnostics.lastFailure != null
    val defaults = remember(hasError) { defaultExpandedSections(hasError) }
    fun expanded(key: String) = isSectionExpanded(key, defaults, toggled)
    val onToggle: (String) -> Unit = { key -> toggled = toggleSection(key, toggled) }

    val summary = summarizeDiagnostics(vpnState, diagnostics)
    // Слияние и сортировка 80 строк — только когда лог раскрыт и строки менялись.
    val logs = remember(logsExpanded, diagnostics.applicationLogs, diagnostics.coreLogs) {
        if (logsExpanded) diagnostics.logs else emptyList()
    }
    val logCount = if (logsExpanded) logs.size else diagnostics.applicationLogs.size + diagnostics.coreLogs.size

    SettingsPageList(contentPadding, tag = "diagnostics-screen") {
        item(key = "summary", contentType = "summary") {
            SummaryCard(summary, vpnState, onCreateDiagnosticShare)
        }

        item(key = DiagnosticsSections.Error, contentType = "section") {
            val failure = diagnostics.lastFailure
            CollapsibleCard(
                title = "Последняя ошибка",
                summary = failure?.let { "${it.supportCode} · ${it.type.title}" }
                    ?: "Ошибок текущего запуска нет",
                expanded = expanded(DiagnosticsSections.Error),
                onToggle = { onToggle(DiagnosticsSections.Error) },
            ) {
                if (failure == null) {
                    Text("Ошибок текущего запуска нет.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "${failure.supportCode} · ${failure.type.title}",
                        modifier = Modifier.testTag("diagnostic-error-type"),
                        color = toneColor(Tone.Bad),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(failure.message, style = MaterialTheme.typography.bodyMedium)
                    failure.technicalDetail?.let { detail ->
                        HintText("Технически: $detail")
                    }
                }
            }
        }

        item(key = DiagnosticsSections.Connect, contentType = "section") {
            CollapsibleCard(
                title = "Подключение (этапы)",
                summary = connectSectionSummary(diagnostics.connectionAttempt),
                expanded = expanded(DiagnosticsSections.Connect),
                onToggle = { onToggle(DiagnosticsSections.Connect) },
                headerTag = "diagnostic-timeline-toggle",
            ) {
                ConnectionContent(diagnostics)
            }
        }

        item(key = DiagnosticsSections.Stop, contentType = "section") {
            CollapsibleCard(
                title = "Остановка (этапы)",
                summary = stopSectionSummary(diagnostics.stopAttempt),
                expanded = expanded(DiagnosticsSections.Stop),
                onToggle = { onToggle(DiagnosticsSections.Stop) },
                headerTag = "diagnostic-stop-timeline-toggle",
            ) {
                StopContent(diagnostics.stopAttempt)
            }
        }

        item(key = DiagnosticsSections.Crash, contentType = "section") {
            val hasCrash = diagnostics.previousCrash != null || diagnostics.previousProcessExit != null
            CollapsibleCard(
                title = "Предыдущий сбой",
                summary = if (hasCrash) "Есть сохранённый сбой приложения" else "Сбоев не было",
                expanded = expanded(DiagnosticsSections.Crash),
                onToggle = { onToggle(DiagnosticsSections.Crash) },
            ) {
                CrashContent(diagnostics)
            }
        }

        item(key = DiagnosticsSections.Environment, contentType = "section") {
            CollapsibleCard(
                title = "Среда",
                summary = "Zapret KVN ${AppInfo.versionName} · Android ${AppInfo.androidRelease}",
                expanded = expanded(DiagnosticsSections.Environment),
                onToggle = { onToggle(DiagnosticsSections.Environment) },
            ) {
                EnvironmentContent(diagnostics)
            }
        }

        item(key = DiagnosticsSections.Network, contentType = "section") {
            val lines = networkLines(diagnostics.network)
            CollapsibleCard(
                title = "Сеть Android",
                summary = lines.first(),
                expanded = expanded(DiagnosticsSections.Network),
                onToggle = { onToggle(DiagnosticsSections.Network) },
            ) {
                lines.forEachIndexed { index, line ->
                    Text(
                        line,
                        style = if (index == 0) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                    )
                }
            }
        }

        item(key = "logs", contentType = "logs-header") {
            ZapretCard {
                Text("Логи", style = MaterialTheme.typography.titleMedium)
                HintText(logsSectionHint(diagnostics.logStreamActive))
                HintText(
                    "Ядро: получено ${diagnostics.coreLogStats.receivedLines}, " +
                        "схлопнуто ${diagnostics.coreLogStats.coalescedLines}, " +
                        "отброшено ${diagnostics.coreLogStats.droppedLines}.",
                )
                OutlinedButton(
                    onClick = { logsExpanded = !logsExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("diagnostic-logs-toggle"),
                ) { Text(logsToggleLabel(logsExpanded, logCount)) }
                if (logsExpanded && logs.isEmpty()) Text("Строк логов пока нет.")
            }
        }
        itemsIndexed(
            items = logs,
            key = { index, _ -> "log-$index" },
            contentType = { _, _ -> "log-line" },
        ) { _, line ->
            Text(
                logLineText(line),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = Spacing.m, vertical = 2.dp),
            )
        }

        item(key = DiagnosticsSections.CoreErrors, contentType = "section") {
            CollapsibleCard(
                title = "Ошибки ядра",
                summary = "Записей: ${diagnostics.runtimeErrors.size}",
                expanded = expanded(DiagnosticsSections.CoreErrors),
                onToggle = { onToggle(DiagnosticsSections.CoreErrors) },
            ) {
                HintText("Исходные сообщения ядра. Секреты скрыты; неизвестные ошибки тоже сохраняются.")
                if (diagnostics.runtimeErrors.isEmpty()) {
                    Text("Ошибок ядра нет.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            diagnostics.runtimeErrors.forEach { record ->
                                Text(
                                    runtimeErrorText(record),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }

        diagnostics.effectiveOverlay?.let { overlay ->
            item(key = DiagnosticsSections.Overlay, contentType = "section") {
                CollapsibleCard(
                    title = "Effective overlay",
                    summary = "Структура управляемых настроек ядра",
                    expanded = expanded(DiagnosticsSections.Overlay),
                    onToggle = { onToggle(DiagnosticsSections.Overlay) },
                ) {
                    HintText("Только структура managed zapret-* без адресов, правил сопоставления и учётных данных.")
                    Text(
                        overlay,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        item(key = "footer", contentType = "footer") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(
                    onClick = onClearDnsCache,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Очистить DNS-кэш и перезапустить ядро") }
                HintText("Лог на диск не пишется; временный отчёт удаляется при следующем запуске.")
            }
        }
    }
}

@Composable
private fun SummaryCard(
    summary: DiagnosticsSummary,
    vpnState: VpnConnectionState,
    onCreateDiagnosticShare: suspend () -> Intent,
) {
    ZapretCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Icon(
                if (summary.ok) ZapretIcons.Ok else ZapretIcons.Warning,
                contentDescription = null,
                tint = toneColor(if (summary.ok) Tone.Good else Tone.Bad),
                modifier = Modifier.size(24.dp),
            )
            Text(
                summary.headline,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Текущее состояние",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(vpnState.diagnosticLabel(), style = MaterialTheme.typography.bodyLarge)
        }
        DiagnosticShareButton(
            label = "Экспортировать диагностику",
            onCreateDiagnosticShare = onCreateDiagnosticShare,
            modifier = Modifier.fillMaxWidth(),
        )
        HintText("Создаёт временный отчёт без секретов и открывает системное окно отправки.")
    }
}

/** Таймлайн этапов — один компонент и для подключения, и для остановки. */
@Composable
internal fun StageTimeline(lines: List<StageLineUi>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        lines.forEach { line ->
            Text(
                line.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (line.failed) toneColor(Tone.Bad) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionContent(diagnostics: DiagnosticState) {
    val attempt: DiagnosticConnectionAttempt? = diagnostics.connectionAttempt
    if (attempt == null) {
        Text("Замеры появятся после следующей попытки подключения.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(
        connectionOutcomeTitle(attempt.outcome, attempt.totalDurationMillis),
        style = MaterialTheme.typography.titleSmall,
        color = if (attempt.outcome == DiagnosticAttemptOutcome.Failed) {
            toneColor(Tone.Bad)
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
    HintText("Триггер: ${attempt.trigger}")
    runningStageText(attempt.stages)?.let { HintText(it) }
    slowestStageText(attempt.slowestCompletedStage)?.let {
        HintText(it, modifier = Modifier.testTag("diagnostic-slowest-stage"))
    }
    StageTimeline(remember(attempt.stages) { stageLines(attempt.stages) })
    if (diagnostics.previousConnectionAttempts.isNotEmpty()) {
        Text("Предыдущие попытки", style = MaterialTheme.typography.titleSmall)
        diagnostics.previousConnectionAttempts.takeLast(2).asReversed().forEach { previous ->
            Text(
                previousAttemptLine(previous),
                style = MaterialTheme.typography.bodySmall,
                color = if (previous.outcome == DiagnosticAttemptOutcome.Failed) {
                    toneColor(Tone.Bad)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    HintText("Замеры делаются только в моменты запуска: без таймеров и фонового трафика.")
}

@Composable
private fun StopContent(attempt: DiagnosticStopAttempt?) {
    if (attempt == null) {
        Text("Замеры появятся после следующей остановки VPN.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(stopTitle(attempt), style = MaterialTheme.typography.titleSmall)
    runningStageText(attempt.stages)?.let { HintText(it) }
    slowestStageText(attempt.slowestCompletedStage)?.let {
        HintText(it, modifier = Modifier.testTag("diagnostic-stop-slowest-stage"))
    }
    StageTimeline(remember(attempt.stages) { stageLines(attempt.stages) })
    HintText("Туннель закрывается раньше клиентов и ядра; каждый этап измеряется отдельно.")
}

@Composable
private fun CrashContent(diagnostics: DiagnosticState) {
    var stackExpanded by rememberSaveable { mutableStateOf(false) }
    val crash = diagnostics.previousCrash
    if (crash == null) {
        Text("Сохранённых сбоев приложения нет.", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(
            crash.exceptionType,
            color = toneColor(Tone.Bad),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("diagnostic-previous-crash"),
        )
        Text(formatDiagnosticTimestamp(crash.occurredAtEpochMillis), style = MaterialTheme.typography.bodyMedium)
        crash.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (crash.stack.isNotEmpty()) {
            TextButton(onClick = { stackExpanded = !stackExpanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (stackExpanded) "Свернуть стек" else "Показать короткий стек")
            }
        }
        if (stackExpanded) {
            Text(
                crash.stack.joinToString("\n") { frame ->
                    "${frame.className}.${frame.methodName}:${frame.lineNumber}"
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        HintText("Хранится один последний сбой без сетевого лога.")
    }
    diagnostics.previousProcessExit?.let { exit ->
        Text("Предыдущее завершение процесса", style = MaterialTheme.typography.titleSmall)
        Text(
            "${exit.reason} · status ${exit.status}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (exit.isCrash()) toneColor(Tone.Bad) else MaterialTheme.colorScheme.onSurface,
        )
        HintText(
            "${formatDiagnosticTimestamp(exit.occurredAtEpochMillis)} · " +
                "PSS ${exit.pssKilobytes} KiB · RSS ${exit.rssKilobytes} KiB",
        )
        exit.description?.let { HintText(it) }
    }
    if (AppInfo.sdkInt < 30) {
        HintText("История аварийных завершений от Android доступна начиная с версии 11.")
    }
}

@Composable
private fun EnvironmentContent(diagnostics: DiagnosticState) {
    FactRow("Приложение", "Zapret KVN ${AppInfo.versionName} (${AppInfo.versionCode})")
    FactRow("Ядро", "${AppInfo.coreTag} · ${AppInfo.coreCommit.take(12)}")
    FactRow("Hysteria", "${AppInfo.hysteriaTag} · ${AppInfo.hysteriaCommit.take(12)}")
    FactRow("Патч ядра", AppInfo.corePatch.take(12))
    FactRow("Android", "${AppInfo.androidRelease} · API ${AppInfo.sdkInt}")
    val policy = vpnPolicyText(diagnostics.vpnPolicy)
    Text(
        policy.text,
        style = MaterialTheme.typography.bodySmall,
        color = if (policy.warning) toneColor(Tone.Bad) else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatDiagnosticTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(epochMillis))
