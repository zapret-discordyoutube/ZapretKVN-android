package io.github.zapretkvn.android.ui.apps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.apps.AppsUiState
import io.github.zapretkvn.android.apps.InstalledApp
import io.github.zapretkvn.android.ui.AppPickerMode
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.ZapretTopBar
import io.github.zapretkvn.core.designsystem.onToneContainer
import io.github.zapretkvn.core.designsystem.toneColor
import io.github.zapretkvn.core.designsystem.toneContainer

/**
 * Экран выбора приложений. Stateless по данным: состояние приложений и действия приходят снаружи,
 * внутри живёт только состояние ввода (поиск, фильтр, показ системных).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppPickerContent(
    state: AppsUiState,
    mode: AppPickerMode,
    onToggle: (packageName: String, selected: Boolean) -> Unit,
    onRemoveMissing: (packageName: String) -> Unit,
    onRemoveAllMissing: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(AppFilter.All) }
    var menuOpen by remember { mutableStateOf(false) }

    val selected = state.selectedFor(mode)
    val missing = remember(state.apps, state.catalogLoaded, selected) { state.missingFor(mode) }
    val matching = remember(state.apps, query) { filterByQuery(state.apps, query) }
    val model = remember(matching, selected, missing, mode, filter, showSystem) {
        buildAppPickerModel(matching, selected, missing, mode, filter, showSystem)
    }
    val blocked = mode == AppPickerMode.Blocked
    val showSuggestion = !blocked

    Scaffold(
        topBar = {
            ZapretTopBar(
                title = pickerTitle(mode, state.scopeMode),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(ZapretIcons.More, contentDescription = "Ещё")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Показывать системные") },
                            trailingIcon = { Checkbox(checked = showSystem, onCheckedChange = null) },
                            onClick = {
                                showSystem = !showSystem
                                menuOpen = false
                            },
                            modifier = Modifier.testTag("show-system-apps"),
                        )
                    }
                },
            )
        },
        bottomBar = { SelectionBar(count = selected.size, onDone = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.loading) {
                item(key = "loading", contentType = "status") {
                    Column {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Читаем список приложений…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                        )
                    }
                }
            }
            item(key = "controls", contentType = "controls") {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Поиск по имени или package") },
                        leadingIcon = { Icon(ZapretIcons.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("app-search"),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        availableFilters(mode).forEach { item ->
                            FilterChip(
                                selected = filter == item,
                                onClick = { filter = item },
                                label = { Text(item.title) },
                            )
                        }
                    }
                    ModeHint(mode = mode, scopeMode = state.scopeMode)
                }
            }

            state.warning?.let { warning ->
                item(key = "warning", contentType = "status") {
                    Notice(warning, Tone.Warning, onRefresh)
                }
            }
            state.error?.let { loadError ->
                item(key = "error", contentType = "status") {
                    Notice(loadError, Tone.Bad, onRefresh)
                }
            }

            if (model.missing.isNotEmpty()) {
                stickyHeader(key = "missing-header", contentType = "header") {
                    SectionHeaderRow(
                        title = "Недоступные",
                        count = model.missing.size,
                        actionLabel = "Удалить все недоступные",
                        onAction = onRemoveAllMissing,
                    )
                }
                items(model.missing, key = { "missing-$it" }, contentType = { "missing" }) { packageName ->
                    MissingAppRow(
                        packageName = packageName,
                        blocked = blocked,
                        onRemove = { onRemoveMissing(packageName) },
                    )
                    HorizontalDivider()
                }
            }

            if (model.suggested.isNotEmpty()) {
                stickyHeader(key = "suggested-header", contentType = "header") {
                    SectionHeaderRow(title = "Популярные", count = model.suggested.size)
                }
                items(
                    model.suggested,
                    key = { "suggested-${it.packageName}" },
                    contentType = { "app" },
                ) { app -> PickerRow(app, selected, showSuggestion, onToggle) }
            }

            if (model.installed.isNotEmpty()) {
                stickyHeader(key = "apps-header", contentType = "header") {
                    SectionHeaderRow(title = model.installedTitle, count = model.installed.size)
                }
                items(
                    model.installed,
                    key = { "app-${it.packageName}" },
                    contentType = { "app" },
                ) { app -> PickerRow(app, selected, showSuggestion, onToggle) }
            }

            if (!state.loading && state.error == null && model.isEmpty) {
                item(key = "empty", contentType = "status") {
                    Text(
                        emptyMessage(query, filter),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.xl),
                    )
                }
            }

            item(key = "privacy", contentType = "status") {
                Text(
                    "Список приложений обрабатывается только на устройстве и никуда не отправляется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.l),
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    app: InstalledApp,
    selected: Set<String>,
    showSuggestion: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    AppRow(
        app = app,
        selected = app.packageName in selected,
        onSelectedChange = { onToggle(app.packageName, it) },
        showSuggestion = showSuggestion,
    )
    HorizontalDivider()
}

@Composable
private fun ModeHint(mode: AppPickerMode, scopeMode: AppScopeMode) {
    val text = when {
        mode == AppPickerMode.Blocked ->
            "Отмеченные приложения не смогут использовать Wi‑Fi или мобильную сеть, пока подключён " +
                "Zapret KVN. Изменения применятся при следующем подключении." to Tone.Bad
        scopeMode == AppScopeMode.Exclude ->
            "Отмеченные приложения используют обычную сеть Android вне VPN, все остальные идут " +
                "через VPN. Пустой список заблокирован." to Tone.Warning
        else -> return
    }
    Text(
        text.first,
        style = MaterialTheme.typography.bodySmall,
        color = toneColor(text.second),
    )
}

/** Закреплённый заголовок секции: непрозрачный, чтобы строки не просвечивали при прокрутке. */
@Composable
private fun SectionHeaderRow(
    title: String,
    count: Int,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 48.dp)
            .padding(start = Spacing.l, end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            "$title ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun Notice(text: String, tone: Tone, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
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
        TextButton(onClick = onRetry) { Text("Повторить") }
    }
}

/** Нижняя панель: сколько выбрано и «Готово». Сама учитывает вырез/навигацию и клавиатуру. */
@Composable
private fun SelectionBar(count: Int, onDone: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                "Выбрано $count",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onDone) { Text("Готово") }
        }
    }
}
