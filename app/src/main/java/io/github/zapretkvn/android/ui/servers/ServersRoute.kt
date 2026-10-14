package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.nav.AppTab
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.core.designsystem.SettingsRow
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.ZapretTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/** Задержки приходят пачками; список обновляем не чаще 4 раз в секунду. */
private const val UPDATE_PERIOD_MILLIS = 250L

private fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> =
    conflate().transform { value ->
        emit(value)
        delay(periodMillis)
    }

/** Выбор сервера, ждущий открытия «сервер-пикера» профиля (офлайн-выбор). */
private data class PendingSelect(val profileId: String, val groupTag: String, val serverTag: String)

@Composable
fun ServersRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val profiles = deps.profiles
    val state by profiles.state.collectAsStateWithLifecycle()
    val savedLatency by profiles.serverLatency.collectAsStateWithLifecycle()
    val hostCountries by profiles.serverCountries.collectAsStateWithLifecycle()
    val vpnState by deps.vpn.state.collectAsStateWithLifecycle()
    val initialGroups = remember(deps.vpn) { deps.vpn.selectorGroups.value }
    val runtimeFlow = remember(deps.vpn) { deps.vpn.selectorGroups.throttleLatest(UPDATE_PERIOD_MILLIS) }
    val runtimeGroups by runtimeFlow.collectAsStateWithLifecycle(initialValue = initialGroups)

    val activeId = state.settings.activeProfileId
    val live = (vpnState as? VpnConnectionState.Connected)?.profileId == activeId && activeId != null
    val groupsForUi: List<RuntimeSelectorGroup> = if (live) runtimeGroups else emptyList()
    val ui = remember(
        state.profiles, activeId, state.serverSummaries, savedLatency,
        groupsForUi, live, state.settings.hideServerAddresses, hostCountries,
    ) {
        buildServersUi(
            profiles = state.profiles,
            activeProfileId = activeId,
            summaries = state.serverSummaries,
            savedLatency = savedLatency,
            runtimeGroups = groupsForUi,
            live = live,
            hideAddresses = state.settings.hideServerAddresses,
            nowEpochMillis = System.currentTimeMillis(),
            hostCountries = hostCountries,
        )
    }

    // Офлайн-выбор в ProfilesViewModel работает через «пикер» профиля: сначала он
    // открывается, потом выбирается сервер, потом пикер закрывается. Так шторка
    // Главной не вспыхивает чужим состоянием.
    val latestState by rememberUpdatedState(state)
    val latestLive by rememberUpdatedState(live)
    var pending by remember { mutableStateOf<PendingSelect?>(null) }
    var ownsPicker by remember { mutableStateOf(false) }

    val select: (String, String) -> Unit = remember(deps) {
        { groupTag, serverTag ->
            val current = latestState
            val profileId = current.settings.activeProfileId
            if (profileId != null && !current.busy) {
                if (latestLive) {
                    deps.vpn.selectOutbound(profileId, groupTag, serverTag)
                } else if (current.serverPicker?.profileId == profileId) {
                    deps.profiles.selectProfileServer(groupTag, serverTag)
                } else {
                    pending = PendingSelect(profileId, groupTag, serverTag)
                    ownsPicker = true
                    deps.profiles.openServerPicker(profileId)
                }
            }
        }
    }
    LaunchedEffect(pending, state.busy, state.serverPicker?.profileId) {
        val request = pending ?: return@LaunchedEffect
        if (state.serverPicker?.profileId == request.profileId) {
            pending = null
            deps.profiles.selectProfileServer(request.groupTag, request.serverTag)
        } else if (!state.busy && state.serverPicker == null && ownsPicker) {
            // Открытие не удалось (сообщение уже показано): выбор не ждём.
            pending = null
            ownsPicker = false
        }
    }
    LaunchedEffect(pending, state.busy, state.serverPicker) {
        if (ownsPicker && pending == null && !state.busy && state.serverPicker != null) {
            deps.profiles.dismissServerPicker()
            ownsPicker = false
        }
    }
    DisposableEffect(deps) {
        onDispose { if (ownsPicker) deps.profiles.dismissServerPicker() }
    }

    val importer = rememberProfileImporter(
        viewModel = deps.profiles,
        qrSubscriptionUrl = state.qrSubscriptionUrl,
        hideServerAddresses = state.settings.hideServerAddresses,
    )
    val actions = remember(deps, nav) {
        ServersScreenActions(
            onSelect = select,
            onMeasure = deps.vpn::measureGroup,
            onOpenProfiles = nav.openProfiles,
            onOpenEditor = deps.profiles::openEditor,
        )
    }
    ServersScreen(
        ui = ui,
        busy = state.busy,
        importer = importer,
        actions = actions,
        contentPadding = padding,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesRoute(deps: AppDependencies, nav: AppNavigation) {
    val state by deps.profiles.state.collectAsStateWithLifecycle()
    val importer = rememberProfileImporter(
        viewModel = deps.profiles,
        qrSubscriptionUrl = state.qrSubscriptionUrl,
        hideServerAddresses = state.settings.hideServerAddresses,
    )
    var deleteTarget by remember { mutableStateOf<ProfileCardUi?>(null) }
    var renameTarget by remember { mutableStateOf<ProfileCardUi?>(null) }
    var addSheetOpen by remember { mutableStateOf(false) }

    val activeId = state.settings.activeProfileId
    val hostCountries by deps.profiles.serverCountries.collectAsStateWithLifecycle()
    val sections = remember(
        state.profiles, state.serverSummaries, activeId, state.refreshableProfileIds, hostCountries,
    ) {
        profileSections(
            profiles = state.profiles,
            summaries = state.serverSummaries,
            activeProfileId = activeId,
            refreshableIds = state.refreshableProfileIds,
            nowEpochMillis = System.currentTimeMillis(),
            hostCountries = hostCountries,
        )
    }
    val latestActive by rememberUpdatedState(activeId)
    val actions = remember(deps, nav) {
        ProfileActions(
            onSelect = { id -> if (id != latestActive) deps.profiles.selectProfile(id) },
            onServers = { id ->
                if (id != latestActive) deps.profiles.selectProfile(id)
                nav.switchTab(AppTab.Servers)
            },
            onRefresh = deps.profiles::refreshSubscription,
            onRename = { renameTarget = it },
            onSubscriptionSettings = deps.profiles::openSubscriptionSettings,
            onRawJson = deps.profiles::openEditor,
            onDelete = { deleteTarget = it },
        )
    }

    Scaffold(
        topBar = { ZapretTopBar(title = "Профили", onBack = nav.back) },
        floatingActionButton = {
            if (sections.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { addSheetOpen = true },
                    icon = { Icon(ZapretIcons.Add, contentDescription = null) },
                    text = { Text("Добавить") },
                )
            }
        },
    ) { padding ->
        ProfilesScreen(
            sections = sections,
            busy = state.busy,
            importer = importer,
            actions = actions,
            contentPadding = padding,
        )
    }

    if (addSheetOpen) {
        ModalBottomSheet(onDismissRequest = { addSheetOpen = false }) {
            AddSourceSheet(
                enabled = !state.busy,
                importer = importer,
                onChosen = { addSheetOpen = false },
            )
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
                        deps.profiles.deleteProfile(profile.id)
                        deleteTarget = null
                    },
                ) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } },
        )
    }
    renameTarget?.let { profile ->
        RenameDialog(
            initialName = profile.name,
            onDismiss = { renameTarget = null },
            onRename = { name ->
                deps.profiles.renameProfile(profile.id, name)
                renameTarget = null
            },
        )
    }
    state.subscriptionSettings?.let { settings ->
        SubscriptionSettingsDialog(
            settings = settings,
            hideServerAddresses = state.settings.hideServerAddresses,
            busy = state.busy,
            onDismiss = deps.profiles::closeSubscriptionSettings,
            onSave = deps.profiles::saveSubscriptionSettings,
        )
    }
}

/** Шторка «Откуда добавить?»: четыре источника. */
@Composable
private fun AddSourceSheet(enabled: Boolean, importer: ProfileImporter, onChosen: () -> Unit) {
    Column(modifier = Modifier.navigationBarsPadding().padding(bottom = Spacing.l)) {
        Text(
            "Откуда добавить?",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
        SourceRow("Файл", "JSON или WireGuard/AWG .conf", enabled, onChosen, importer.pickFile)
        SourceRow(
            "Буфер", "Ссылка, подписка или конфигурация из буфера обмена", enabled, onChosen,
            importer.pasteClipboard,
        )
        SourceRow("Ссылка", "Адрес подписки по HTTPS", enabled, onChosen, importer.askUrl)
        SourceRow("QR", "Камера или картинка из галереи", enabled, onChosen, importer.askQr)
    }
}

@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onChosen: () -> Unit,
    action: () -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = if (enabled) {
            {
                onChosen()
                action()
            }
        } else {
            null
        },
    )
}
