package io.github.zapretkvn.android.ui.routing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.apps.AppsUiState
import io.github.zapretkvn.android.routing.ManagedRoutingRule
import io.github.zapretkvn.android.routing.RoutingInspection
import io.github.zapretkvn.android.routing.RoutingMatchType
import io.github.zapretkvn.android.routing.RoutingPreset
import io.github.zapretkvn.android.routing.RoutingRuleAction
import io.github.zapretkvn.android.routing.RoutingUiState
import io.github.zapretkvn.core.designsystem.CardTitle
import io.github.zapretkvn.core.designsystem.EmptyState
import io.github.zapretkvn.core.designsystem.SectionHeader
import io.github.zapretkvn.core.designsystem.SettingsRow
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ToneBadge
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.onToneContainer
import io.github.zapretkvn.core.designsystem.toneColor
import io.github.zapretkvn.core.designsystem.toneContainer

private class EditedRule(val index: Int?, val rule: ManagedRoutingRule)

/** Вкладка «Маршруты». Не знает про ViewModel: получает готовое состояние и лямбды. */
@Composable
internal fun RoutingScreen(
    contentPadding: PaddingValues,
    appsState: AppsUiState,
    routingState: RoutingUiState,
    onScopeModeChange: (AppScopeMode) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenBlockPicker: () -> Unit,
    onApplyPreset: (RoutingPreset) -> Unit,
    onSaveRule: (Int?, ManagedRoutingRule) -> Unit,
    onDeleteRule: (Int) -> Unit,
    onOpenJson: () -> Unit,
    onOpenServers: () -> Unit,
) {
    var presetOpen by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditedRule?>(null) }

    val scope = remember(appsState) { scopeSummary(appsState) }
    val block = remember(appsState.blockedPackages) { blockSummary(appsState.blockedPackages.size) }
    val inspection = routingState.inspection
    val ruleRows = remember(inspection?.rules) { inspection?.rules.orEmpty().map(::ruleRow) }
    val hasProfile = routingState.activeProfileId != null

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag("routing-list"),
        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
    ) {
        item(key = "scope-header", contentType = "section") { SectionHeader("Область VPN") }
        item(key = "scope", contentType = "card") {
            ScopeCard(
                scopeMode = appsState.scopeMode,
                scope = scope,
                onScopeModeChange = onScopeModeChange,
                onOpenPicker = onOpenPicker,
            )
        }
        item(key = "block", contentType = "card") {
            ZapretCard {
                CardTitle("Блокировка приложений")
                Text(
                    block.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = toneColor(block.tone),
                )
                Text(
                    "Отмеченные приложения не выходят в сеть, пока подключён Zapret KVN.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onOpenBlockPicker,
                    modifier = Modifier.semantics {
                        contentDescription = "Открыть выбор заблокированных приложений"
                    },
                ) { Text("Блокировать приложения") }
            }
        }

        item(key = "traffic-header", contentType = "section") { SectionHeader("Правило трафика") }
        item(key = "mode", contentType = "card") {
            when {
                !hasProfile -> NoProfileCard(onOpenServers)
                inspection == null -> ModeSkeleton()
                else -> ModeCard(inspection = inspection, onChange = { presetOpen = true })
            }
        }

        if (hasProfile && inspection != null) {
            item(key = "rules-header", contentType = "rules-header") {
                RulesHeader(
                    count = ruleRows.size,
                    addEnabled = !routingState.loading,
                    onAdd = {
                        editing = EditedRule(
                            index = null,
                            rule = ManagedRoutingRule(
                                RoutingMatchType.Domain,
                                emptyList(),
                                RoutingRuleAction.Proxy,
                            ),
                        )
                    },
                )
            }
            if (ruleRows.isEmpty()) {
                item(key = "rules-empty", contentType = "hint") {
                    Text(
                        "Пользовательских правил нет. Добавьте домен, IP или набор правил " +
                            "и выберите, куда направить такой трафик.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs),
                    )
                }
            }
            itemsIndexed(
                items = ruleRows,
                key = { index, row -> "rule-$index-${row.rule.hashCode()}" },
                contentType = { _, _ -> "rule" },
            ) { index, row ->
                RuleCard(row = row, onClick = { editing = EditedRule(index, row.rule) })
            }
        }

        if (hasProfile) {
            item(key = "advanced", contentType = "card") {
                AdvancedCard(
                    open = advancedOpen,
                    onToggle = { advancedOpen = !advancedOpen },
                    summary = inspection?.let { routingSummary(appsState.scopeMode, it.summary) },
                    order = evaluationOrder(appsState.blockedPackages.isNotEmpty()),
                    diff = routingState.managedDiff,
                    jsonEnabled = !routingState.loading,
                    onOpenJson = onOpenJson,
                )
            }
        }
    }

    if (presetOpen && inspection != null) {
        PresetPicker(
            selected = inspection.preset,
            onSelect = onApplyPreset,
            onDismiss = { presetOpen = false },
        )
    }
    editing?.let { edited ->
        RuleEditorSheet(
            initial = edited.rule,
            onDismiss = { editing = null },
            onSave = {
                onSaveRule(edited.index, it)
                editing = null
            },
            onDelete = edited.index?.let { index ->
                {
                    onDeleteRule(index)
                    editing = null
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeCard(
    scopeMode: AppScopeMode,
    scope: ScopeSummary,
    onScopeModeChange: (AppScopeMode) -> Unit,
    onOpenPicker: () -> Unit,
) {
    ZapretCard {
        CardTitle("Что идёт через VPN")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = scopeMode == AppScopeMode.Include,
                onClick = { onScopeModeChange(AppScopeMode.Include) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Только выбранные") }
            SegmentedButton(
                selected = scopeMode == AppScopeMode.Exclude,
                onClick = { onScopeModeChange(AppScopeMode.Exclude) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Все кроме") }
        }
        Text(
            scope.countText,
            style = MaterialTheme.typography.titleSmall,
            color = toneColor(scope.countTone),
            modifier = Modifier.testTag("selected-app-count"),
        )
        Text(
            scope.caption,
            style = MaterialTheme.typography.bodyMedium,
            color = toneColor(scope.captionTone),
        )
        scope.missingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (scope.needsSelection) {
            Notice(text = scope.warningText, tone = Tone.Warning, actionLabel = "Выбрать", onAction = onOpenPicker)
        }
        Button(
            onClick = onOpenPicker,
            modifier = Modifier.semantics { contentDescription = scope.pickerDescription },
        ) { Text(scope.pickerLabel) }
    }
}

/** Плашка-подсказка: цвет усиливает слово и иконку, но не заменяет их. */
@Composable
private fun Notice(text: String, tone: Tone, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(toneContainer(tone), RoundedCornerShape(12.dp))
            .padding(start = Spacing.m, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(
            ZapretIcons.Warning,
            contentDescription = null,
            tint = onToneContainer(tone),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = onToneContainer(tone),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun ModeCard(inspection: RoutingInspection, onChange: () -> Unit) {
    ZapretCard {
        CardTitle("Режим")
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(inspection.preset.title, style = MaterialTheme.typography.titleMedium)
            Text(
                inspection.preset.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        remoteRuleSetNote(inspection.remoteRuleSetCount)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = toneColor(Tone.Warning))
        }
        OutlinedButton(onClick = onChange, modifier = Modifier.testTag("routing-change-preset")) {
            Text("Изменить режим")
        }
    }
}

/** Заготовка на время чтения профиля: статичные плашки, без мерцания. */
@Composable
private fun ModeSkeleton() {
    ZapretCard(modifier = Modifier.semantics { contentDescription = "Читаем маршруты профиля" }) {
        CardTitle("Режим")
        SkeletonBar(widthFraction = 0.5f, height = 20.dp)
        SkeletonBar(widthFraction = 0.85f, height = 14.dp)
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun NoProfileCard(onOpenServers: () -> Unit) {
    ZapretCard {
        EmptyState(
            title = "Сначала добавьте профиль, чтобы настроить маршруты",
            body = "Режим и правила применяются поверх профиля. Без него настраивать пока нечего.",
            icon = ZapretIcons.Route,
        ) {
            Button(onClick = onOpenServers) { Text("Перейти к серверам") }
        }
    }
}

@Composable
private fun RulesHeader(count: Int, addEnabled: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        SectionHeader(
            text = if (count > 0) "Правила ($count)" else "Правила",
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = onAdd,
            enabled = addEnabled,
            modifier = Modifier.testTag("routing-add-rule"),
        ) {
            Icon(ZapretIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text("Правило")
        }
    }
}

@Composable
private fun RuleCard(row: RuleRowModel, onClick: () -> Unit) {
    ZapretCard(
        onClick = onClick,
        verticalSpacing = Spacing.xs,
        modifier = Modifier.semantics { contentDescription = "Редактировать правило: ${row.sentence}" },
    ) {
        Text(row.value, style = MaterialTheme.typography.titleSmall)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(
                "${row.kind} →",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToneBadge(row.actionTitle, row.tone)
        }
        row.outbound?.let {
            Text(
                "Исходящее соединение: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AdvancedCard(
    open: Boolean,
    onToggle: () -> Unit,
    summary: String?,
    order: String,
    diff: String?,
    jsonEnabled: Boolean,
    onOpenJson: () -> Unit,
) {
    ZapretCard(contentPadding = PaddingValues(0.dp), verticalSpacing = 0.dp) {
        SettingsRow(
            title = "Дополнительно",
            subtitle = "Итог, последние изменения, исходный JSON",
            onClick = onToggle,
            trailing = {
                Icon(
                    if (open) ZapretIcons.ExpandLess else ZapretIcons.ExpandMore,
                    contentDescription = if (open) "Свернуть" else "Развернуть",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        AnimatedVisibility(visible = open) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                summary?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text("Итог", style = MaterialTheme.typography.titleSmall)
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    order,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                diff?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text("Последний diff zapret-*", style = MaterialTheme.typography.titleSmall)
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenJson,
                    enabled = jsonEnabled,
                    modifier = Modifier.semantics {
                        contentDescription = "Открыть редактор исходного JSON активного профиля"
                    },
                ) { Text("Исходный JSON профиля") }
                Text(
                    "Общие правила накладываются на исходный JSON при запуске VPN и в нём не сохраняются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
