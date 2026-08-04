package io.github.zapretkvn.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileServerSummary
import io.github.zapretkvn.android.vpn.AppScopeMode
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.TrafficSample
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnSessionStats
import io.github.zapretkvn.android.vpn.primaryGroup
import kotlinx.coroutines.delay
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    contentPadding: PaddingValues,
    activeProfile: ProfileMetadata?,
    activeProfileServers: ProfileServerSummary?,
    onOpenServers: () -> Unit,
    selectedAppCount: Int,
    blockedAppCount: Int,
    appScopeMode: AppScopeMode,
    onAddProfile: () -> Unit,
    onSelectApps: () -> Unit,
    vpnState: VpnConnectionState,
    selectorGroups: List<RuntimeSelectorGroup>,
    sessionStats: VpnSessionStats,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onSelectOutbound: (String, String, String) -> Unit,
    onMeasurePing: () -> Unit,
    onMeasureGroup: (String) -> Unit,
) {
    var serverSheetOpen by rememberSaveable { mutableStateOf(false) }
    val connected = vpnState as? VpnConnectionState.Connected
    val currentGroup = selectorGroups.primaryGroup()
    val currentServer = currentGroup?.items?.firstOrNull { it.tag == currentGroup.selected }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionHeader(vpnState)
                Text(
                    connected?.profileName ?: activeProfile?.name
                    ?: "Добавьте профиль для подключения.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (connected == null && activeProfile != null && activeProfileServers != null &&
                    activeProfileServers.serverCount > 0
                ) {
                    OfflineServerSummary(
                        summary = activeProfileServers,
                        onClick = onOpenServers.takeIf { activeProfileServers.switchable },
                    )
                }

                if (connected != null) {
                    if (currentServer != null) {
                        ServerSummary(
                            server = currentServer,
                            pingMillis = sessionStats.pingMillis,
                            onClick = { serverSheetOpen = true },
                        )
                    } else {
                        Text(
                            "Сервер задаётся профилем JSON",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SessionFacts(sessionStats, connected.connectedAtEpochMillis, onMeasurePing)
                    TrafficChart(sessionStats.samples)
                    TrafficTotals(sessionStats)
                }

                when (vpnState) {
                    is VpnConnectionState.Starting -> Text(
                        vpnState.message,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is VpnConnectionState.Error -> Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Код: ${vpnState.code.ifBlank { "VPN-000" }}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(vpnState.message, color = MaterialTheme.colorScheme.error)
                    }
                    is VpnConnectionState.Reconnecting -> Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "${vpnState.message} · попытка ${vpnState.attempt} из ${vpnState.maxAttempts}",
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        if (vpnState.code.isNotBlank()) {
                            Text(
                                "Причина: ${vpnState.code}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is VpnConnectionState.Paused -> Text(
                        vpnState.message,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    else -> Unit
                }

                ConnectionAction(
                    activeProfile = activeProfile,
                    selectedAppCount = selectedAppCount,
                    blockedAppCount = blockedAppCount,
                    appScopeMode = appScopeMode,
                    vpnState = vpnState,
                    onAddProfile = onAddProfile,
                    onSelectApps = onSelectApps,
                    onStart = onStart,
                    onStop = onStop,
                    onRestart = onRestart,
                )
            }
        }

        Text(
            if (appScopeMode == AppScopeMode.Include) {
                "Через VPN: $selectedAppCount · заблокировано: $blockedAppCount"
            } else {
                "Напрямую вне VPN: $selectedAppCount · заблокировано: $blockedAppCount"
            },
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Версия приложения: ${BuildConfig.VERSION_NAME}",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (serverSheetOpen && connected != null) {
        ModalBottomSheet(onDismissRequest = { serverSheetOpen = false }) {
            ServerSelectorSheet(
                groups = selectorGroups,
                sessionPingMillis = sessionStats.pingMillis,
                onSelect = { group, item ->
                    onSelectOutbound(connected.profileId, group.tag, item.tag)
                },
                onMeasureGroup = onMeasureGroup,
            )
        }
    }
}

@Composable
private fun ConnectionHeader(state: VpnConnectionState) {
    val color = when (state) {
        is VpnConnectionState.Connected -> MaterialTheme.colorScheme.primary
        is VpnConnectionState.Error -> MaterialTheme.colorScheme.error
        is VpnConnectionState.Starting,
        is VpnConnectionState.Reconnecting,
        is VpnConnectionState.Paused,
        is VpnConnectionState.Stopping,
        -> MaterialTheme.colorScheme.tertiary
        VpnConnectionState.Stopped -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = MaterialTheme.shapes.extraLarge) {
            Spacer(Modifier.size(10.dp))
        }
        Text(
            text = state.title(),
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ServerSummary(server: RuntimeOutboundItem, pingMillis: Long?, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(server.tag, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(server.type.uppercase(), server.endpoint).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(formatPing(pingMillis ?: server.pingMillis?.toLong()), style = MaterialTheme.typography.labelLarge)
            Text("  ›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Выбор сервера до подключения: тот же профиль, но данные берутся из сохранённого JSON. */
@Composable
private fun OfflineServerSummary(summary: ProfileServerSummary, onClick: (() -> Unit)?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("home-offline-servers"),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    summary.selectedLabel ?: "Сервер не выбран",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (summary.switchable) {
                        "Серверов в профиле: ${summary.serverCount} · нажмите, чтобы сменить"
                    } else {
                        "В профиле один сервер"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClick != null) Text("  ›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SessionFacts(stats: VpnSessionStats, connectedAt: Long, onMeasurePing: () -> Unit) {
    var now by remember(connectedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAt) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Fact(
            label = "IP",
            value = stats.externalIp ?: "—",
            modifier = Modifier.weight(1.45f),
        )
        Fact(
            label = "Пинг",
            value = formatPing(stats.pingMillis),
            modifier = Modifier
                .weight(0.8f)
                .clickable(onClick = onMeasurePing),
        )
        Fact(
            label = "Время",
            value = formatDuration((now - connectedAt).coerceAtLeast(0)),
            modifier = Modifier.weight(0.9f),
        )
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrafficTotals(stats: VpnSessionStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "↓ ${formatBytes(stats.downloadTotalBytes)}",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "↑ ${formatBytes(stats.uploadTotalBytes)}",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun TrafficChart(samples: List<TrafficSample>) {
    val downloadColor = MaterialTheme.colorScheme.primary
    val uploadColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .semantics { contentDescription = "График загрузки и отдачи за последние 60 секунд" },
    ) {
        drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(0f, size.height), end = androidx.compose.ui.geometry.Offset(size.width, size.height))
        if (samples.size < 2) return@Canvas
        val peak = max(1L, samples.maxOf { max(it.downloadBytesPerSecond, it.uploadBytesPerSecond) })
        fun pathOf(value: (TrafficSample) -> Long): Path = Path().apply {
            samples.forEachIndexed { index, sample ->
                val x = size.width * index / max(1, samples.size - 1)
                val y = size.height * (1f - value(sample).toFloat() / peak.toFloat())
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(pathOf(TrafficSample::downloadBytesPerSecond), downloadColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(pathOf(TrafficSample::uploadBytesPerSecond), uploadColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun ConnectionAction(
    activeProfile: ProfileMetadata?,
    selectedAppCount: Int,
    blockedAppCount: Int,
    appScopeMode: AppScopeMode,
    vpnState: VpnConnectionState,
    onAddProfile: () -> Unit,
    onSelectApps: () -> Unit,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    when {
        activeProfile == null -> Button(
            onClick = onAddProfile,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("Добавить профиль")
        }
        selectedAppCount == 0 &&
            (appScopeMode == AppScopeMode.Exclude || blockedAppCount == 0) &&
            (vpnState == VpnConnectionState.Stopped || vpnState is VpnConnectionState.Error) -> {
            Text(
                if (appScopeMode == AppScopeMode.Include) {
                    "Выберите хотя бы одно приложение для VPN."
                } else {
                    "Выберите хотя бы одно приложение для прямого доступа вне VPN."
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onSelectApps,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    if (appScopeMode == AppScopeMode.Include) {
                        "Выбрать приложения"
                    } else {
                        "Выбрать приложения напрямую"
                    },
                )
            }
        }
        else -> when (vpnState) {
            VpnConnectionState.Stopped,
            is VpnConnectionState.Error,
            -> Button(
                onClick = { onStart(activeProfile.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Подключить")
            }
            is VpnConnectionState.Starting,
            is VpnConnectionState.Reconnecting,
            is VpnConnectionState.Stopping,
            -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Остановить") }
            }
            is VpnConnectionState.Paused -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Остановить") }
                Button(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Подключить сейчас") }
            }
            is VpnConnectionState.Connected -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("Перезапустить")
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("Отключить")
                }
            }
        }
    }
}

@Composable
private fun ServerSelectorSheet(
    groups: List<RuntimeSelectorGroup>,
    sessionPingMillis: Long?,
    onSelect: (RuntimeSelectorGroup, RuntimeOutboundItem) -> Unit,
    onMeasureGroup: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("Серверы", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Пинг хранится только для текущей сессии.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        groups.filter { it.items.isNotEmpty() }.forEach { group ->
            item(key = "header-${group.tag}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.tag,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = { onMeasureGroup(group.tag) }) { Text("Проверить") }
                }
            }
            items(group.items, key = { "${group.tag}-${it.tag}" }) { item ->
                val selected = group.selected == item.tag
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = group.selectable) { onSelect(group, item) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = if (group.selectable) ({ onSelect(group, item) }) else null,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.tag, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        Text(
                            listOfNotNull(item.type.uppercase(), item.endpoint).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        formatPing(item.pingMillis?.toLong() ?: sessionPingMillis.takeIf { selected }),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            item(key = "divider-${group.tag}") { HorizontalDivider() }
        }
    }
}

private fun VpnConnectionState.title(): String = when (this) {
    VpnConnectionState.Stopped -> "VPN выключен"
    is VpnConnectionState.Starting -> "Подключение"
    is VpnConnectionState.Connected -> "VPN подключён"
    is VpnConnectionState.Paused -> "VPN на паузе"
    is VpnConnectionState.Stopping -> "Отключение"
    is VpnConnectionState.Reconnecting -> "Переподключение"
    is VpnConnectionState.Error -> "Ошибка VPN"
}

internal fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = safe.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$safe ${units[unit]}" else "%.1f %s".format(java.util.Locale.US, value, units[unit])
}

internal fun formatDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    val minutes = seconds / 60 % 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%02d:%02d".format(minutes, rest)
}

internal fun formatPing(value: Long?): String {
    val millis = value?.coerceAtLeast(0) ?: return "—"
    if (millis < 1_000) return "$millis мс"

    val hundredths = millis / 10 + if (millis % 10 >= 5) 1 else 0
    val seconds = hundredths / 100
    val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$seconds с" else "$seconds,$fraction с"
}
