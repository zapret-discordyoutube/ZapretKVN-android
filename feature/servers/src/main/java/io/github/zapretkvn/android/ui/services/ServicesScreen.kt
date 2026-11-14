package io.github.zapretkvn.android.ui.services

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.core.designsystem.FlagBadge
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.SwitchRow
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.ZapretTopBar

/** More servers than this (over all profiles) and the chooser shows a search field. */
private const val SEARCH_THRESHOLD = 8

/**
 * The «Сервисы» tab (owner decision 2026-11-14): one compact row per service with where it leaves now, and a tap
 * opens the choice in place: «Авто», or a server from any profile (profiles collapsed, search on a long list). The
 * main server for everything else is chosen on the «Серверы» tab.
 */
@Composable
fun ServicesScreen(
    ui: ServicesUi,
    contentPadding: PaddingValues,
    onPin: (serviceId: String, ref: String?) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding)
            .testTag("services-screen"),
    ) {
        ZapretTopBar(title = "Сервисы")
        LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            item(key = "switch") {
                ZapretCard {
                    SwitchRow(
                        title = "Раздельные серверы (бета)",
                        checked = ui.enabled,
                        onCheckedChange = onEnabledChange,
                        subtitle = "YouTube, Telegram и ИИ-приложения ходят через свои серверы. Применяется после переподключения.",
                        switchTestTag = "services-enabled",
                        switchDescription = "Раздельные серверы",
                    )
                }
            }
            if (ui.enabled) {
                items(ui.services, key = { it.id }) { service ->
                    ServiceCard(
                        service = service,
                        connected = ui.connected,
                        expanded = expandedId == service.id,
                        onToggle = { expandedId = if (expandedId == service.id) null else service.id },
                        onPin = { ref -> onPin(service.id, ref) },
                    )
                }
                item(key = "rest") {
                    ZapretCard {
                        Text("Остальные приложения", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            (ui.mainServerTitle?.let { "Через $it. " }.orEmpty()) + "Сервер для них выбирается на вкладке «Серверы».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceCard(
    service: ServiceUi,
    connected: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPin: (String?) -> Unit,
) {
    ZapretCard(verticalSpacing = Spacing.s, modifier = Modifier.testTag("service-${service.id}")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = expanded, role = Role.Button, onClick = onToggle)
                .semantics {
                    contentDescription = service.title
                    stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (service.flag != null) FlagBadge(service.flag, size = 24.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(service.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        service.serverTitle ?: if (connected) null else "Сервер виден при подключении",
                        service.profileName,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                service.modeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (expanded) ZapretIcons.ExpandLess else ZapretIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Chooser(service, onPin)
        }
    }
}

@Composable
private fun Chooser(service: ServiceUi, onPin: (String?) -> Unit) {
    var query by rememberSaveable(service.id) { mutableStateOf("") }
    // Which profiles the user opened; the one holding the current or pinned server is open from the start.
    var opened by rememberSaveable(service.id) { mutableStateOf("") }
    val total = service.profiles.sumOf { it.servers.size }
    val visible = filterServiceServers(service.profiles, query)
    val searching = query.isNotBlank()
    HorizontalDivider()
    ChoiceRow("Автоматически", flag = null, detail = null, selected = service.pinnedRef == null, current = false) { onPin(null) }
    if (total > SEARCH_THRESHOLD) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(40) },
            modifier = Modifier.fillMaxWidth().testTag("service-search-${service.id}"),
            singleLine = true,
            label = { Text("Поиск сервера") },
        )
    }
    visible.forEach { profile ->
        val holdsChoice = profile.servers.any { it.current || it.pinned }
        // A lone profile has nothing to hide behind a header, so it is open from the start.
        val open = searching || holdsChoice || service.profiles.size == 1 || profile.profileId in opened.split(',')
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) {
                    val ids = opened.split(',').filter { it.isNotEmpty() }.toMutableSet()
                    if (!ids.add(profile.profileId)) ids.remove(profile.profileId)
                    opened = ids.joinToString(",")
                }
                .testTag("service-profile-${service.id}-${profile.profileId.take(8)}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Icon(if (open) ZapretIcons.ExpandLess else ZapretIcons.ExpandMore, contentDescription = null)
            Text(
                profile.name + if (profile.active) " · активный" else "",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text("${profile.servers.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            profile.servers.forEach { server ->
                ChoiceRow(server.title, server.flag, server.protocol, selected = server.pinned, current = server.current) {
                    onPin(server.ref)
                }
            }
        }
    }
    if (searching && visible.isEmpty()) {
        Text("Такого сервера нет.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceRow(title: String, flag: String?, detail: String?, selected: Boolean, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        RadioButton(selected = selected, onClick = null)
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (flag != null) FlagBadge(flag, size = 24.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (current) {
            Text("сейчас", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
