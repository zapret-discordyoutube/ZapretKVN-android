package io.github.zapretkvn.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.routing.ManagedRoutingRule
import io.github.zapretkvn.android.routing.RoutingMatchType
import io.github.zapretkvn.android.routing.RoutingPreset
import io.github.zapretkvn.android.routing.RoutingRuleAction
import io.github.zapretkvn.android.routing.RoutingUiState
import io.github.zapretkvn.android.routing.RoutingViewModel
import io.github.zapretkvn.android.vpn.AppScopeMode
import io.github.zapretkvn.android.vpn.AppsUiState
import io.github.zapretkvn.android.vpn.AppsViewModel

@Composable
fun RoutingScreen(
    contentPadding: PaddingValues,
    appsState: AppsUiState,
    appsViewModel: AppsViewModel,
    routingState: RoutingUiState,
    routingViewModel: RoutingViewModel,
    onOpenPicker: () -> Unit,
    onOpenBlockPicker: () -> Unit,
    onOpenAdvancedJson: () -> Unit,
) {
    var presetDialog by remember { mutableStateOf(false) }
    var editedRule by remember { mutableStateOf<Pair<Int?, ManagedRoutingRule>?>(null) }
    val inspection = routingState.inspection

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag("routing-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "scope") {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Область VPN",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = appsState.scopeMode == AppScopeMode.Include,
                            onClick = { appsViewModel.setScopeMode(AppScopeMode.Include) },
                            label = { Text("Только выбранные") },
                        )
                        FilterChip(
                            selected = appsState.scopeMode == AppScopeMode.Exclude,
                            onClick = { appsViewModel.setScopeMode(AppScopeMode.Exclude) },
                            label = { Text("Приложения напрямую") },
                        )
                    }
                    val scopeReady = when (appsState.scopeMode) {
                        AppScopeMode.Include -> {
                            appsState.allowedPackages.isNotEmpty() ||
                                appsState.blockedPackages.isNotEmpty()
                        }
                        AppScopeMode.Exclude -> appsState.allowedPackages.isNotEmpty()
                    }
                    Text(
                        when (appsState.scopeMode) {
                            AppScopeMode.Include -> if (appsState.allowedPackages.isEmpty()) {
                                if (appsState.blockedPackages.isEmpty()) {
                                    "Приложения не выбраны"
                                } else {
                                    "TUN используется только для блокировки"
                                }
                            } else {
                                "Через VPN: ${appsState.allowedPackages.size}"
                            }
                            AppScopeMode.Exclude -> if (appsState.allowedPackages.isEmpty()) {
                                "Приложения напрямую не выбраны: запуск заблокирован"
                            } else {
                                "Напрямую вне TUN: ${appsState.allowedPackages.size}"
                            }
                        },
                        modifier = Modifier.testTag("selected-app-count"),
                        color = if (scopeReady) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        if (appsState.scopeMode == AppScopeMode.Include) {
                            "Android отсекает остальные приложения до TUN; отдельно заблокированные " +
                                "приложения допускаются в TUN только до reject-правила."
                        } else {
                            "Режим «Приложения напрямую»: отмеченные приложения остаются вне TUN; " +
                                "все остальные попадут в VPN."
                        },
                        color = if (appsState.scopeMode == AppScopeMode.Exclude) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    val missingPackageCount = (
                        appsState.missingAllowedPackages + appsState.missingBlockedPackages
                    ).size
                    if (missingPackageCount > 0) {
                        Text(
                            "Недоступных пакетов: $missingPackageCount. Они будут пропущены при подключении.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = onOpenPicker,
                        enabled = !appsState.loading,
                        modifier = Modifier.semantics {
                            contentDescription = if (appsState.scopeMode == AppScopeMode.Include) {
                                "Открыть выбор приложений для VPN"
                            } else {
                                "Открыть выбор приложений напрямую вне VPN"
                            }
                        },
                    ) {
                        Text(
                            if (appsState.scopeMode == AppScopeMode.Include) {
                                "Выбрать приложения"
                            } else {
                                "Выбрать приложения напрямую"
                            },
                        )
                    }
                    Text(
                        if (appsState.blockedPackages.isEmpty()) {
                            "Блокировка приложений: список пуст"
                        } else {
                            "Заблокировано приложений: ${appsState.blockedPackages.size}"
                        },
                        color = if (appsState.blockedPackages.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    OutlinedButton(
                        onClick = onOpenBlockPicker,
                        enabled = !appsState.loading,
                        modifier = Modifier.semantics {
                            contentDescription = "Открыть выбор заблокированных приложений"
                        },
                    ) {
                        Text("Блокировать приложения")
                    }
                }
            }
        }

        item(key = "traffic") {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Правило трафика",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "Общая политика: применяется поверх JSON любого выбранного профиля.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (routingState.activeProfileId == null) {
                        Text("Сначала выберите активный профиль.", color = MaterialTheme.colorScheme.error)
                    } else if (inspection == null || routingState.loading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text("Подготовка общих правил…")
                        }
                    } else {
                        Text(inspection.preset.title, fontWeight = FontWeight.Bold)
                        Text(inspection.preset.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = { presetDialog = true },
                            modifier = Modifier.testTag("routing-change-preset"),
                        ) { Text("Изменить режим") }
                        if (inspection.remoteRuleSetCount > 0) {
                            Text(
                                "Пользовательских remote rule-set: ${inspection.remoteRuleSetCount}. Пресеты их не загружают и не переписывают.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Text("Итог", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (appsState.scopeMode == AppScopeMode.Include) {
                                inspection.summary
                            } else {
                                inspection.summary.substringBefore("Остальные приложения:") +
                                    "Приложения напрямую: вне VPN и TUN; все остальные входят в TUN."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (appsState.blockedPackages.isEmpty()) {
                                "Порядок: reject → direct → VPN → final. Package route rules не создаются."
                            } else {
                                "Порядок: блокировка package → reject → direct → VPN → final."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenAdvancedJson,
                        enabled = routingState.activeProfileId != null && !routingState.loading,
                        modifier = Modifier.semantics {
                            contentDescription = "Открыть расширенный редактор JSON активного профиля"
                        },
                    ) {
                        Text("JSON активного профиля")
                    }
                }
            }
        }

        if (inspection != null) {
            item(key = "rules-title") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Правила",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "Домены, IP/CIDR и rule-set · для всех профилей",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            editedRule = null to ManagedRoutingRule(
                                RoutingMatchType.Domain,
                                emptyList(),
                                RoutingRuleAction.Proxy,
                            )
                        },
                        enabled = !routingState.loading,
                        modifier = Modifier.testTag("routing-add-rule"),
                    ) { Text("Добавить") }
                }
            }

            itemsIndexed(inspection.rules, key = { index, rule -> "$index-${rule.hashCode()}" }) { index, rule ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clickable { editedRule = index to rule }
                        .semantics {
                            contentDescription =
                                "Редактировать правило ${rule.action.title}: ${rule.values.joinToString()}"
                        },
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(rule.action.title, color = actionColor(rule.action), fontWeight = FontWeight.Bold)
                        Text("${rule.matchType.title}: ${rule.values.joinToString()}")
                        val outbound = rule.outboundTag
                        if (outbound != null) {
                            Text("Outbound: $outbound", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (inspection.rules.isEmpty()) {
                item(key = "rules-empty") {
                    Text(
                        "Пользовательских правил нет.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        routingState.managedDiff?.let { diff ->
            item(key = "diff") {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Последний diff zapret-*", style = MaterialTheme.typography.titleMedium)
                        Text(diff, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (presetDialog && inspection != null) {
        PresetDialog(
            selected = inspection.preset,
            onDismiss = { presetDialog = false },
            onSelect = {
                routingViewModel.applyPreset(it)
                presetDialog = false
            },
        )
    }
    editedRule?.let { (index, rule) ->
        RuleEditorDialog(
            initial = rule,
            onDismiss = { editedRule = null },
            onSave = {
                routingViewModel.saveRule(index, it)
                editedRule = null
            },
            onDelete = index?.let {
                {
                    routingViewModel.deleteRule(it)
                    editedRule = null
                }
            },
        )
    }
}

@Composable
private fun PresetDialog(
    selected: RoutingPreset,
    onDismiss: () -> Unit,
    onSelect: (RoutingPreset) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Правило трафика") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("routing-preset-options"),
            ) {
                RoutingPreset.entries.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(preset) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = preset == selected, onClick = { onSelect(preset) })
                        Column {
                            Text(preset.title, fontWeight = FontWeight.Medium)
                            Text(
                                preset.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun RuleEditorDialog(
    initial: ManagedRoutingRule,
    onDismiss: () -> Unit,
    onSave: (ManagedRoutingRule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var matchType by remember(initial) { mutableStateOf(initial.matchType) }
    var action by remember(initial) { mutableStateOf(initial.action) }
    var values by remember(initial) { mutableStateOf(initial.values.joinToString("\n")) }
    var outbound by remember(initial) { mutableStateOf(initial.outboundTag.orEmpty()) }
    val parsed = values.split(Regex("[,\\n]")).map(String::trim).filter(String::isNotEmpty)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.values.isEmpty()) "Новое правило" else "Редактировать правило") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                    value = values,
                    onValueChange = { values = it },
                    label = { Text("По одному значению на строку") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("routing-rule-values"),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        label = { Text("Outbound tag (пусто = выбранный VPN)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (action == RoutingRuleAction.Block && matchType in setOf(
                        RoutingMatchType.Domain,
                        RoutingMatchType.DomainSuffix,
                        RoutingMatchType.DomainRuleSet,
                    )
                ) {
                    Text(
                        "Будут созданы DNS reject и route reject. Встроенный DoH приложения может скрыть domain-only совпадение.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
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
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Удалить") } }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@Composable
private fun actionColor(action: RoutingRuleAction) = when (action) {
    RoutingRuleAction.Proxy -> MaterialTheme.colorScheme.primary
    RoutingRuleAction.Direct -> MaterialTheme.colorScheme.tertiary
    RoutingRuleAction.Block -> MaterialTheme.colorScheme.error
}
