package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons

/** Действия списка серверов; все лямбды стабильны (создаются через `remember` в Route). */
@Stable
internal class ServerListActions(
    val onSelect: (groupTag: String, serverTag: String) -> Unit,
    val onMeasure: ((groupTag: String) -> Unit)? = null,
    val onToggleGroup: (groupTag: String) -> Unit = {},
    val onChangeProfile: (() -> Unit)? = null,
)

/** Состояние панели поиска и чипов (закреплена вверху списка). */
@Stable
internal class ServerTools(
    val filter: ServerFilter,
    val chips: ServerChips,
    val onQuery: (String) -> Unit,
    val onChip: (ServerChip) -> Unit,
    val onReset: () -> Unit,
)

internal const val SERVER_LIST_TAG = "profile-servers-sheet"

/**
 * Список серверов: профиль → группы → строки. Один `LazyColumn` с `key` и
 * `contentType`; поиск и чипы закреплены. Ничего не считает сам — рисует
 * готовую [ServerListModel].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServerList(
    model: ServerListModel,
    actions: ServerListActions,
    tools: ServerTools?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.s),
    testTag: String = SERVER_LIST_TAG,
    leading: (@Composable () -> Unit)? = null,
) {
    val items = model.items
    val toolsIndex = items.indexOfFirst { it is ServerListItem.Tools }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag(testTag),
        contentPadding = contentPadding,
    ) {
        if (leading != null) item(key = "leading", contentType = "leading") { leading() }
        if (toolsIndex < 0 || tools == null) {
            serverItems(items, actions, tools)
        } else {
            serverItems(items.subList(0, toolsIndex), actions, tools)
            stickyHeader(key = "tools", contentType = "tools") { ServerToolsBar(tools) }
            serverItems(items.subList(toolsIndex + 1, items.size), actions, tools)
        }
    }
}

private fun LazyListScope.serverItems(
    list: List<ServerListItem>,
    actions: ServerListActions,
    tools: ServerTools?,
) {
    items(list, key = { it.key }, contentType = { it.contentType }) { item ->
        when (item) {
            is ServerListItem.Profile -> ProfileHeaderCard(item.header, actions.onChangeProfile)
            ServerListItem.Tools -> Unit
            is ServerListItem.Group -> GroupCard(item, actions)
            is ServerListItem.Server -> ServerRow(
                row = item.row,
                position = item.position,
                onClick = if (item.readOnly) null else {
                    { actions.onSelect(item.row.groupTag, item.row.tag) }
                },
            )
            is ServerListItem.Note -> Text(
                item.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.s),
            )
            ServerListItem.EmptyFilter -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Ничего не найдено", style = MaterialTheme.typography.titleMedium)
                if (tools != null) TextButton(onClick = tools.onReset) { Text("Сбросить фильтры") }
            }
        }
    }
}

@Composable
internal fun ProfileHeaderCard(header: ProfileHeaderUi, onChange: (() -> Unit)?) {
    ZapretCard(modifier = Modifier.padding(bottom = Spacing.cardGap), verticalSpacing = Spacing.s) {
        Text(
            "Профиль",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(header.name, style = MaterialTheme.typography.titleLarge)
        header.counts?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        header.flags?.let { flags ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                // Флаги декоративны: страны названы в строках серверов.
                Text(flags, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
                if (header.extraFlags > 0) {
                    Text(
                        "+${header.extraFlags}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                header.updated,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onChange != null) {
                FilledTonalButton(onClick = onChange) { Text("Сменить") }
            }
        }
    }
}

@Composable
private fun GroupCard(item: ServerListItem.Group, actions: ServerListActions) {
    val group = item.group
    ZapretCard(
        modifier = Modifier.padding(top = Spacing.cardGap, bottom = Spacing.s),
        verticalSpacing = Spacing.s,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(group.title, style = MaterialTheme.typography.titleMedium)
                group.explanation?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.collapsible) {
                IconButton(onClick = { actions.onToggleGroup(group.tag) }) {
                    Icon(
                        if (item.expanded) ZapretIcons.ExpandLess else ZapretIcons.ExpandMore,
                        contentDescription = if (item.expanded) "Свернуть группу" else "Развернуть группу",
                    )
                }
            }
        }
        if (!item.expanded) {
            group.currentLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = { actions.onToggleGroup(group.tag) }) {
                Text("Показать ${group.rows.size}")
            }
            return@ZapretCard
        }
        val measure = actions.onMeasure
        if (item.showControls && measure != null && group.probeLine != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                FilledTonalButton(
                    onClick = { measure(group.tag) },
                    enabled = group.live && !group.measuring,
                ) { Text(if (group.measuring) "Проверяем…" else "Проверить все") }
                Text(
                    group.probeLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            group.progressFraction?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            }
        }
        group.better?.let { hint ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hint.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { actions.onSelect(group.tag, hint.serverTag) }) { Text("Переключить") }
            }
        }
    }
}

@Composable
private fun ServerToolsBar(tools: ServerTools) {
    var moreOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        OutlinedTextField(
            value = tools.filter.query,
            onValueChange = tools.onQuery,
            placeholder = { Text("Поиск по названию или стране") },
            leadingIcon = { Icon(ZapretIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (tools.filter.query.isNotEmpty()) {
                    IconButton(onClick = { tools.onQuery("") }) {
                        Icon(ZapretIcons.Close, contentDescription = "Очистить поиск")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile-servers-filter"),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            tools.chips.primary.forEach { chip ->
                FilterChip(
                    selected = tools.filter.chip == chip.chip,
                    onClick = { tools.onChip(chip.chip) },
                    label = { Text(chip.label) },
                )
            }
            if (tools.chips.more.isNotEmpty()) {
                FilterChip(
                    selected = tools.chips.more.any { it.chip == tools.filter.chip },
                    onClick = { moreOpen = true },
                    label = { Text("Ещё") },
                )
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    tools.chips.more.forEach { chip ->
                        DropdownMenuItem(
                            text = { Text(chip.label) },
                            onClick = {
                                moreOpen = false
                                tools.onChip(chip.chip)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Готовый список для шторки «Быстрый выбор» (Главная) и аналогичных мест:
 * группы → строки [ServerRow], поиск и чипы появляются при >8 серверах.
 *
 * [onMeasure] != null добавляет в карточку группы «Проверить все» (доступна, если
 * [ServerGroupUi.live]). Модели групп строят [liveServerGroups] / [offlineServerGroups].
 */
@Composable
internal fun ServerPickerContent(
    groups: List<ServerGroupUi>,
    onSelect: (groupTag: String, serverTag: String) -> Unit,
    modifier: Modifier = Modifier,
    onMeasure: ((groupTag: String) -> Unit)? = null,
    testTag: String = SERVER_LIST_TAG,
    leading: (@Composable () -> Unit)? = null,
) {
    var filter by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(ServerFilter()) }
    var toggled by remember { mutableStateOf(emptySet<String>()) }
    val chips = remember(groups) { serverChips(groups) }
    val cards = groups.size > 1 || onMeasure != null
    val model = remember(groups, filter, toggled, cards) {
        buildServerList(groups, header = null, filter = filter, toggledGroups = toggled, compact = true, groupCards = cards)
    }
    val tools = remember(model.showTools, chips, filter) {
        if (model.showTools) {
            ServerTools(
                filter = filter,
                chips = chips,
                onQuery = { filter = filter.copy(query = it.take(80)) },
                onChip = { filter = filter.copy(chip = it) },
                onReset = { filter = ServerFilter() },
            )
        } else {
            null
        }
    }
    val actions = remember(onSelect, onMeasure) {
        ServerListActions(
            onSelect = onSelect,
            onMeasure = onMeasure,
            onToggleGroup = { tag -> toggled = if (tag in toggled) toggled - tag else toggled + tag },
        )
    }
    ServerList(
        model = model,
        actions = actions,
        tools = tools,
        modifier = modifier,
        testTag = testTag,
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
        leading = leading,
    )
}

/** Строка поиска переживает поворот экрана; чип — нет (сбрасывается на «Все»). */
internal val FilterSaver = androidx.compose.runtime.saveable.Saver<ServerFilter, String>(
    save = { it.query },
    restore = { ServerFilter(query = it) },
)
