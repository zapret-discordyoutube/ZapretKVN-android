package io.github.zapretkvn.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import io.github.zapretkvn.android.importer.qrImportScanOptions
import io.github.zapretkvn.android.diagnostics.DiagnosticState
import io.github.zapretkvn.android.profiles.ImportPreviewState
import io.github.zapretkvn.android.profiles.ProfileEditorState
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.profiles.ProfilesUiState
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.android.routing.RoutingUiState
import io.github.zapretkvn.android.routing.RoutingViewModel
import io.github.zapretkvn.android.updates.UpdateCandidate
import io.github.zapretkvn.android.updates.UpdateChannel
import io.github.zapretkvn.android.updates.UpdateState
import io.github.zapretkvn.android.vpn.AppScopeMode
import io.github.zapretkvn.android.vpn.AppsUiState
import io.github.zapretkvn.android.vpn.AppsViewModel
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnSessionStats
import java.text.DateFormat
import java.util.Date

private enum class AppTab(
    val title: String,
    val icon: ImageVector,
) {
    Home("Главная", Icons.Default.Home),
    Profiles("Профили", Icons.AutoMirrored.Filled.List),
    Routing("Маршруты", Icons.Default.Share),
    Settings("Настройки", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapretApp(
    profilesViewModel: ProfilesViewModel,
    state: ProfilesUiState,
    appsViewModel: AppsViewModel,
    appsState: AppsUiState,
    routingViewModel: RoutingViewModel,
    routingState: RoutingUiState,
    vpnState: VpnConnectionState,
    selectorGroups: List<RuntimeSelectorGroup>,
    sessionStats: VpnSessionStats,
    diagnostics: DiagnosticState,
    vpnMessage: String?,
    onVpnMessageConsumed: () -> Unit,
    onVpnStart: (String) -> Unit,
    onVpnStop: () -> Unit,
    onSelectOutbound: (String, String, String) -> Unit,
    onMeasurePing: () -> Unit,
    onMeasureGroup: (String) -> Unit,
    onHomeSelected: (Boolean) -> Unit,
    onDiagnosticsSelected: (Boolean) -> Unit,
    onCreateDiagnosticShare: suspend () -> Intent,
    onClearDnsCache: () -> Unit,
    updateState: UpdateState,
    onCheckUpdate: (UpdateChannel) -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
) {
    var appPickerMode by rememberSaveable { mutableStateOf<AppPickerMode?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var dismissedUpdateTag by rememberSaveable { mutableStateOf<String?>(null) }
    val homeSelected = state.editor == null && appPickerMode == null && selectedTab == AppTab.Home
    DisposableEffect(homeSelected) {
        onHomeSelected(homeSelected)
        onDispose { if (homeSelected) onHomeSelected(false) }
    }
    state.editor?.let { editor ->
        RawEditorScreen(profilesViewModel, editor, state.settings.rawEditorLineWrap, state.busy)
        return
    }
    appPickerMode?.let { pickerMode ->
        AppPickerScreen(
            state = appsState,
            viewModel = appsViewModel,
            mode = pickerMode,
            onBack = { appPickerMode = null },
        )
        return
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            profilesViewModel.consumeMessage()
        }
    }
    LaunchedEffect(vpnMessage) {
        vpnMessage?.let {
            snackbarHostState.showSnackbar(it)
            onVpnMessageConsumed()
        }
    }
    LaunchedEffect(routingState.message) {
        routingState.message?.let {
            snackbarHostState.showSnackbar(it)
            routingViewModel.consumeMessage()
        }
    }
    LaunchedEffect(updateState) {
        if (updateState == UpdateState.Idle || updateState is UpdateState.Checking) {
            dismissedUpdateTag = null
        }
    }
    val availableUpdate = (updateState as? UpdateState.Available)?.candidate
    if (availableUpdate != null && dismissedUpdateTag != availableUpdate.release.tag) {
        UpdateAvailableDialog(
            candidate = availableUpdate,
            onDownload = {
                dismissedUpdateTag = availableUpdate.release.tag
                onDownloadUpdate()
            },
            onLater = {
                dismissedUpdateTag = availableUpdate.release.tag
                onCancelUpdate()
            },
        )
    }
    LaunchedEffect(
        state.importCompletion,
        appsState.initialized,
        appsState.needsAppSelection,
    ) {
        if (
            state.importCompletion != null &&
            appsState.initialized &&
            !appsState.needsAppSelection
        ) {
            profilesViewModel.consumeImportCompletion()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(selectedTab.title) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                            )
                        },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { contentPadding ->
        when (selectedTab) {
            AppTab.Home -> HomeScreen(
                contentPadding = contentPadding,
                activeProfile = state.profiles.firstOrNull {
                    it.id == state.settings.activeProfileId
                },
                selectedAppCount = appsState.allowedPackages.size,
                blockedAppCount = appsState.blockedPackages.size,
                appScopeMode = appsState.scopeMode,
                onAddProfile = { selectedTab = AppTab.Profiles },
                onSelectApps = {
                    appsViewModel.refresh()
                    appPickerMode = AppPickerMode.VpnScope
                },
                vpnState = vpnState,
                selectorGroups = selectorGroups,
                sessionStats = sessionStats,
                onStart = { activeProfileId -> onVpnStart(activeProfileId) },
                onStop = onVpnStop,
                onSelectOutbound = onSelectOutbound,
                onMeasurePing = onMeasurePing,
                onMeasureGroup = onMeasureGroup,
            )
            AppTab.Profiles -> ProfilesScreen(
                contentPadding = contentPadding,
                state = state,
                viewModel = profilesViewModel,
                showAppSelectionAfterImport = appsState.needsAppSelection,
                onOpenAppPicker = {
                    appsViewModel.refresh()
                    appPickerMode = AppPickerMode.VpnScope
                },
            )
            AppTab.Routing -> RoutingScreen(
                contentPadding = contentPadding,
                appsState = appsState,
                appsViewModel = appsViewModel,
                routingState = routingState,
                routingViewModel = routingViewModel,
                onOpenPicker = {
                    appsViewModel.refresh()
                    appPickerMode = AppPickerMode.VpnScope
                },
                onOpenBlockPicker = {
                    appsViewModel.refresh()
                    appPickerMode = AppPickerMode.Blocked
                },
                onOpenAdvancedJson = {
                    routingState.activeProfileId?.let(profilesViewModel::openEditor)
                },
            )
            AppTab.Settings -> SettingsScreen(
                contentPadding = contentPadding,
                state = state,
                vpnState = vpnState,
                diagnostics = diagnostics,
                viewModel = profilesViewModel,
                onDiagnosticsSelected = onDiagnosticsSelected,
                onCreateDiagnosticShare = onCreateDiagnosticShare,
                onClearDnsCache = onClearDnsCache,
                updateState = updateState,
                onCheckUpdate = onCheckUpdate,
                onDownloadUpdate = onDownloadUpdate,
                onInstallUpdate = onInstallUpdate,
                onCancelUpdate = onCancelUpdate,
            )
        }
    }
}

@Composable
internal fun UpdateAvailableDialog(
    candidate: UpdateCandidate,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Доступно обновление ${candidate.metadata.versionName}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("update-release-notes"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Изменения", style = MaterialTheme.typography.titleMedium)
                ReleaseNotesMarkdown(
                    candidate.release.body.ifBlank {
                        "Автор релиза не добавил список изменений."
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = onDownload) { Text("Скачать") }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("Позже") }
        },
        modifier = Modifier.testTag("update-available-dialog"),
    )
}

@Composable
private fun ProfilesScreen(
    contentPadding: PaddingValues,
    state: ProfilesUiState,
    viewModel: ProfilesViewModel,
    showAppSelectionAfterImport: Boolean,
    onOpenAppPicker: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<ProfileMetadata?>(null) }
    var renameTarget by remember { mutableStateOf<ProfileMetadata?>(null) }
    var urlDialogOpen by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importDocument)
    }
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf(String::isNotBlank)?.let(viewModel::importQr)
    }
    val launchQrScanner = {
        qrLauncher.launch(qrImportScanOptions())
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchQrScanner() else cameraDenied = true
    }
    val requestQr = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchQrScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val groups = remember(state.profiles) { profileGroups(state.profiles) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .testTag("profiles-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Добавить профиль",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "JSON, WireGuard/AWG .conf, ссылка или подписка проходят preview и проверку ядром.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                fileLauncher.launch(
                                    arrayOf(
                                        "application/json",
                                        "application/x-wireguard-profile",
                                        "application/x-amneziawg-profile",
                                        "text/plain",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Файл") }
                        OutlinedButton(
                            onClick = viewModel::importClipboard,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Буфер") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { urlDialogOpen = true },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("URL") }
                        OutlinedButton(
                            onClick = requestQr,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("QR") }
                    }
                }
            }
        }

        if (state.busy) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Операция с профилем выполняется" },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        if (state.profiles.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.testTag("profiles-empty"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Профилей пока нет",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "Импортируйте настоящий sing-box JSON или поддерживаемую ссылку.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            groups.forEach { group ->
                item(key = "group-${group.title}") {
                    Text(
                        "${group.title} · ${group.profiles.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .semantics { heading() },
                    )
                }
                items(group.profiles, key = ProfileMetadata::id) { profile ->
                    ProfileCard(
                        profile = profile,
                        active = profile.id == state.settings.activeProfileId,
                        enabled = !state.busy,
                        onSelect = { viewModel.selectProfile(profile.id) },
                        onOpen = { viewModel.openEditor(profile.id) },
                        onRename = { renameTarget = profile },
                        onDelete = { deleteTarget = profile },
                        onRefresh = { viewModel.refreshSubscription(profile.id) },
                        refreshable = profile.id in state.refreshableProfileIds,
                    )
                }
            }
        }
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить профиль?") },
            text = { Text("${profile.name} и его backup будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile.id)
                        deleteTarget = null
                    },
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
            },
        )
    }

    renameTarget?.let { profile ->
        RenameDialog(
            initialName = profile.name,
            onDismiss = { renameTarget = null },
            onRename = { name ->
                viewModel.renameProfile(profile.id, name)
                renameTarget = null
            },
        )
    }

    if (urlDialogOpen) {
        UrlImportDialog(
            onDismiss = { urlDialogOpen = false },
            onImport = { url ->
                urlDialogOpen = false
                viewModel.importUrl(url)
            },
        )
    }

    if (cameraDenied) {
        AlertDialog(
            onDismissRequest = { cameraDenied = false },
            title = { Text("Камера недоступна") },
            text = { Text("Разрешение камеры нужно только на время открытия QR-сканера.") },
            confirmButton = {
                TextButton(onClick = { cameraDenied = false }) { Text("Понятно") }
            },
        )
    }

    state.importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            busy = state.busy,
            onDismiss = viewModel::dismissImportPreview,
            onCreate = viewModel::confirmImport,
            onAppend = viewModel::confirmAppend,
            onRefresh = viewModel::confirmRefresh,
        )
    }

    state.importCompletion?.takeIf { showAppSelectionAfterImport }?.let { completion ->
        AlertDialog(
            onDismissRequest = viewModel::consumeImportCompletion,
            title = { Text("Профиль готов") },
            text = {
                Text(
                    "${completion.profileName} сохранён. VPN не запускался. " +
                        "Для VPN не выбрано ни одного приложения. Выберите хотя бы одно, " +
                        "затем подключитесь вручную на главной.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.consumeImportCompletion()
                        onOpenAppPicker()
                    },
                ) { Text("Выбрать приложения") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::consumeImportCompletion) { Text("Позже") }
            },
        )
    }
}

@Composable
private fun UrlImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт по URL") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.take(4096) },
                label = { Text("HTTPS URL подписки") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onImport(url) }, enabled = url.isNotBlank()) {
                Text("Загрузить preview")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ImportPreviewDialog(
    preview: ImportPreviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onAppend: (String) -> Unit,
    onRefresh: (Boolean) -> Unit,
) {
    var name by remember(preview) { mutableStateOf(preview.suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (preview.isRefresh) "Обновление подписки" else "Предпросмотр импорта") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(preview.sourceDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (preview.serverCount > 0) "Серверов: ${preview.serverCount}" else "Готовый sing-box JSON",
                )
                if (preview.serverLabels.isNotEmpty()) {
                    Text(
                        preview.serverLabels.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!preview.isRefresh) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(80) },
                        label = { Text("Название профиля") },
                        singleLine = true,
                    )
                }
                preview.activityWarning?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (preview.selectionChanged) {
                    Text(
                        "Текущий server tag исчез: будет выбран первый доступный сервер.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (preview.activeRefresh) {
                    Text(
                        "Профиль сейчас подключён. Перезапуск возможен только отдельным подтверждением ниже.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (!preview.isRefresh && preview.isSingleManaged && preview.appendTargets.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Или добавить сервер в managed-группу:")
                    preview.appendTargets.take(4).forEach { profile ->
                        TextButton(onClick = { onAppend(profile.id) }, enabled = !busy) {
                            Text(profile.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (preview.isRefresh) {
                Column(horizontalAlignment = Alignment.End) {
                    if (preview.activeRefresh) {
                        TextButton(onClick = { onRefresh(true) }, enabled = !busy) {
                            Text("Сохранить и переподключить")
                        }
                    }
                    TextButton(onClick = { onRefresh(false) }, enabled = !busy) {
                        Text(if (preview.activeRefresh) "Сохранить без перезапуска" else "Обновить")
                    }
                }
            } else {
                TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank() && !busy) {
                    Text("Новый профиль")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}

@Composable
private fun ProfileCard(
    profile: ProfileMetadata,
    active: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    refreshable: Boolean,
) {
    val updatedAt = remember(profile.updatedAtEpochMillis) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(profile.updatedAtEpochMillis))
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append(profile.name)
                    append(". Источник: ")
                    append(profile.source.displayName())
                    append(". Обновлено: ")
                    append(updatedAt)
                    if (active) append(". Активный профиль")
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        profile.source.displayName(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Обновлено: $updatedAt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (active) Text("Активен", color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen, enabled = enabled) { Text("JSON") }
                if (!active) {
                    TextButton(onClick = onSelect, enabled = enabled) { Text("Выбрать") }
                }
                TextButton(onClick = onRename, enabled = enabled) { Text("Имя") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (refreshable) {
                    TextButton(onClick = onRefresh, enabled = enabled) { Text("Обновить") }
                }
                TextButton(onClick = onDelete, enabled = enabled) { Text("Удалить") }
            }
        }
    }
}

private data class ProfileGroup(
    val title: String,
    val profiles: List<ProfileMetadata>,
)

private fun profileGroups(profiles: List<ProfileMetadata>): List<ProfileGroup> {
    val subscriptions = profiles.filter { it.source in setOf(ProfileSource.Url, ProfileSource.Subscription) }
    val imported = profiles.filter { it.source in setOf(ProfileSource.Clipboard, ProfileSource.Link, ProfileSource.Qr) }
    val files = profiles.filter { it.source in setOf(ProfileSource.File, ProfileSource.RawJson) }
    return listOf(
        ProfileGroup("Подписки", subscriptions),
        ProfileGroup("Импортированные", imported),
        ProfileGroup("Файлы и JSON", files),
    ).filter { it.profiles.isNotEmpty() }
}

private fun ProfileSource.displayName(): String = when (this) {
    ProfileSource.RawJson -> "JSON"
    ProfileSource.File -> "Системный файл"
    ProfileSource.Clipboard -> "Буфер обмена"
    ProfileSource.Link -> "Ссылка"
    ProfileSource.Qr -> "QR-код"
    ProfileSource.Url -> "URL-подписка"
    ProfileSource.Subscription -> "Подписка"
}

@Composable
private fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Название") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawEditorScreen(
    viewModel: ProfilesViewModel,
    editor: ProfileEditorState,
    lineWrap: Boolean,
    busy: Boolean,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestClose = {
        if (!viewModel.closeEditor()) confirmDiscard = true
    }
    BackHandler(onBack = requestClose)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(editor.profileName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = editor.search,
                onValueChange = viewModel::updateSearch,
                label = { Text("Поиск") },
                supportingText = {
                    if (editor.search.isNotBlank()) Text("Совпадений: ${editor.searchMatches}")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SelectorControls(editor, viewModel)

            val horizontalScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (lineWrap) Modifier else Modifier.horizontalScroll(horizontalScroll)),
            ) {
                OutlinedTextField(
                    value = editor.text,
                    onValueChange = viewModel::updateEditorText,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("sing-box JSON") },
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (lineWrap) Modifier else Modifier.widthIn(min = 1200.dp)),
                )
            }

            editor.validationMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (editor.validationSuccessful) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = viewModel::formatEditor, enabled = !busy) { Text("Format") }
                OutlinedButton(onClick = viewModel::validateEditor, enabled = !busy) { Text("Validate") }
                if (editor.hasBackup) {
                    OutlinedButton(onClick = viewModel::restoreBackup, enabled = !busy) {
                        Text("Restore backup")
                    }
                }
                Button(
                    onClick = viewModel::saveEditor,
                    enabled = editor.hasUnsavedChanges && !busy,
                ) { Text("Сохранить") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Отменить изменения?") },
            text = { Text("Несохранённые изменения JSON будут потеряны.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        viewModel.closeEditor(force = true)
                    },
                ) { Text("Отменить изменения") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Продолжить редактирование") }
            },
        )
    }
}

@Composable
private fun SelectorControls(editor: ProfileEditorState, viewModel: ProfilesViewModel) {
    if (editor.selectors.isEmpty()) {
        if (editor.serverTags.isNotEmpty()) {
            OutlinedButton(onClick = viewModel::createManagedSelector) {
                Text("Создать zapret-proxy из ${editor.serverTags.size} серверов")
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        editor.selectors.forEach { selector ->
            Text(selector.tag, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                selector.outbounds.forEach { server ->
                    FilterChip(
                        selected = selector.default == server,
                        onClick = { viewModel.selectServer(selector.tag, server) },
                        label = { Text(server) },
                    )
                }
            }
        }
        HorizontalDivider()
    }
}
