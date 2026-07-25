package io.github.zapretkvn.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.DnsOverride
import io.github.zapretkvn.android.diagnostics.DiagnosticAttemptOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticState
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.diagnostics.DiagnosticStopOutcome
import io.github.zapretkvn.android.hardening.TunMtuMode
import io.github.zapretkvn.android.profiles.ProfilesUiState
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.android.updates.UpdateChannel
import io.github.zapretkvn.android.updates.UpdateOperation
import io.github.zapretkvn.android.updates.UpdateState
import io.github.zapretkvn.android.vpn.VpnConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class SettingsDestination {
    Main,
    VpnHiding,
    Diagnostics,
    Community,
    About,
}

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    state: ProfilesUiState,
    vpnState: VpnConnectionState,
    diagnostics: DiagnosticState,
    viewModel: ProfilesViewModel,
    onDiagnosticsSelected: (Boolean) -> Unit,
    onCreateDiagnosticShare: suspend () -> Intent,
    onClearDnsCache: () -> Unit,
    updateState: UpdateState,
    onCheckUpdate: (UpdateChannel) -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.Main) }
    val goBack = { destination = SettingsDestination.Main }
    BackHandler(enabled = destination != SettingsDestination.Main, onBack = goBack)

    when (destination) {
        SettingsDestination.Main -> SettingsMain(
            contentPadding = contentPadding,
            state = state,
            viewModel = viewModel,
            onClearDnsCache = onClearDnsCache,
            updateState = updateState,
            onCheckUpdate = onCheckUpdate,
            onDownloadUpdate = onDownloadUpdate,
            onInstallUpdate = onInstallUpdate,
            onCancelUpdate = onCancelUpdate,
            onOpen = { destination = it },
        )
        SettingsDestination.VpnHiding -> VpnHidingSettings(
            contentPadding = contentPadding,
            state = state,
            viewModel = viewModel,
            onBack = goBack,
        )
        SettingsDestination.Diagnostics -> DiagnosticsSettings(
            contentPadding = contentPadding,
            vpnState = vpnState,
            diagnostics = diagnostics,
            onSelected = onDiagnosticsSelected,
            onCreateDiagnosticShare = onCreateDiagnosticShare,
            onClearDnsCache = onClearDnsCache,
            onBack = goBack,
        )
        SettingsDestination.Community -> CommunitySettings(
            contentPadding = contentPadding,
            onBack = goBack,
        )
        SettingsDestination.About -> AboutSettings(
            contentPadding = contentPadding,
            onBack = goBack,
        )
    }
}

@Composable
private fun SettingsMain(
    contentPadding: PaddingValues,
    state: ProfilesUiState,
    viewModel: ProfilesViewModel,
    onClearDnsCache: () -> Unit,
    updateState: UpdateState,
    onCheckUpdate: (UpdateChannel) -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
    onOpen: (SettingsDestination) -> Unit,
) {
    var editDnsOverride by rememberSaveable { mutableStateOf(false) }
    if (editDnsOverride) {
        DnsOverrideDialog(
            current = state.settings.dnsOverride,
            onDismiss = { editDnsOverride = false },
            onSave = { hostname, ipv4Address ->
                viewModel.setDnsOverride(hostname, ipv4Address)
                editDnsOverride = false
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag("settings-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "appearance") {
            SettingsCard(title = "Оформление") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.settings.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                            label = { Text(mode.displayName()) },
                        )
                    }
                }
                Text(
                    "Системная тема следует Android; Dynamic Color используется на Android 12+.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Перенос строк JSON")
                        Text(
                            "Только вид редактора; профиль не меняется",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.settings.rawEditorLineWrap,
                        onCheckedChange = viewModel::setRawEditorLineWrap,
                        modifier = Modifier.semantics {
                            contentDescription = "Перенос строк в редакторе JSON"
                        },
                    )
                }
            }
        }

        item(key = "dns") {
            SettingsCard(title = "DNS") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DnsMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.settings.dnsMode == mode,
                            onClick = { viewModel.setDnsMode(mode) },
                            label = { Text(mode.displayName()) },
                        )
                    }
                }
                Text(
                    state.settings.dnsMode.description(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Перехватывается TCP/UDP 53; встроенный DoH, DoT и mDNS не перехватываются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Только IPv4 через VPN")
                        Text(
                            "Включено по умолчанию. Убирает AAAA после перехода автоматического режима на DNS Android/DoH; DNS профиля и «Из JSON» не меняются. Выключите для IPv6-only сайтов.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.settings.proxyIpv4Only,
                        onCheckedChange = viewModel::setProxyIpv4Only,
                        enabled = state.settings.dnsMode != DnsMode.FromJson,
                        modifier = Modifier.semantics {
                            contentDescription = "Только IPv4 через VPN"
                        },
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("DNS-переопределение")
                        Text(
                            "${state.settings.dnsOverride.hostname} → ${state.settings.dnsOverride.ipv4Address}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.settings.dnsOverride.enabled,
                        onCheckedChange = viewModel::setDnsOverrideEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "DNS-переопределение включено"
                        },
                    )
                }
                Text(
                    if (state.settings.dnsMode == DnsMode.FromJson) {
                        "Сохранено, но не применяется в режиме «Из JSON»."
                    } else if (state.settings.dnsMode == DnsMode.Automatic) {
                        "Применяется только после автоматического перехода с DNS профиля на DNS Android или DoH."
                    } else {
                        "Точный домен получает указанный IPv4 до обращения к DNS. Встроенный DoH/DoT приложения может обойти правило."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { editDnsOverride = true },
                    modifier = Modifier.testTag("dns-override-edit"),
                ) {
                    Text("Изменить DNS-переопределение")
                }
                OutlinedButton(onClick = onClearDnsCache) { Text("Очистить DNS-кэш") }
                Text(
                    "Удаляет bootstrap LKG и контролируемо перезапускает core. Кэш Android не очищается.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "updates") {
            SettingsCard(title = "Обновления") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateChannel.entries.forEach { channel ->
                        FilterChip(
                            selected = state.settings.updateChannel == channel,
                            onClick = { viewModel.setUpdateChannel(channel) },
                            label = { Text(channel.displayName()) },
                        )
                    }
                }
                Text(
                    "Для каждой новой версии автоматически выбирается канал её сборки: Stable для обычной, Beta для prerelease. Его можно сменить вручную; ядро обновляется вместе с APK.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateControls(
                    state = updateState,
                    channel = state.settings.updateChannel,
                    onCheck = onCheckUpdate,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate,
                    onCancel = onCancelUpdate,
                )
            }
        }

        item(key = "sections") {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                SettingsLinkRow(
                    title = "Скрытие VPN",
                    subtitle = "Rootless-защита от localhost-проб и экспериментальные параметры TUN",
                    onClick = { onOpen(SettingsDestination.VpnHiding) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsLinkRow(
                    title = "Диагностика",
                    subtitle = "Состояние VPN, версии и обслуживание DNS",
                    onClick = { onOpen(SettingsDestination.Diagnostics) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsLinkRow(
                    title = "Сообщество",
                    subtitle = "Официальные Telegram-ссылки Zapret KVN",
                    onClick = { onOpen(SettingsDestination.Community) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsLinkRow(
                    title = "О приложении",
                    subtitle = "Версия приложения, Android и pinned core",
                    onClick = { onOpen(SettingsDestination.About) },
                )
            }
        }
    }
}

@Composable
private fun DnsOverrideDialog(
    current: DnsOverride,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var hostname by rememberSaveable(current.hostname) { mutableStateOf(current.hostname) }
    var ipv4Address by rememberSaveable(current.ipv4Address) { mutableStateOf(current.ipv4Address) }
    val validationMessage = DnsOverride.validationMessage(hostname, ipv4Address)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DNS-переопределение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it },
                    label = { Text("Точный домен") },
                    singleLine = true,
                    isError = DnsOverride.validationMessage(hostname, DnsOverride.DEFAULT_IPV4_ADDRESS) != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dns-override-hostname")
                        .semantics { contentDescription = "Домен DNS-переопределения" },
                )
                OutlinedTextField(
                    value = ipv4Address,
                    onValueChange = { ipv4Address = it },
                    label = { Text("IPv4-адрес") },
                    singleLine = true,
                    isError = DnsOverride.validationMessage(DnsOverride.DEFAULT_HOSTNAME, ipv4Address) != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dns-override-ipv4")
                        .semantics { contentDescription = "IPv4 DNS-переопределения" },
                )
                Text(
                    validationMessage
                        ?: "HTTPS продолжит проверять исходное имя домена и его сертификат.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (validationMessage == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(hostname, ipv4Address) },
                enabled = validationMessage == null,
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun UpdateControls(
    state: UpdateState,
    channel: UpdateChannel,
    onCheck: (UpdateChannel) -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        UpdateState.Idle -> Button(
            onClick = { onCheck(channel) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Проверить обновления") }

        is UpdateState.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                Text("Проверка канала ${state.channel}…")
            }
            OutlinedButton(onClick = onCancel) { Text("Отмена") }
        }

        is UpdateState.RetryingViaVpn -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                Text(
                    when (state.operation) {
                        UpdateOperation.Check -> "GitHub недоступен напрямую. Временно включаем VPN для проверки…"
                        UpdateOperation.Download -> "Загрузка заблокирована. Временно включаем VPN для updater…"
                    },
                )
            }
            OutlinedButton(onClick = onCancel) { Text("Отмена") }
        }

        is UpdateState.UpToDate -> {
            Text("Установлена последняя версия ${state.currentVersion} (${state.checkedTag}).")
            OutlinedButton(onClick = { onCheck(channel) }) { Text("Проверить ещё раз") }
        }

        is UpdateState.Available -> {
            ReleaseSummary(
                state.candidate.metadata.versionName,
                state.candidate.metadata.coreTag,
                state.candidate.metadata.abi.single(),
            )
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Скачать и проверить APK") }
        }

        is UpdateState.Downloading -> {
            val progress = if (state.totalBytes > 0) {
                (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            ReleaseSummary(
                state.candidate.metadata.versionName,
                state.candidate.metadata.coreTag,
                state.candidate.metadata.abi.single(),
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "${formatUpdateBytes(state.downloadedBytes)} / ${formatUpdateBytes(state.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onCancel) { Text("Отменить и удалить") }
        }

        is UpdateState.Ready -> {
            ReleaseSummary(
                state.candidate.metadata.versionName,
                state.candidate.metadata.coreTag,
                state.candidate.metadata.abi.single(),
            )
            Text("SHA-256, package, version и подпись проверены.")
            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Открыть системную установку") }
            OutlinedButton(onClick = onCancel) { Text("Удалить APK") }
        }

        is UpdateState.Failure -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            if (state.candidate != null) {
                Button(onClick = onDownload) { Text("Повторить загрузку") }
            }
            OutlinedButton(onClick = { onCheck(channel) }) { Text("Проверить заново") }
        }
    }
}

@Composable
private fun ReleaseSummary(versionName: String, coreTag: String, abi: String) {
    Text("Доступна версия $versionName", fontWeight = FontWeight.SemiBold)
    Text("Core $coreTag · $abi", style = MaterialTheme.typography.bodySmall)
}

private fun formatUpdateBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1f МБ".format(value / (1024.0 * 1024.0))
    value >= 1024 -> "%.1f КБ".format(value / 1024.0)
    else -> "$value Б"
}

@Composable
private fun VpnHidingSettings(
    contentPadding: PaddingValues,
    state: ProfilesUiState,
    viewModel: ProfilesViewModel,
    onBack: () -> Unit,
) {
    val options = state.settings.vpnHiding
    SettingsSubpage(contentPadding, "Скрытие VPN", onBack) {
        SettingsCard(title = "Возможности rootless-режима") {
            Text(
                "Защита закрывает локальные proxy/API и уменьшает технические признаки. " +
                    "Обычное приложение не может скрыть созданные Android TRANSPORT_VPN и TUN-интерфейс.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Здесь нет фонового сканирования, таймеров или дополнительного процесса.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(title = "Защита от активных чекеров") {
            VpnHidingSwitchRow(
                title = "Блокировать localhost endpoints",
                subtitle = "Удаляет Clash/V2Ray API и запрещает дополнительные SOCKS/HTTP/mixed inbounds в runtime.",
                checked = options.blockLocalEndpoints,
                onCheckedChange = viewModel::setVpnHidingBlockLocalEndpoints,
                testTag = "vpn-hiding-local-endpoints",
            )
            if (!options.blockLocalEndpoints) {
                Text(
                    "Защита отключена: raw JSON сможет открыть локальный controller или proxy для других приложений.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Обход через Network.bindSocket не разрешается: VpnService.Builder.allowBypass() не используется.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(title = "Сетевые параметры") {
            VpnHidingSwitchRow(
                title = "Нейтральное имя VPN-сессии",
                subtitle = "Показывает «Системная сеть» вместо имени клиента в доступных Android-представлениях.",
                checked = options.neutralSessionName,
                onCheckedChange = viewModel::setVpnHidingNeutralSessionName,
                testTag = "vpn-hiding-session-name",
            )
            HorizontalDivider()
            Text("MTU TUN", fontWeight = FontWeight.Medium)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TunMtuMode.entries.forEach { mode ->
                    FilterChip(
                        selected = options.tunMtuMode == mode,
                        onClick = { viewModel.setVpnHidingTunMtuMode(mode) },
                        label = { Text(mode.displayName()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vpn-hiding-mtu-${mode.name}"),
                    )
                }
            }
            Text(
                options.tunMtuMode.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "Изменение параметров контролируемо перезапускает активное подключение. " +
                "Сохранённый JSON профиля не переписывается.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VpnHidingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .testTag(testTag)
                .semantics { contentDescription = title },
        )
    }
}

@Composable
private fun DiagnosticsSettings(
    contentPadding: PaddingValues,
    vpnState: VpnConnectionState,
    diagnostics: DiagnosticState,
    onSelected: (Boolean) -> Unit,
    onCreateDiagnosticShare: suspend () -> Intent,
    onClearDnsCache: () -> Unit,
    onBack: () -> Unit,
) {
    DisposableEffect(Unit) {
        onSelected(true)
        onDispose { onSelected(false) }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var timelineExpanded by rememberSaveable { mutableStateOf(false) }
    var stopTimelineExpanded by rememberSaveable { mutableStateOf(false) }
    var crashExpanded by rememberSaveable { mutableStateOf(false) }
    var logsExpanded by rememberSaveable { mutableStateOf(false) }
    var overlayExpanded by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    SettingsSubpage(contentPadding, "Диагностика", onBack) {
        Column(
            modifier = Modifier.testTag("diagnostics-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Текущее состояние", style = MaterialTheme.typography.titleMedium)
                Text(vpnState.diagnosticLabel(), fontWeight = FontWeight.SemiBold)
            }
        }
        OutlinedButton(
            enabled = !exporting,
            onClick = {
                scope.launch {
                    exporting = true
                    exportError = null
                    try {
                        val shareIntent = onCreateDiagnosticShare()
                        context.startActivity(
                            Intent.createChooser(shareIntent, "Передать диагностику"),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: ActivityNotFoundException) {
                        exportError = "Не найдено приложение для передачи файла."
                    } catch (_: SecurityException) {
                        exportError = "Android запретил передачу файла."
                    } catch (_: Throwable) {
                        exportError = "Не удалось создать диагностический файл."
                    } finally {
                        exporting = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("export-diagnostics"),
        ) {
            if (exporting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text("Экспортировать диагностику")
        }
        exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "Создаёт временный redacted diagnostic JSON и открывает системное окно отправки.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Последняя ошибка", style = MaterialTheme.typography.titleMedium)
                val failure = diagnostics.lastFailure
                if (failure == null) {
                    Text("Ошибок текущего запуска нет.")
                } else {
                    Text(
                        "${failure.supportCode} · ${failure.type.title}",
                        modifier = Modifier.testTag("diagnostic-error-type"),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(failure.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    failure.technicalDetail?.let { detail ->
                        Text(
                            "Технически: $detail",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Время остановки", style = MaterialTheme.typography.titleMedium)
                val attempt = diagnostics.stopAttempt
                if (attempt == null) {
                    Text("Замеры появятся после следующей остановки VPN.")
                } else {
                    Text(
                        if (attempt.outcome == DiagnosticStopOutcome.Running) {
                            "Остановка выполняется"
                        } else {
                            "Остановлено за ${formatDiagnosticDuration(attempt.totalDurationMillis)}"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    attempt.stages.lastOrNull { it.status == DiagnosticStageStatus.Running }?.let { stage ->
                        Text(
                            "Сейчас: ${stage.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    attempt.slowestCompletedStage?.let { stage ->
                        Text(
                            "Самый долгий этап: ${stage.label} — ${formatDiagnosticDuration(stage.durationMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("diagnostic-stop-slowest-stage"),
                        )
                    }
                    OutlinedButton(
                        onClick = { stopTimelineExpanded = !stopTimelineExpanded },
                        modifier = Modifier.testTag("diagnostic-stop-timeline-toggle"),
                    ) {
                        Text(
                            if (stopTimelineExpanded) {
                                "Скрыть этапы"
                            } else {
                                "Показать этапы (${attempt.stages.size})"
                            },
                        )
                    }
                    if (stopTimelineExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            attempt.stages.forEach { stage ->
                                Text(
                                    "${stage.status.symbol()} ${stage.label} — " +
                                        (stage.durationMillis?.let(::formatDiagnosticDuration)
                                            ?: "выполняется") +
                                        (stage.detail?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (stage.status == DiagnosticStageStatus.Failed) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
                Text(
                    "TUN закрывается до клиентов и libbox; каждый этап измеряется отдельно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Время подключения", style = MaterialTheme.typography.titleMedium)
                val attempt = diagnostics.connectionAttempt
                if (attempt == null) {
                    Text("Замеры появятся после следующей попытки подключения.")
                } else {
                    Text(
                        attempt.outcome.diagnosticLabel(attempt.totalDurationMillis),
                        fontWeight = FontWeight.SemiBold,
                        color = if (attempt.outcome == DiagnosticAttemptOutcome.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        "Триггер: ${attempt.trigger}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    attempt.stages.lastOrNull { it.status == DiagnosticStageStatus.Running }?.let { stage ->
                        Text(
                            "Сейчас: ${stage.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    attempt.slowestCompletedStage?.let { stage ->
                        Text(
                            "Самый долгий этап: ${stage.label} — ${formatDiagnosticDuration(stage.durationMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("diagnostic-slowest-stage"),
                        )
                    }
                    OutlinedButton(
                        onClick = { timelineExpanded = !timelineExpanded },
                        modifier = Modifier.testTag("diagnostic-timeline-toggle"),
                    ) {
                        Text(if (timelineExpanded) "Скрыть этапы" else "Показать этапы (${attempt.stages.size})")
                    }
                    if (timelineExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            attempt.stages.forEach { stage ->
                                Text(
                                    "${stage.status.symbol()} ${stage.label} — " +
                                        (stage.durationMillis?.let(::formatDiagnosticDuration) ?: "выполняется") +
                                        (stage.detail?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (stage.status == DiagnosticStageStatus.Failed) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                    if (diagnostics.previousConnectionAttempts.isNotEmpty()) {
                        Text("Предыдущие попытки", fontWeight = FontWeight.SemiBold)
                        diagnostics.previousConnectionAttempts.takeLast(2).asReversed().forEach { previous ->
                            Text(
                                "${previous.trigger}: " +
                                    previous.outcome.diagnosticLabel(previous.totalDurationMillis) +
                                    (previous.failure?.let { " · ${it.supportCode}" } ?: "") +
                                    (previous.slowestCompletedStage?.let { " · ${it.label}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (previous.outcome == DiagnosticAttemptOutcome.Failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Text(
                    "Замеры делаются только на событиях запуска: без таймера, polling и фонового трафика.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Предыдущий crash приложения", style = MaterialTheme.typography.titleMedium)
                val crash = diagnostics.previousCrash
                if (crash == null) {
                    Text("Сохранённого Kotlin/Java crash нет.")
                } else {
                    Text(
                        crash.exceptionType,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("diagnostic-previous-crash"),
                    )
                    Text(formatDiagnosticTimestamp(crash.occurredAtEpochMillis))
                    crash.message?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (crash.stack.isNotEmpty()) {
                        OutlinedButton(onClick = { crashExpanded = !crashExpanded }) {
                            Text(if (crashExpanded) "Скрыть стек" else "Показать короткий стек")
                        }
                    }
                    if (crashExpanded) {
                        Text(
                            crash.stack.joinToString("\n") { frame ->
                                "${frame.className}.${frame.methodName}:${frame.lineNumber}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Text(
                    "Хранится только один redacted Kotlin/Java crash без сетевого runtime-лога. " +
                        "На API 30+ Android отдельно сообщает тип последнего native crash/ANR без тяжёлого trace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                diagnostics.previousProcessExit?.let { exit ->
                    HorizontalDivider()
                    Text("Предыдущее завершение процесса", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${exit.reason} · status ${exit.status}",
                        color = if (exit.reason == "native_crash" || exit.reason == "anr") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        "${formatDiagnosticTimestamp(exit.occurredAtEpochMillis)} · " +
                            "PSS ${exit.pssKilobytes} KiB · RSS ${exit.rssKilobytes} KiB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    exit.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (Build.VERSION.SDK_INT < 30) {
                    Text(
                        "История native crash/ANR через Android доступна начиная с API 30.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Среда", style = MaterialTheme.typography.titleMedium)
                Text("Zapret KVN ${BuildConfig.VERSION_NAME}")
                Text("Core ${BuildConfig.CORE_TAG} · ${BuildConfig.CORE_COMMIT.take(12)}")
                Text(
                    "Android patch ${BuildConfig.CORE_PATCH_SHA256.take(12)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                val vpnPolicy = diagnostics.vpnPolicy
                when {
                    vpnPolicy == null -> Text(
                        "Always-on/Lockdown: статус появится при запуске VPN.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    !vpnPolicy.statusAvailable -> Text(
                        "Always-on/Lockdown: Android API < 29 не даёт публичный статус.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    vpnPolicy.alwaysOn || vpnPolicy.lockdown -> Text(
                        "Always-on: ${vpnPolicy.alwaysOn.yesNo()} · Lockdown: ${vpnPolicy.lockdown.yesNo()}. " +
                            "Эти режимы пока не поддерживаются; отключите их в Android.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        "Always-on: нет · Lockdown: нет",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Сеть Android", style = MaterialTheme.typography.titleMedium)
                val network = diagnostics.network
                if (network == null) {
                    Text("Состояние появится при подключении или экспорте отчёта.")
                } else {
                    Text(
                        "${network.transport} · ${network.interfaceName ?: "интерфейс не определён"}",
                    )
                    Text(
                        "Validated: ${network.validated.yesNo()} · Metered: ${network.metered.yesNo()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Private DNS: ${network.privateDnsMode} · active: ${network.privateDnsActive.yesNo()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Последние логи", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (diagnostics.logStreamActive) {
                        "Поток core активен только пока открыт этот экран."
                    } else {
                        "Поток core не активен; в памяти сохранены только bounded строки текущего запуска."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Core: получено ${diagnostics.coreLogStats.receivedLines}, " +
                        "схлопнуто ${diagnostics.coreLogStats.coalescedLines}, " +
                        "отброшено ${diagnostics.coreLogStats.droppedLines}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { logsExpanded = !logsExpanded },
                    modifier = Modifier.testTag("diagnostic-logs-toggle"),
                ) {
                    Text(if (logsExpanded) "Скрыть (${diagnostics.logs.size})" else "Показать (${diagnostics.logs.size})")
                }
                if (logsExpanded) {
                    if (diagnostics.logs.isEmpty()) {
                        Text("Строк логов пока нет.")
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            diagnostics.logs.forEach { line ->
                                Text(
                                    "${line.levelName}/${line.category.code}: ${line.message}" +
                                        if (line.repeatCount > 1) " ×${line.repeatCount}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
        diagnostics.effectiveOverlay?.let { overlay ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Effective overlay", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Только структура managed zapret-* без адресов, правил сопоставления и credentials.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { overlayExpanded = !overlayExpanded }) {
                        Text(if (overlayExpanded) "Скрыть" else "Показать")
                    }
                    if (overlayExpanded) {
                        Text(
                            overlay,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onClearDnsCache) { Text("Очистить DNS-кэш и перезапустить core") }
        Text(
            "Runtime-лог на диск не пишется; временный export удаляется при следующем запуске.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }
}

@Composable
private fun CommunitySettings(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var openError by remember { mutableStateOf<String?>(null) }
    val openLink: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            openError = null
        } catch (_: ActivityNotFoundException) {
            openError = "Не найдено приложение для открытия ссылки."
        } catch (_: SecurityException) {
            openError = "Android запретил открыть ссылку."
        }
    }

    SettingsSubpage(contentPadding, "Сообщество", onBack) {
        Text(
            "Независимый клиент работает с любыми совместимыми профилями. Эти ссылки относятся только к сообществу Zapret KVN.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            SettingsLinkRow(
                title = "Zapret KVN",
                subtitle = "Telegram-канал",
                onClick = { openLink("https://t.me/bypassblock") },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsLinkRow(
                title = "VPN Discord YouTube",
                subtitle = "Telegram-сообщество",
                onClick = { openLink("https://t.me/vpndiscordyooutube") },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsLinkRow(
                title = "Zapret VPN bot",
                subtitle = "Telegram-бот",
                onClick = { openLink("https://t.me/zapretvpns_bot") },
            )
        }
        openError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun AboutSettings(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    SettingsSubpage(contentPadding, "О приложении", onBack) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Zapret KVN", style = MaterialTheme.typography.headlineSmall)
                Text("Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text(
                    "Независимый Android-клиент и GUI для sing-box-extended. Конфигурация хранится как настоящий sing-box JSON.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Ядро", style = MaterialTheme.typography.titleMedium)
                Text(BuildConfig.CORE_TAG)
                Text(BuildConfig.CORE_COMMIT, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Core встроен в APK из зафиксированной ревизии; динамической загрузки ядра нет.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Известные ограничения MVP", style = MaterialTheme.typography.titleMedium)
                Text("• Android 8+; release APK разделены на arm64-v8a, armeabi-v7a и x86_64, интерфейс рассчитан на телефоны.")
                Text("• Always-on/Lockdown и shared UID не поддерживаются как гарантированный per-app режим.")
                Text("• Rootless-защита не скрывает системный TRANSPORT_VPN, TUN и установленный APK.")
                Text("• Managed DNS перехватывает TCP/UDP 53, но не встроенный DoH, DoT или mDNS; FakeIP выключен.")
                Text("• Domain-only block не является firewall: для гарантии нужен IP/CIDR rule-set.")
                Text("• Clash YAML и Hysteria v1 URI пока не импортируются; raw JSON остаётся ответственностью пользователя.")
                Text("• APK проверяется один раз при запуске; загрузка требует подтверждения, после проверки системная установка открывается автоматически. Финальное подтверждение остаётся за Android, silent install недоступен. Подписки не обновляются в фоне, core обновляется вместе с APK.")
                Text("• При неработающем proxy/DNS VPN закрывается без бесконечного retry и plaintext DNS fallback.")
            }
        }
        Text(
            "Лицензия приложения: GPL-3.0-or-later. Распространение закрытых производных APK запрещено; полный соответствующий исходный код обязателен. Название и фирменное оформление Zapret KVN не лицензируются GPL. Лицензии ядра и библиотек включены в APK и NOTICE.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsSubpage(
    contentPadding: PaddingValues,
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        item(key = "content") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = { content() })
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun SettingsLinkRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title. $subtitle" }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", style = MaterialTheme.typography.headlineSmall)
    }
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.System -> "Системная"
    ThemeMode.Light -> "Светлая"
    ThemeMode.Dark -> "Тёмная"
}

private fun UpdateChannel.displayName(): String = when (this) {
    UpdateChannel.Stable -> "Stable"
    UpdateChannel.Beta -> "Beta"
}

private fun DnsMode.displayName(): String = when (this) {
    DnsMode.Automatic -> "Автоматически"
    DnsMode.Android -> "DNS Android"
    DnsMode.Secure -> "Защищённый через VPN"
    DnsMode.FromJson -> "Из JSON"
}

private fun DnsMode.description(): String = when (this) {
    DnsMode.Automatic -> "DNS профиля → DNS Android → DoH. Переход только после подтверждённой ошибки."
    DnsMode.Android -> "Системный resolver и Private DNS Android остаются источником истины."
    DnsMode.Secure -> "Стандартный DNS выбранных приложений идёт в DoH через proxy."
    DnsMode.FromJson -> "DNS профиля используется как есть; если его нет — local DNS Android без DoH."
}

private fun TunMtuMode.displayName(): String = when (this) {
    TunMtuMode.CoreDefault -> "По профилю"
    TunMtuMode.Normalize1500 -> "Нормализовать 1500"
}

private fun TunMtuMode.description(): String = when (this) {
    TunMtuMode.CoreDefault ->
        "Используется MTU профиля; для WireGuard без tun.mtu — MTU endpoint, " +
            "для остальных — Android-default ядра."
    TunMtuMode.Normalize1500 ->
        "Используется по умолчанию: runtime ограничивает TUN до MTU 1500. " +
            "Для userspace WireGuard выбирается меньшее из 1500 и MTU endpoint. JSON не меняется."
}

private fun VpnConnectionState.diagnosticLabel(): String = when (this) {
    VpnConnectionState.Stopped -> "VPN выключен"
    is VpnConnectionState.Starting -> "Подключение: $message"
    is VpnConnectionState.Connected -> "Подключено: $profileName"
    is VpnConnectionState.Stopping -> "Отключение"
    is VpnConnectionState.Error -> "Ошибка VPN"
}

private fun DiagnosticAttemptOutcome.diagnosticLabel(totalDurationMillis: Long?): String = when (this) {
    DiagnosticAttemptOutcome.Running -> "Подключение выполняется"
    DiagnosticAttemptOutcome.Connected -> "Подключено за ${formatDiagnosticDuration(totalDurationMillis)}"
    DiagnosticAttemptOutcome.Failed -> "Ошибка через ${formatDiagnosticDuration(totalDurationMillis)}"
    DiagnosticAttemptOutcome.Cancelled -> "Попытка отменена через ${formatDiagnosticDuration(totalDurationMillis)}"
}

private fun DiagnosticStageStatus.symbol(): String = when (this) {
    DiagnosticStageStatus.Running -> "…"
    DiagnosticStageStatus.Success -> "✓"
    DiagnosticStageStatus.Recovered -> "↻"
    DiagnosticStageStatus.Failed -> "×"
    DiagnosticStageStatus.Cancelled -> "—"
}

private fun formatDiagnosticDuration(value: Long?): String = when {
    value == null -> "не измерено"
    value < 1_000 -> "$value мс"
    else -> "%.2f с".format(value / 1_000.0)
}

private fun formatDiagnosticTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(epochMillis))

private fun Boolean.yesNo(): String = if (this) "да" else "нет"
