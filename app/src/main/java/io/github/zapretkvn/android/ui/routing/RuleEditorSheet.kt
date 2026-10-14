package io.github.zapretkvn.android.ui.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.zapretkvn.android.routing.ManagedRoutingRule
import io.github.zapretkvn.android.routing.RoutingMatchType
import io.github.zapretkvn.android.routing.RoutingRuleAction
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.toneColor

/**
 * Редактор правила в нижнем листе. Сверху живой предпросмотр: то, что будет сохранено,
 * читается одним предложением ещё до нажатия «Сохранить».
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RuleEditorSheet(
    initial: ManagedRoutingRule,
    onDismiss: () -> Unit,
    onSave: (ManagedRoutingRule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var matchType by remember(initial) { mutableStateOf(initial.matchType) }
    var action by remember(initial) { mutableStateOf(initial.action) }
    var valuesText by remember(initial) { mutableStateOf(initial.values.joinToString("\n")) }
    var outbound by remember(initial) { mutableStateOf(initial.outboundTag.orEmpty()) }
    val parsed = remember(valuesText) { parseRuleValues(valuesText) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Поля прокручиваются, а «Отмена/Сохранить» закреплены внизу над клавиатурой:
        // иначе при открытой клавиатуре кнопку пришлось бы искать прокруткой.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Spacing.l)
                .padding(bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Text(
                    if (initial.values.isEmpty()) "Новое правило" else "Редактировать правило",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        "Так это прочитается",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        rulePreview(matchType, parsed, action),
                        style = MaterialTheme.typography.titleSmall,
                        color = toneColor(if (parsed.isEmpty()) Tone.Neutral else ruleTone(action)),
                    )
                }

                Text("Что искать", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    RoutingMatchType.entries.forEach { type ->
                        FilterChip(
                            selected = matchType == type,
                            onClick = { matchType = type },
                            label = { Text(type.title) },
                        )
                    }
                }
                OutlinedTextField(
                    value = valuesText,
                    onValueChange = { valuesText = it },
                    label = { Text("По одному значению на строку") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("routing-rule-values"),
                )

                Text("Что делать", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    RoutingRuleAction.entries.forEach { item ->
                        FilterChip(
                            selected = action == item,
                            onClick = { action = item },
                            label = { Text(item.title) },
                        )
                    }
                }
                if (action == RoutingRuleAction.Proxy) {
                    OutlinedTextField(
                        value = outbound,
                        onValueChange = { outbound = it },
                        label = { Text("Исходящее соединение (пусто — выбранный VPN)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (isDomainBlock(matchType, action)) {
                    Text(
                        "Будут созданы DNS reject и route reject. Встроенный DoH приложения " +
                            "может скрыть совпадение только по домену.",
                        style = MaterialTheme.typography.bodySmall,
                        color = toneColor(Tone.Warning),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Удалить", color = toneColor(Tone.Bad))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Отмена") }
                Button(
                    onClick = {
                        onSave(
                            ManagedRoutingRule(
                                matchType = matchType,
                                values = parsed,
                                action = action,
                                outboundTag = outbound.trim().takeIf(String::isNotEmpty),
                            ),
                        )
                    },
                    enabled = parsed.isNotEmpty(),
                ) { Text("Сохранить") }
            }
        }
    }
}
