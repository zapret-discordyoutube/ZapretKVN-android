package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.layout.Column
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
 * Вкладка «Серверы»: профиль → группы → строки. Экран ничего не читает из
 * хранилищ: получает готовую [ServersUi] и лямбды. Локальное UI-состояние —
 * поиск, чип, сортировка, раскрытые группы.
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
    // Порядок фиксируется в момент выбора сортировки: замеры строки не переставляют.
    var order by rememberSaveable { mutableStateOf(ArrayList<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding),
    ) {
        ZapretTopBar(
            title = "Серверы",
            actions = {
                val content = ui as? ServersUi.Content
                if (content != null && content.groups.sumOf { it.rows.size } > 1) {
                    SortMenu(sort) { chosen ->
                        sort = chosen
                        order = ArrayList(frozenOrder(content.groups, chosen))
                    }
                }
            },
        )
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
                ServersBody(ui, order, actions)
            }
        }
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
private fun ServersBody(content: ServersUi.Content, order: List<String>, actions: ServersScreenActions) {
    var filter by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(ServerFilter()) }
    var toggled by remember { mutableStateOf(emptySet<String>()) }
    val chips = remember(content.groups) { serverChips(content.groups) }
    val model = remember(content.groups, content.header, filter, order, toggled) {
        buildServerList(content.groups, content.header, filter, order, toggled)
    }
    val tools = remember(model.showTools, chips, filter) {
        if (model.showTools) {
            ServerTools(
                filter = filter,
                chips = chips,
                onQuery = { filter = filter.copy(query = it.take(MAX_QUERY)) },
                onChip = { filter = filter.copy(chip = it) },
                onReset = { filter = ServerFilter() },
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
            onChangeProfile = actions.onOpenProfiles,
        )
    }
    val listState = rememberLazyListState()
    // Первый показ: прокрутка к выбранному серверу без анимации.
    var scrolled by rememberSaveable { mutableStateOf(false) }
    val selectedIndex by rememberUpdatedState(model.selectedIndex)
    val hasTools by rememberUpdatedState(model.showTools)
    LaunchedEffect(Unit) {
        if (!scrolled) {
            scrolled = true
            val index = selectedIndex
            if (index > SCROLL_MIN_INDEX) listState.scrollToItem(index - if (hasTools) 2 else 1)
        }
    }
    ServerList(
        model = model,
        actions = listActions,
        tools = tools,
        listState = listState,
        modifier = Modifier.fillMaxSize(),
    )
}

private const val MAX_QUERY = 80

/** Выбранная строка в первых экранах списка не требует прокрутки. */
private const val SCROLL_MIN_INDEX = 6
