package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import io.github.zapretkvn.core.designsystem.Spacing

/** Режим «Свой порядок…» в списке режимов: открывает выбор уровней. */
const val CUSTOM_GROUPING_ROW = "custom"

/**
 * «Группировка» списка серверов: готовые режимы одним нажатием и «Свой порядок…» — до трёх уровней папок в порядке,
 * который выбирает пользователь (владелец, 2026-11-27). Режим сохраняется в настройках и переживает перезапуск.
 * Как в Windows-версии: список режимов, а свой порядок — отдельное небольшое окно.
 */
@Composable
fun GroupingDialog(
    current: String,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val normalized = ServerGrouping.normalize(current)
    var custom by rememberSaveable { mutableStateOf(false) }
    if (custom) {
        CustomOrderDialog(current = normalized, onApply = onChoose, onBack = { custom = false })
        return
    }
    val isPreset = ServerGrouping.presets.any { it.first == normalized }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Группировка серверов") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ServerGrouping.presets.forEach { (mode, label) ->
                    ModeRow(label, selected = mode == normalized, tag = "grouping-$mode") { onChoose(mode) }
                }
                ModeRow(
                    label = if (isPreset) "Свой порядок…" else "Свой: ${ServerGrouping.title(normalized)}",
                    selected = !isPreset,
                    tag = "grouping-$CUSTOM_GROUPING_ROW",
                ) { custom = true }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun ModeRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(Spacing.s))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** До трёх уровней папок: по одному ряду чипов на уровень, «—» — уровня нет. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomOrderDialog(current: String, onApply: (String) -> Unit, onBack: () -> Unit) {
    val initial = ServerGrouping.levels(current).orEmpty()
    var picks by remember { mutableStateOf(List(ServerGrouping.MAX_LEVELS) { initial.getOrNull(it) }) }
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("Свой порядок") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    "Папки вкладываются друг в друга в выбранном порядке: например, «Страна › Сервер › Протокол». " +
                        "Внутри последней папки лежат серверы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                picks.forEachIndexed { position, picked ->
                    Text("Уровень ${position + 1}", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        FilterChip(
                            selected = picked == null,
                            onClick = { picks = picks.toMutableList().also { it[position] = null } },
                            label = { Text("—") },
                            modifier = Modifier.testTag("grouping-level-$position-none"),
                        )
                        GroupLevel.entries.forEach { level ->
                            FilterChip(
                                selected = picked == level,
                                // a level can be used once: picking it here clears it from the other positions
                                onClick = { picks = picks.map { if (it == level) null else it }.toMutableList().also { it[position] = level } },
                                label = { Text(level.title) },
                                modifier = Modifier.testTag("grouping-level-$position-${level.id}"),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(ServerGrouping.modeOf(picks)) },
                modifier = Modifier.testTag("grouping-apply-custom"),
            ) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("Назад") } },
    )
}
