package io.github.zapretkvn.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import io.github.zapretkvn.android.importer.qrImportScanOptions
import io.github.zapretkvn.android.diagnostics.DiagnosticState
import io.github.zapretkvn.android.profiles.ImportPreviewState
import io.github.zapretkvn.android.profiles.MAX_SPLIT_PROFILES
import io.github.zapretkvn.android.profiles.ProfileEditorState
import io.github.zapretkvn.android.importer.SubscriptionClientProfile
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.SubscriptionIdentityInput
import io.github.zapretkvn.android.profiles.SubscriptionSettingsState
import io.github.zapretkvn.android.profiles.ProfileServerPickerState
import io.github.zapretkvn.android.profiles.ProfileServerSummary
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
    onVpnRestart: () -> Unit,
    onSelectOutbound: (String, String, String) -> Unit,
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
        RawEditorScreen(
            profilesViewModel,
            editor,
            state.settings.rawEditorLineWrap,
            state.settings.hideServerAddresses,
            state.busy,
        )
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
        val activeProfile = state.profiles.firstOrNull { it.id == state.settings.activeProfileId }
        when (selectedTab) {
            AppTab.Home -> HomeScreen(
                contentPadding = contentPadding,
                activeProfile = activeProfile,
                activeProfileServers = activeProfile?.let { state.serverSummaries[it.id] },
                hideServerAddresses = state.settings.hideServerAddresses,
                onOpenServers = {
                    activeProfile?.id?.let(profilesViewModel::openServerPicker)
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
                onRestart = onVpnRestart,
                onSelectOutbound = onSelectOutbound,
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

    state.serverPicker?.let { picker ->
        ModalBottomSheet(onDismissRequest = profilesViewModel::dismissServerPicker) {
            ProfileServerPickerSheet(
                picker = picker,
                hideServerAddresses = state.settings.hideServerAddresses,
                busy = state.busy,
                onSelect = profilesViewModel::selectProfileServer,
            )
        }
    }
}

@Composable
private fun ProfileServerPickerSheet(
    picker: ProfileServerPickerState,
    hideServerAddresses: Boolean,
    busy: Boolean,
    onSelect: (String, String) -> Unit,
) {
    var filter by remember(picker.profileId) { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile-servers-sheet"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "picker-header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Серверы профиля",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "${picker.profileName} · ${picker.serverCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (picker.liveSwitch) {
                        "VPN подключён: выбор применяется сразу, без перезапуска туннеля."
                    } else {
                        "Выбор сохраняется в профиле и применится при подключении."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (picker.serverCount > SERVER_FILTER_THRESHOLD) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it.take(80) },
                        label = { Text("Фильтр по имени или адресу") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile-servers-filter"),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        picker.groups.forEach { group ->
            val options = group.options.filter { option ->
                filter.isBlank() ||
                    option.tag.contains(filter, ignoreCase = true) ||
                    option.endpoint?.contains(filter, ignoreCase = true) == true
            }
            item(key = "group-${group.tag}") {
                Text(
                    "${group.tag} · ${group.options.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (options.isEmpty()) {
                item(key = "empty-${group.tag}") {
                    Text(
                        "Под фильтр не подходит ни один сервер.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(options, key = { "${group.tag}-${it.tag}" }) { option ->
                val selected = group.selected == option.tag
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { onSelect(group.tag, option.tag) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        enabled = !busy,
                        onClick = { onSelect(group.tag, option.tag) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            ScreenshotPrivacy.serverLabel(option.tag, hideServerAddresses),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (option.subtitle.isNotBlank()) {
                            Text(
                                listOfNotNull(
                                    option.type.uppercase().takeIf(String::isNotBlank),
                                    ScreenshotPrivacy.serverEndpoint(
                                        option.endpoint,
                                        hideServerAddresses,
                                    ),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            item(key = "divider-${group.tag}") { HorizontalDivider() }
        }
    }
}

private const val SERVER_FILTER_THRESHOLD = 8

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
                        hideServerAddresses = state.settings.hideServerAddresses,
                        active = profile.id == state.settings.activeProfileId,
                        enabled = !state.busy,
                        onSelect = { viewModel.selectProfile(profile.id) },
                        onOpen = { viewModel.openEditor(profile.id) },
                        onRename = { renameTarget = profile },
                        onDelete = { deleteTarget = profile },
                        onRefresh = { viewModel.refreshSubscription(profile.id) },
                        onSubscriptionSettings = { viewModel.openSubscriptionSettings(profile.id) },
                        refreshable = profile.id in state.refreshableProfileIds,
                        servers = state.serverSummaries[profile.id],
                        onOpenServers = { viewModel.openServerPicker(profile.id) },
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
            hideServerAddresses = state.settings.hideServerAddresses,
            onDismiss = { urlDialogOpen = false },
            onImport = { url, identity ->
                urlDialogOpen = false
                viewModel.importUrl(url, identity)
            },
        )
    }

    state.subscriptionSettings?.let { settings ->
        SubscriptionSettingsDialog(
            settings = settings,
            hideServerAddresses = state.settings.hideServerAddresses,
            busy = state.busy,
            onDismiss = viewModel::closeSubscriptionSettings,
            onSave = viewModel::saveSubscriptionSettings,
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
            hideServerAddresses = state.settings.hideServerAddresses,
            busy = state.busy,
            onDismiss = viewModel::dismissImportPreview,
            onCreate = viewModel::confirmImport,
            onSplit = viewModel::confirmImportPerServer,
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
    hideServerAddresses: Boolean,
    onDismiss: () -> Unit,
    onImport: (String, SubscriptionIdentityInput) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var revealed by rememberSaveable { mutableStateOf(false) }
    // null — профиль ещё не выбран: его подскажет сама ссылка (happ://add и подобные).
    var clientProfile by rememberSaveable { mutableStateOf<SubscriptionClientProfile?>(null) }
    var sendHwid by rememberSaveable { mutableStateOf(false) }
    var hwid by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт по URL") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.take(4096) },
                    label = { Text("HTTPS URL подписки") },
                    singleLine = true,
                    visualTransformation = if (hideServerAddresses && !revealed) {
                        PasswordVisualTransformation(mask = '*')
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hideServerAddresses) {
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(if (revealed) "Скрыть URL" else "Показать URL")
                    }
                }
                Text(
                    "Открытые add/import-ссылки Happ, INCY и v2RayTun разворачиваются " +
                        "в адрес подписки автоматически.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SubscriptionIdentityFields(
                    clientProfile = clientProfile,
                    onClientProfile = { clientProfile = it },
                    sendHwid = sendHwid,
                    onSendHwid = { sendHwid = it },
                    hwid = hwid,
                    onHwid = { hwid = it },
                    hideSecrets = hideServerAddresses,
                    autoOption = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onImport(
                        url,
                        SubscriptionIdentityInput(clientProfile, sendHwid, hwid.trim()),
                    )
                },
                enabled = url.isNotBlank(),
            ) {
                Text("Загрузить preview")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SubscriptionSettingsDialog(
    settings: SubscriptionSettingsState,
    hideServerAddresses: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (SubscriptionIdentityInput) -> Unit,
) {
    var clientProfile by remember(settings) {
        mutableStateOf<SubscriptionClientProfile?>(settings.clientProfile)
    }
    var sendHwid by remember(settings) { mutableStateOf(settings.sendHwid) }
    var hwid by remember(settings) { mutableStateOf(settings.hwid) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подписка: ${settings.profileName}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    ScreenshotPrivacy.subscriptionSource(settings.url, hideServerAddresses),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SubscriptionIdentityFields(
                    clientProfile = clientProfile,
                    onClientProfile = { clientProfile = it },
                    sendHwid = sendHwid,
                    onSendHwid = { sendHwid = it },
                    hwid = hwid,
                    onHwid = { hwid = it },
                    hideSecrets = hideServerAddresses,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(SubscriptionIdentityInput(clientProfile, sendHwid, hwid.trim()))
                },
                enabled = !busy,
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Панели с лимитом устройств узнают клиента по User-Agent и `X-HWID`. Пустое поле
 * HWID означает постоянный идентификатор этой установки, а не отсутствие заголовка.
 */
@Composable
private fun SubscriptionIdentityFields(
    clientProfile: SubscriptionClientProfile?,
    onClientProfile: (SubscriptionClientProfile?) -> Unit,
    sendHwid: Boolean,
    onSendHwid: (Boolean) -> Unit,
    hwid: String,
    onHwid: (String) -> Unit,
    hideSecrets: Boolean,
    autoOption: Boolean = false,
) {
    var hwidRevealed by rememberSaveable { mutableStateOf(false) }
    Text(
        "Профиль клиента",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (autoOption) {
            FilterChip(
                selected = clientProfile == null,
                onClick = { onClientProfile(null) },
                label = { Text("Из ссылки") },
            )
        }
        SubscriptionClientProfile.entries.forEach { profile ->
            FilterChip(
                selected = profile == clientProfile,
                onClick = { onClientProfile(profile) },
                label = { Text(profile.displayName()) },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Передавать стабильный HWID", modifier = Modifier.weight(1f))
        Switch(checked = sendHwid, onCheckedChange = onSendHwid)
    }
    if (sendHwid) {
        OutlinedTextField(
            value = hwid,
            onValueChange = { onHwid(it.take(128)) },
            label = { Text("HWID") },
            placeholder = { Text("Пусто — постоянный ID этой установки") },
            singleLine = true,
            visualTransformation = if (hideSecrets && !hwidRevealed) {
                PasswordVisualTransformation(mask = '*')
            } else {
                VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (hideSecrets) {
            TextButton(onClick = { hwidRevealed = !hwidRevealed }) {
                Text(if (hwidRevealed) "Скрыть HWID" else "Показать HWID")
            }
        }
    }
}

private fun SubscriptionClientProfile.displayName(): String = when (this) {
    SubscriptionClientProfile.Zapret -> "Zapret KVN"
    SubscriptionClientProfile.Happ -> "Happ"
    SubscriptionClientProfile.Incy -> "INCY"
    SubscriptionClientProfile.V2RayTun -> "v2RayTun"
    SubscriptionClientProfile.Custom -> "Другой"
}

@Composable
private fun ImportPreviewDialog(
    preview: ImportPreviewState,
    hideServerAddresses: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onSplit: (String) -> Unit,
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
                Text(
                    if (preview.hasSubscriptionUrl) {
                        ScreenshotPrivacy.subscriptionSource(
                            preview.sourceDescription,
                            hideServerAddresses,
                        )
                    } else {
                        preview.sourceDescription
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (preview.serverCount > 0) "Серверов: ${preview.serverCount}" else "Готовый sing-box JSON",
                )
                if (preview.serverLabels.isNotEmpty()) {
                    Text(
                        if (hideServerAddresses) {
                            preview.serverLabels.joinToString(" • ") {
                                ScreenshotPrivacy.serverLabel(it, hidden = true)
                            }
                        } else {
                            preview.serverLabels.joinToString(" • ")
                        },
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
                preview.importWarnings.forEach {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (preview.selectionChanged) {
                    Text(
                        "Текущий server tag исчез: будет выбран первый доступный сервер.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                preview.splitRefreshSummary?.let { summary ->
                    Text(
                        "Раздельные профили: ${summary.updated} обновится, " +
                            "${summary.added} добавится, ${summary.removed} удалится.",
                        color = if (summary.removed > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (summary.connectedProfileRemoved) {
                        Text(
                            "Подключённый сервер удалён из подписки: после обновления VPN будет отключён.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (preview.activeRefresh) {
                    Text(
                        "Профиль сейчас подключён. Перезапуск возможен только отдельным подтверждением ниже.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (!preview.isRefresh && preview.splittableServerCount > 1) {
                    HorizontalDivider()
                    Text(
                        if (preview.splitSupported) {
                            "Один профиль-группа даёт переключение сервера прямо в приложении. " +
                                "Отдельные профили удобны, когда у серверов разные маршруты и настройки" +
                                if (preview.hasSubscriptionUrl) {
                                    "; они сохранят индивидуальные маршруты и будут синхронизироваться " +
                                        "вместе по ссылке подписки."
                                } else {
                                    "."
                                }
                        } else {
                            "Серверов больше $MAX_SPLIT_PROFILES: разложить их по отдельным " +
                                "профилям нельзя. Сохраните профиль-группу и переключайте сервер " +
                                "в приложении."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            Text(
                                if (preview.splitRefreshSummary?.connectedProfileRemoved == true) {
                                    "Обновить и отключить"
                                } else {
                                    "Сохранить и переподключить"
                                },
                            )
                        }
                    }
                    if (preview.splitRefreshSummary?.connectedProfileRemoved != true) {
                        TextButton(onClick = { onRefresh(false) }, enabled = !busy) {
                            Text(if (preview.activeRefresh) "Сохранить без перезапуска" else "Обновить")
                        }
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank() && !busy) {
                        Text(
                            if (preview.splittableServerCount > 1) {
                                "Один профиль-группа"
                            } else {
                                "Новый профиль"
                            },
                        )
                    }
                    if (preview.splitSupported) {
                        TextButton(
                            onClick = { onSplit(name) },
                            enabled = name.isNotBlank() && !busy,
                            modifier = Modifier.testTag("import-split-profiles"),
                        ) {
                            Text("Отдельные профили: ${preview.splittableServerCount}")
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}

@Composable
private fun ProfileCard(
    profile: ProfileMetadata,
    hideServerAddresses: Boolean,
    active: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    onSubscriptionSettings: () -> Unit,
    refreshable: Boolean,
    servers: ProfileServerSummary?,
    onOpenServers: () -> Unit,
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
            if (servers != null && servers.serverCount > 0) {
                Text(
                    buildString {
                        append("Серверов: ${servers.serverCount}")
                        servers.selectedLabel?.let {
                            append(" · выбран: ")
                            append(
                                ScreenshotPrivacy.serverLabel(
                                    it,
                                    hideServerAddresses,
                                ),
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (servers?.switchable == true) {
                    TextButton(onClick = onOpenServers, enabled = enabled) { Text("Серверы") }
                }
                TextButton(onClick = onOpen, enabled = enabled) { Text("JSON") }
                if (!active) {
                    TextButton(onClick = onSelect, enabled = enabled) { Text("Выбрать") }
                }
                TextButton(onClick = onRename, enabled = enabled) { Text("Имя") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (refreshable) {
                    TextButton(onClick = onRefresh, enabled = enabled) { Text("Обновить") }
                    TextButton(onClick = onSubscriptionSettings, enabled = enabled) {
                        Text("Подписка")
                    }
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
    hideServerAddresses: Boolean,
    busy: Boolean,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    // Sensitive endpoints must be concealed again after Activity recreation.
    var addressesRevealed by remember(editor.profileId, hideServerAddresses) {
        mutableStateOf(!hideServerAddresses)
    }
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

            SelectorControls(editor, viewModel, hideServerAddresses)

            if (hideServerAddresses) {
                OutlinedButton(onClick = { addressesRevealed = !addressesRevealed }) {
                    Text(
                        if (addressesRevealed) {
                            "Скрыть адреса серверов"
                        } else {
                            "Показать адреса и разрешить редактирование"
                        },
                    )
                }
            }

            val horizontalScroll = rememberScrollState()
            val editorTextVisible = !hideServerAddresses || addressesRevealed
            val displayedText = remember(editor.text, editorTextVisible) {
                if (editorTextVisible) {
                    editor.text
                } else {
                    ScreenshotPrivacy.redactServerAddressesInJson(editor.text)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (lineWrap) Modifier else Modifier.horizontalScroll(horizontalScroll)),
            ) {
                OutlinedTextField(
                    value = displayedText,
                    onValueChange = if (editorTextVisible) viewModel::updateEditorText else ({ _ -> }),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = {
                        Text(if (editorTextVisible) "sing-box JSON" else "sing-box JSON · адреса скрыты")
                    },
                    readOnly = !editorTextVisible,
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
private fun SelectorControls(
    editor: ProfileEditorState,
    viewModel: ProfilesViewModel,
    hideServerAddresses: Boolean,
) {
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
                        label = {
                            Text(ScreenshotPrivacy.serverLabel(server, hideServerAddresses))
                        },
                    )
                }
            }
        }
        HorizontalDivider()
    }
}
