package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.zapretkvn.core.designsystem.EmptyState
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.ZapretTopBar

/** Действия вкладки «Серверы»; создаются один раз в Route. */
internal class ServersScreenActions(
    val onSelect: (groupTag: String, serverTag: String) -> Unit,
    val onMeasure: (groupTag: String) -> Unit,
    val onOpenProfiles: () -> Unit,
    val onOpenEditor: (profileId: String) -> Unit,
)

/**
 * Вкладка «Серверы»: профиль → группы → (секции по странам) → строки. Экран ничего не
 * читает из хранилищ: получает готовую [ServersUi] и лямбды. Локальное UI-состояние —
 * поиск, чип, сортировка, раскрытые группы и секции.
 *
 * Поиск не занимает места: иконка лупы в шапке раскрывает поле прямо в шапке
 * (список остаётся на весь экран); чипы — обычный элемент списка и уезжают вверх.
 */
@Composable
internal fun ServersScreen(
    ui: ServersUi,
    busy: Boolean,
    importer: ProfileImporter,
    actions: ServersScreenActions,
    contentPadding: PaddingValues,
) {
    var sort by rememberSaveable { mutableStateOf(ServerSort.Subscription) }
    // Порядок фиксируется в момент выбора сортировки: замеры строку не переставляют.
    var order by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    val content = ui as? ServersUi.Content
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable(content?.profileId, stateSaver = FilterSaver) { mutableStateOf(ServerFilter()) }
    val canSearch = content != null && content.groups.flatMap { it.rows }.distinctBy { it.tag }.size > FILTER_THRESHOLD
    val searching = canSearch && searchOpen

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding),
    ) {
        if (searching) {
            SearchBar(
                query = filter.query,
                onQuery = { filter = filter.copy(query = it.take(MAX_QUERY)) },
                onClose = {
                    searchOpen = false
                    filter = filter.copy(query = "")
                },
            )
        } else {
            ZapretTopBar(
                title = "Серверы",
                actions = {
                    if (canSearch) {
                        IconButton(onClick = { searchOpen = true }, modifier = Modifier.testTag("servers-search-toggle")) {
                            Icon(ZapretIcons.Search, contentDescription = "Поиск серверов")
                        }
                    }
                    if (content != null && content.groups.sumOf { it.rows.size } > 1) {
                        SortMenu(sort) { chosen ->
                            sort = chosen
                            order = ArrayList(frozenOrder(content.groups, chosen))
                        }
                    }
                },
            )
        }
        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Операция с профилем выполняется" },
            )
        }
        when (ui) {
            ServersUi.Empty -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .testTag("profiles-empty"),
            ) {
                EmptyState(
                    title = "Пока нет серверов",
                    body = "Добавьте подписку, файл или ссылку. Серверы появятся здесь.",
                    icon = ZapretIcons.Servers,
                ) {
                    ImportSourceButtons(importer, enabled = !busy)
                }
            }
            ServersUi.NoActive -> EmptyState(
                title = "Профиль не выбран",
                body = "Выберите профиль, чтобы увидеть его серверы.",
                icon = ZapretIcons.Servers,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Button(onClick = actions.onOpenProfiles) { Text("Выбрать профиль") }
            }
            is ServersUi.NoServers -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen, vertical = Spacing.s),
            ) {
                ProfileHeaderCard(ui.header, actions.onOpenProfiles)
                EmptyState(
                    title = "В профиле нет списка серверов",
                    body = "Это свой JSON без группы серверов. Подключение идёт по его собственным " +
                        "правилам, выбирать здесь нечего.",
                    icon = ZapretIcons.Code,
                ) {
                    OutlinedButton(onClick = { actions.onOpenEditor(ui.profileId) }) { Text("Исходный JSON") }
                }
            }
            is ServersUi.Content -> key(ui.profileId) {
                ServersBody(ui, order, sort, filter, { filter = it }, actions)
            }
        }
    }
}

/** Поиск в шапке: стрелка закрывает и очищает, крестик стирает текст. */
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(ZapretIcons.Back, contentDescription = "Закрыть поиск")
        }
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Название или страна") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(ZapretIcons.Close, contentDescription = "Очистить поиск")
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .testTag("profile-servers-filter"),
        )
    }
}

@Composable
private fun SortMenu(current: ServerSort, onChoose: (ServerSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(ZapretIcons.More, contentDescription = "Сортировка")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        ServerSort.entries.forEach { sort ->
            DropdownMenuItem(
                text = { Text(sort.label) },
                onClick = {
                    open = false
                    onChoose(sort)
                },
                trailingIcon = {
                    if (sort == current) Icon(ZapretIcons.Ok, contentDescription = "выбрано")
                },
                modifier = Modifier.testTag(
                    if (sort == ServerSort.Fastest) "servers-sort-fastest" else "servers-sort-${sort.name}",
                ),
            )
        }
    }
}

@Composable
private fun ServersBody(
    content: ServersUi.Content,
    order: List<String>,
    sort: ServerSort,
    filter: ServerFilter,
    onFilter: (ServerFilter) -> Unit,
    actions: ServersScreenActions,
) {
    var toggled by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var sections by rememberSaveable(stateSaver = SectionStateSaver) { mutableStateOf(SectionState()) }
    val pinned = rememberPinnedTags(content.groups)
    val chips = remember(content.groups) { serverChips(content.groups) }
    val model = remember(content.groups, content.header, filter, order, toggled, sections, sort, pinned) {
        buildServerList(
            content.groups, content.header, filter, order, toggled,
            sections = sections, sort = sort, pinnedTags = pinned,
        )
    }
    val currentFilter by rememberUpdatedState(filter)
    val tools = remember(model.showTools, chips, filter) {
        if (model.showTools) {
            ServerTools(
                filter = filter,
                chips = chips,
                onQuery = null,
                onChip = { onFilter(currentFilter.copy(chip = it)) },
                onReset = { onFilter(ServerFilter()) },
            )
        } else {
            null
        }
    }
    val listActions = remember(actions) {
        ServerListActions(
            onSelect = actions.onSelect,
            onMeasure = actions.onMeasure,
            onToggleGroup = { tag -> toggled = if (tag in toggled) toggled - tag else toggled + tag },
            onToggleSection = { key, expand -> sections = sections.with(key, expand) },
            onChangeProfile = actions.onOpenProfiles,
        )
    }
    val listState = rememberLazyListState()
    // Первый показ: прокрутка к выбранному серверу без анимации.
    var scrolled by rememberSaveable { mutableStateOf(false) }
    val selectedIndex by rememberUpdatedState(model.selectedIndex)
    LaunchedEffect(Unit) {
        if (!scrolled) {
            scrolled = true
            val index = selectedIndex
            if (index > SCROLL_MIN_INDEX) listState.scrollToItem(index - 1)
        }
    }
    // Поиск/фильтр сменился: результаты показываем с самого верха.
    var seenFilter by remember { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != seenFilter) {
            seenFilter = filter
            listState.scrollToItem(0)
        }
    }
    val scope = rememberCoroutineScope()
    val farDown by remember { derivedStateOf { listState.firstVisibleItemIndex >= TOP_BUTTON_INDEX } }
    Box(modifier = Modifier.fillMaxSize()) {
        ServerList(
            model = model,
            actions = listActions,
            tools = tools,
            listState = listState,
            modifier = Modifier.fillMaxSize(),
        )
        if (farDown) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.scrollToItem(0) } },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.l)
                    .testTag("servers-scroll-top"),
            ) {
                Icon(ZapretIcons.ExpandLess, contentDescription = "Наверх")
            }
        }
    }
}

private const val MAX_QUERY = 80

/** Выбранная строка в первых экранах списка не требует прокрутки. */
private const val SCROLL_MIN_INDEX = 6

/** «Наверх» появляется, когда список прокручен дальше этой позиции. */
private const val TOP_BUTTON_INDEX = 10
