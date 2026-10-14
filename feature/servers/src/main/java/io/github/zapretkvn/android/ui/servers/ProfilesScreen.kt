package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.core.designsystem.EmptyState
import io.github.zapretkvn.core.designsystem.FlagSmall
import io.github.zapretkvn.core.designsystem.SectionHeader
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons

/** Действия меню ⋮ на карточке профиля. */
class ProfileActions(
    val onSelect: (id: String) -> Unit,
    val onServers: (id: String) -> Unit,
    val onRefresh: (id: String) -> Unit,
    val onRename: (ProfileCardUi) -> Unit,
    val onSubscriptionSettings: (id: String) -> Unit,
    val onRawJson: (id: String) -> Unit,
    val onDelete: (ProfileCardUi) -> Unit,
)

/** Экран «Профили»: секции карточек; пустое состояние — те же четыре источника. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfilesScreen(
    sections: List<ProfileSectionUi>,
    busy: Boolean,
    importer: ProfileImporter,
    actions: ProfileActions,
    contentPadding: PaddingValues,
) {
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Операция с профилем выполняется" },
            )
        }
        if (sections.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .testTag("profiles-empty"),
            ) {
                EmptyState(
                    title = "Профилей пока нет",
                    body = "Добавьте подписку, файл или ссылку. Серверы появятся на вкладке «Серверы».",
                    icon = ZapretIcons.Servers,
                ) {
                    ImportSourceButtons(importer, enabled = !busy)
                }
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("profiles-list"),
            // Снизу запас под «Добавить», чтобы он не закрывал последнюю карточку.
            contentPadding = PaddingValues(
                start = Spacing.screen,
                end = Spacing.screen,
                top = Spacing.xs,
                bottom = FAB_CLEARANCE,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
        ) {
            sections.forEach { section ->
                stickyHeader(key = "section-${section.title}", contentType = "section") {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                        SectionHeader(section.title)
                    }
                }
                items(section.cards, key = { it.id }, contentType = { "profile" }) { card ->
                    ProfileCard(card, busy, actions)
                }
            }
        }
    }
}

private val FAB_CLEARANCE = 88.dp

@Composable
private fun ProfileCard(card: ProfileCardUi, busy: Boolean, actions: ProfileActions) {
    ZapretCard(
        modifier = Modifier.semantics {
            contentDescription = card.a11y
            stateDescription = if (card.active) "активный" else "не выбран"
        },
        onClick = { if (!busy) actions.onSelect(card.id) },
        contentPadding = PaddingValues(start = Spacing.s, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Радио-отметка только показывает активный профиль; нажатие обрабатывает карточка.
            RadioButton(selected = card.active, onClick = null, modifier = Modifier.padding(Spacing.m).clearAndSetSemantics {})
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(card.name, style = MaterialTheme.typography.titleSmall)
                card.counts?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                card.flags?.let { flags -> FlagStrip(flags, card.extraFlags, size = FlagSmall) }
                Text(
                    card.meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProfileMenu(card, busy, actions)
        }
    }
}

@Composable
private fun ProfileMenu(card: ProfileCardUi, busy: Boolean, actions: ProfileActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(ZapretIcons.More, contentDescription = "Действия с профилем ${card.name}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (card.hasServers) {
                MenuItem("Серверы", !busy) { open = false; actions.onServers(card.id) }
            }
            if (card.refreshable) {
                MenuItem("Обновить (без кэша)", !busy) { open = false; actions.onRefresh(card.id) }
            }
            MenuItem("Переименовать", !busy) { open = false; actions.onRename(card) }
            if (card.refreshable) {
                MenuItem("Настройки подписки", !busy) { open = false; actions.onSubscriptionSettings(card.id) }
            }
            HorizontalDivider()
            MenuItem("Исходный JSON", !busy) { open = false; actions.onRawJson(card.id) }
            MenuItem("Удалить", !busy, danger = true) { open = false; actions.onDelete(card) }
        }
    }
}

@Composable
private fun MenuItem(text: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(text, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        enabled = enabled,
        onClick = onClick,
    )
}
