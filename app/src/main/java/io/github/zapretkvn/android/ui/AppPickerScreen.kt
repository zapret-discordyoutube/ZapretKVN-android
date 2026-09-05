package io.github.zapretkvn.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.apps.AppsUiState
import io.github.zapretkvn.android.apps.AppsViewModel
import io.github.zapretkvn.android.apps.InstalledApp

enum class AppPickerMode {
    VpnScope,
    Blocked,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    state: AppsUiState,
    viewModel: AppsViewModel,
    mode: AppPickerMode,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    val matchingApps = state.apps.filter { app ->
        query.isBlank() ||
            app.label.contains(query, ignoreCase = true) ||
            app.packageName.contains(query, ignoreCase = true)
    }
    val selectedPackages = when (mode) {
        AppPickerMode.VpnScope -> state.allowedPackages
        AppPickerMode.Blocked -> state.blockedPackages
    }
    val missingPackages = when (mode) {
        AppPickerMode.VpnScope -> state.missingAllowedPackages
        AppPickerMode.Blocked -> state.missingBlockedPackages
    }
    val suggestedApps = if (mode == AppPickerMode.VpnScope) {
        matchingApps.filter { it.suggestion != null }
    } else {
        emptyList()
    }
    val regularApps = matchingApps.filter { app ->
        (mode == AppPickerMode.Blocked || app.suggestion == null) &&
            (!app.system || showSystem || app.packageName in selectedPackages)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when (mode) {
                            AppPickerMode.Blocked -> "Блокировка приложений"
                            AppPickerMode.VpnScope -> {
                                if (state.scopeMode == AppScopeMode.Include) {
                                    "Приложения для VPN"
                                } else {
                                    "Приложения напрямую"
                                }
                            }
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "controls") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Поиск по имени или package") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("app-search"),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Показывать системные")
                            Text(
                                "Службы Android скрыты по умолчанию",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = showSystem,
                            onCheckedChange = { showSystem = it },
                            modifier = Modifier
                                .testTag("show-system-apps")
                                .semantics {
                                    contentDescription = "Показывать системные приложения"
                                },
                        )
                    }
                    Text(
                        when (mode) {
                            AppPickerMode.Blocked -> "Заблокировано: ${state.blockedPackages.size}"
                            AppPickerMode.VpnScope -> {
                                if (state.scopeMode == AppScopeMode.Include) {
                                    "В VPN: ${state.allowedPackages.size}"
                                } else {
                                    "Напрямую вне VPN: ${state.allowedPackages.size}"
                                }
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (mode == AppPickerMode.Blocked) {
                        Text(
                            "Отмеченные приложения не смогут использовать Wi‑Fi или мобильную сеть, " +
                                "пока подключён Zapret KVN. Изменения применятся при следующем подключении.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (state.scopeMode == AppScopeMode.Exclude) {
                        Text(
                            "Отмеченные приложения используют обычную сеть Android вне VPN и TUN; " +
                                "все остальные идут через VPN. Глобальный tun0 при этом может оставаться видимым. " +
                                "Пустой список заблокирован.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Список приложений обрабатывается только на устройстве и никуда не отправляется.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.loading) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            state.warning?.let { warning ->
                item(key = "warning") {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(warning, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = viewModel::refresh) { Text("Повторить") }
                    }
                }
            }

            state.error?.let { loadError ->
                item(key = "error") {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(loadError, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = viewModel::refresh) { Text("Повторить") }
                    }
                }
            }

            if (missingPackages.isNotEmpty()) {
                item(key = "missing-header") {
                    SectionHeader("Недоступные")
                }
                items(missingPackages.toList(), key = { "missing-$it" }) { packageName ->
                    MissingAppRow(
                        packageName = packageName,
                        blocked = mode == AppPickerMode.Blocked,
                        onRemove = {
                            if (mode == AppPickerMode.Blocked) {
                                viewModel.setBlocked(packageName, false)
                            } else {
                                viewModel.setAllowed(packageName, false)
                            }
                        },
                    )
                }
            }

            if (suggestedApps.isNotEmpty()) {
                item(key = "suggested-header") {
                    SectionHeader("Популярные")
                }
                items(suggestedApps, key = { "suggested-${it.packageName}" }) { app ->
                    AppRow(
                        app = app,
                        selected = app.packageName in selectedPackages,
                        onSelectedChange = { selected ->
                            if (mode == AppPickerMode.Blocked) {
                                viewModel.setBlocked(app.packageName, selected)
                            } else {
                                viewModel.setAllowed(app.packageName, selected)
                            }
                        },
                    )
                }
            }

            if (regularApps.isNotEmpty()) {
                item(key = "apps-header") {
                    SectionHeader(if (showSystem) "Все приложения" else "Установленные")
                }
                items(regularApps, key = InstalledApp::packageName) { app ->
                    AppRow(
                        app = app,
                        selected = app.packageName in selectedPackages,
                        onSelectedChange = { selected ->
                            if (mode == AppPickerMode.Blocked) {
                                viewModel.setBlocked(app.packageName, selected)
                            } else {
                                viewModel.setAllowed(app.packageName, selected)
                            }
                        },
                    )
                }
            }

            if (!state.loading && state.error == null &&
                suggestedApps.isEmpty() && regularApps.isEmpty() &&
                missingPackages.isEmpty()
            ) {
                item(key = "empty") {
                    Text(
                        if (query.isBlank()) "Приложения не найдены." else "По запросу ничего не найдено.",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (missingPackages.isNotEmpty()) {
                item(key = "remove-missing") {
                    TextButton(
                        onClick = {
                            if (mode == AppPickerMode.Blocked) {
                                viewModel.removeMissingBlockedPackages()
                            } else {
                                viewModel.removeMissingAllowedPackages()
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text("Удалить все недоступные")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { heading() },
    )
}

@Composable
internal fun AppRow(
    app: InstalledApp,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag("app-row-${app.packageName}")
            .clickable { onSelectedChange(!selected) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, fontWeight = FontWeight.Medium)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val details = buildList {
                app.suggestion?.let { add("Рекомендуем: $it") }
                if (app.system) add("Системное")
                if (!app.enabled) add("Отключено в Android")
            }.joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (app.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
        Checkbox(
            checked = selected,
            onCheckedChange = onSelectedChange,
        )
    }
    HorizontalDivider()
}

@Composable
private fun MissingAppRow(
    packageName: String,
    blocked: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(packageName, fontWeight = FontWeight.Medium)
            Text(
                if (blocked) {
                    "Пакет удалён или недоступен. Правило блокировки будет пропущено."
                } else {
                    "Пакет удалён или недоступен и будет пропущен при подключении."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onRemove) { Text("Убрать") }
    }
    HorizontalDivider()
}
