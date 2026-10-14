package io.github.zapretkvn.android.ui.servers

import androidx.compose.animation.core.tween
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import io.github.zapretkvn.core.designsystem.Motion
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons

/** Действия списка серверов; все лямбды стабильны (создаются через `remember` в Route). */
@Stable
internal class ServerListActions(
    val onSelect: (groupTag: String, serverTag: String) -> Unit,
    val onMeasure: ((groupTag: String) -> Unit)? = null,
    val onStopMeasure: ((groupTag: String) -> Unit)? = null,
    val onToggleGroup: (groupTag: String) -> Unit = {},
    val onToggleSection: (sectionKey: String, expand: Boolean) -> Unit = { _, _ -> },
    val onChangeProfile: (() -> Unit)? = null,
)

/**
 * Состояние фильтров над списком. Ничего не закреплено: чипы прокручиваются вместе
 * со списком. [onQuery] == null — строка поиска живёт снаружи (в шапке экрана),
 * в списке остаются только чипы.
 */
@Stable
internal class ServerTools(
    val filter: ServerFilter,
    val chips: ServerChips,
    val onQuery: ((String) -> Unit)?,
    val onChip: (ServerChip) -> Unit,
    val onReset: () -> Unit,
)

internal const val SERVER_LIST_TAG = "profile-servers-sheet"

/**
 * Список серверов: профиль → группы → (секции по странам) → строки. Один `LazyColumn`
 * с `key` и `contentType`; чипы и поиск не закреплены. Ничего не считает сам —
 * рисует готовую [ServerListModel].
 */
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
    LazyColumn(
        state = listState,
        modifier = modifier.testTag(testTag),
        contentPadding = contentPadding,
    ) {
        if (leading != null) item(key = "leading", contentType = "leading") { leading() }
        serverItems(model.items, actions, tools)
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
            ServerListItem.Tools -> if (tools != null) ServerToolsBar(tools)
            is ServerListItem.Group -> GroupCard(item, actions)
            is ServerListItem.Section -> SectionRow(item, actions.onToggleSection)
            is ServerListItem.Server -> ServerRow(
                row = item.row,
                position = item.position,
                onClick = if (item.readOnly) null else {
                    { actions.onSelect(item.row.groupTag, item.row.tag) }
                },
                modifier = if (item.animate) Modifier.animateItem(fadeInSpec = tween(Motion.QUICK), placementSpec = null) else Modifier,
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
            FlagStrip(flags, header.extraFlags)
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
            group.currentLine?.let { FlagAwareText(it, style = MaterialTheme.typography.bodyMedium) }
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
                val stop = actions.onStopMeasure
                if (group.measuring && stop != null) {
                    OutlinedButton(
                        onClick = { stop(group.tag) },
                        modifier = Modifier.testTag("servers-measure-stop"),
                    ) { Text("Остановить") }
                } else {
                    FilledTonalButton(
                        onClick = { measure(group.tag) },
                        enabled = group.live && !group.measuring,
                    ) { Text(if (group.measuring) "Проверяем…" else "Проверить все") }
                }
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

/** Ряд флагов профиля (декоративный: страны названы в строках серверов). */
@Composable
internal fun FlagStrip(strip: String, extra: Int, modifier: Modifier = Modifier, size: Dp = FlagSmall) {
    val flags = remember(strip) { splitFlagStrip(strip) }
    Row(
        modifier = modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        flags.forEach { FlagBadge(it, size = size) }
        if (extra > 0) {
            Text(
                "+$extra",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Чипы (и, если задан [ServerTools.onQuery], поле поиска) — обычный элемент списка, не закреплён. */
@Composable
private fun ServerToolsBar(tools: ServerTools) {
    var moreOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        tools.onQuery?.let { onQuery ->
            ServerSearchField(
                query = tools.filter.query,
                onQuery = onQuery,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            tools.chips.primary.forEach { chip ->
                FilterChip(
                    selected = tools.filter.chip == chip.chip,
                    onClick = { tools.onChip(chip.chip) },
                    label = { FlagAwareText(chip.label, style = MaterialTheme.typography.labelLarge) },
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
                            text = { FlagAwareText(chip.label) },
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

/** Компактное поле поиска серверов (одна строка, 48 dp). */
@Composable
internal fun ServerSearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Название или страна") },
        leadingIcon = { Icon(ZapretIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(ZapretIcons.Close, contentDescription = "Очистить поиск")
                }
            }
        },
        singleLine = true,
        modifier = modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .testTag("profile-servers-filter"),
    )
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
    var toggled by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var sections by rememberSaveable(stateSaver = SectionStateSaver) { mutableStateOf(SectionState()) }
    val chips = remember(groups) { serverChips(groups) }
    val cards = groups.size > 1 || onMeasure != null
    val pinned = rememberPinnedTags(groups)
    val model = remember(groups, filter, toggled, cards, sections, pinned) {
        buildServerList(
            groups, header = null, filter = filter, toggledGroups = toggled, compact = true,
            groupCards = cards, sections = sections, pinnedTags = pinned,
        )
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
            onToggleSection = { key, expand -> sections = sections.with(key, expand) },
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

/** Теги, выбранные при первом показе: их секции остаются раскрытыми, даже когда выбор переехал. */
@Composable
internal fun rememberPinnedTags(groups: List<ServerGroupUi>): Set<String> {
    val initial = rememberSaveable { ArrayList(groups.flatMap { g -> g.rows.filter { it.selected }.map { it.tag } }) }
    return remember(initial) { initial.toSet() }
}

internal val StringSetSaver = androidx.compose.runtime.saveable.Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

internal val SectionStateSaver = androidx.compose.runtime.saveable.Saver<SectionState, ArrayList<String>>(
    save = { it.save() },
    restore = { SectionState.restore(it) },
)

/** Строка поиска переживает поворот экрана; чип — нет (сбрасывается на «Все»). */
internal val FilterSaver = androidx.compose.runtime.saveable.Saver<ServerFilter, String>(
    save = { it.query },
    restore = { ServerFilter(query = it) },
)
