package io.github.zapretkvn.android.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.apps.AppsViewModel
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.android.routing.RoutingViewModel
import io.github.zapretkvn.android.ui.nav.AppTab
import io.github.zapretkvn.android.ui.nav.Navigator
import io.github.zapretkvn.android.ui.nav.Route
import io.github.zapretkvn.android.ui.nav.rememberNavigator
import io.github.zapretkvn.android.ui.routing.RoutingRoute
import io.github.zapretkvn.android.ui.servers.ImportPreviewDialog
import io.github.zapretkvn.android.ui.servers.ProfilesRoute
import io.github.zapretkvn.android.ui.servers.ServersRoute
import io.github.zapretkvn.android.ui.settings.SettingsPageRoute
import io.github.zapretkvn.android.ui.settings.SettingsRoute
import io.github.zapretkvn.android.ui.update.HomeWithUpdateBanner
import io.github.zapretkvn.android.updates.UpdateController
import io.github.zapretkvn.android.vpn.VpnController
import io.github.zapretkvn.core.designsystem.Motion
import io.github.zapretkvn.core.designsystem.ZapretTopBar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Всё, что нужно интерфейсу от Activity: ViewModel, контроллеры и действия,
 * которые требуют системных разрешений (запуск VPN, установка обновления).
 */
class AppDependencies(
    val profiles: ProfilesViewModel,
    val apps: AppsViewModel,
    val routing: RoutingViewModel,
    val vpn: VpnController,
    val updates: UpdateController,
    val startVpn: (profileId: String) -> Unit,
    val restartVpn: () -> Unit,
    val installUpdate: () -> Unit,
    val createDiagnosticShare: suspend () -> Intent,
)

/** Действия навигации, общие для экранов. */
class AppNavigation(
    val navigator: Navigator,
    val openAppPicker: (AppPickerMode) -> Unit,
    val openProfiles: () -> Unit,
    val openSettingsPage: (String) -> Unit,
    val switchTab: (AppTab) -> Unit,
    val back: () -> Unit,
    /** Экран диагностики сообщает о своей видимости — от неё зависит поток логов. */
    val setDiagnosticsShown: (Boolean) -> Unit,
)

/**
 * Корень интерфейса. Сам почти ничего не читает: каждая вкладка и каждый
 * «хост» (сообщения, обновление, импорт) собирают свои потоки сами, поэтому
 * частые события VPN перерисовывают только свой участок экрана.
 */
@Composable
fun ZapretRoot(deps: AppDependencies) {
    val navigator = rememberNavigator()
    val snackbarHostState = remember { SnackbarHostState() }
    var diagnosticsShown by rememberSaveable { mutableStateOf(false) }
    val editorOpen by remember(deps.profiles) {
        deps.profiles.state.map { it.editor != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val nav = remember(navigator) {
        AppNavigation(
            navigator = navigator,
            openAppPicker = { mode ->
                deps.apps.refresh()
                navigator.push(Route.AppPicker(mode))
            },
            openProfiles = { navigator.push(Route.Profiles) },
            openSettingsPage = { page -> navigator.push(Route.SettingsPage(page)) },
            switchTab = navigator::switchTab,
            back = { navigator.back() },
            setDiagnosticsShown = { diagnosticsShown = it },
        )
    }

    // Поток трафика и IP нужен только видимой Главной, поток логов — видимой
    // диагностике. Остановка Activity гасит оба.
    val homeVisible = !editorOpen && navigator.top == Route.Tab(AppTab.Home)
    LifecycleStartEffect(homeVisible) {
        deps.vpn.setHomeVisible(homeVisible)
        onStopOrDispose { deps.vpn.setHomeVisible(false) }
    }
    val diagnosticsVisible = !editorOpen && diagnosticsShown
    LifecycleStartEffect(diagnosticsVisible) {
        deps.vpn.setDiagnosticsVisible(diagnosticsVisible)
        onStopOrDispose { deps.vpn.setDiagnosticsVisible(false) }
    }

    MessagesHost(deps, snackbarHostState)

    Box(Modifier.fillMaxSize()) {
        if (editorOpen) {
            EditorRoute(deps, snackbarHostState)
        } else {
            BackHandler(enabled = navigator.canGoBack) { navigator.back() }
            AnimatedContent(
                targetState = navigator.top,
                transitionSpec = {
                    fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(Motion.QUICK))
                },
                contentKey = { route -> if (route is Route.Tab) "tabs" else Navigator.encode(route) },
                label = "route",
            ) { route ->
                when (route) {
                    is Route.Tab -> TabsScaffold(navigator, snackbarHostState) { tab, padding ->
                        when (tab) {
                            AppTab.Home -> HomeWithUpdateBanner(deps, nav, padding)
                            AppTab.Servers -> ServersRoute(deps, nav, padding)
                            AppTab.Routing -> RoutingRoute(deps, nav, padding)
                            AppTab.Settings -> SettingsRoute(deps, nav, padding)
                        }
                    }
                    Route.Profiles -> ProfilesRoute(deps, nav)
                    is Route.AppPicker -> AppPickerRoute(deps, route.mode, onBack = nav.back)
                    is Route.SettingsPage -> SettingsPageRoute(deps, nav, route.page)
                }
            }
        }
        // Вкладки показывают сообщения над нижней панелью; вложенным экранам
        // нужен свой хост у нижнего края, иначе сообщение увидят только после возврата.
        if (!editorOpen && navigator.top !is Route.Tab) {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }
        ImportHost(deps, nav)
    }
}

@Composable
private fun TabsScaffold(
    navigator: Navigator,
    snackbarHostState: SnackbarHostState,
    content: @Composable (AppTab, PaddingValues) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = navigator.tab == tab,
                        onClick = { navigator.switchTab(tab) },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = navigator.tab,
                transitionSpec = {
                    fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(Motion.QUICK))
                },
                label = "tab",
            ) { tab ->
                // «Серверы» рисуют шапку сами: в ней поиск и сортировка.
                if (tab == AppTab.Servers) {
                    Box(Modifier.fillMaxSize()) { content(tab, padding) }
                } else {
                    TitledTab(tab.title, padding) { inner -> content(tab, inner) }
                }
            }
        }
    }
}

/** Одинаковая шапка вкладок: заголовок сверху, отступ строки состояния уже учтён в ней. */
@Composable
private fun TitledTab(
    title: String,
    padding: PaddingValues,
    content: @Composable (PaddingValues) -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val top = PaddingValues(top = padding.calculateTopPadding())
    Column(
        Modifier
            .fillMaxSize()
            .padding(top)
            .consumeWindowInsets(top),
    ) {
        ZapretTopBar(title = title)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(
                PaddingValues(
                    start = padding.calculateStartPadding(direction),
                    end = padding.calculateEndPadding(direction),
                    bottom = padding.calculateBottomPadding(),
                ),
            )
        }
    }
}

/** Одна очередь сообщений вместо трёх: профили, VPN и маршруты. */
@Composable
private fun MessagesHost(deps: AppDependencies, snackbarHostState: SnackbarHostState) {
    LaunchedEffect(deps.profiles) {
        deps.profiles.state.map { it.message }.distinctUntilChanged().filterNotNull().collect {
            deps.profiles.consumeMessage()
            snackbarHostState.showSnackbar(it)
        }
    }
    LaunchedEffect(deps.vpn) {
        deps.vpn.message.filterNotNull().collect {
            deps.vpn.consumeMessage()
            snackbarHostState.showSnackbar(it)
        }
    }
    LaunchedEffect(deps.routing) {
        deps.routing.state.map { it.message }.distinctUntilChanged().filterNotNull().collect {
            deps.routing.consumeMessage()
            snackbarHostState.showSnackbar(it)
        }
    }
}

/**
 * Предпросмотр импорта и итог импорта живут на корне: файл, открытый из
 * другого приложения, виден сразу, а не только на экране профилей.
 */
@Composable
private fun ImportHost(deps: AppDependencies, nav: AppNavigation) {
    val state by deps.profiles.state.collectAsStateWithLifecycle()
    val appsState by deps.apps.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.importCompletion, appsState.initialized, appsState.needsAppSelection) {
        if (state.importCompletion != null && appsState.initialized && !appsState.needsAppSelection) {
            deps.profiles.consumeImportCompletion()
        }
    }
    state.importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            hideServerAddresses = state.settings.hideServerAddresses,
            busy = state.busy,
            onDismiss = deps.profiles::dismissImportPreview,
            onCreate = deps.profiles::confirmImport,
            onSplit = deps.profiles::confirmImportPerServer,
            onAppend = deps.profiles::confirmAppend,
            onRefresh = deps.profiles::confirmRefresh,
        )
    }
    state.importCompletion?.takeIf { appsState.needsAppSelection }?.let { completion ->
        AlertDialog(
            onDismissRequest = deps.profiles::consumeImportCompletion,
            title = { Text("Профиль готов") },
            text = {
                Text(
                    "«${completion.profileName}» сохранён. Осталось выбрать приложения, " +
                        "которые пойдут через VPN, — затем подключитесь на Главной.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deps.profiles.consumeImportCompletion()
                        nav.openAppPicker(AppPickerMode.VpnScope)
                    },
                ) { Text("Выбрать приложения") }
            },
            dismissButton = {
                TextButton(onClick = deps.profiles::consumeImportCompletion) { Text("Позже") }
            },
        )
    }
}

@Composable
private fun EditorRoute(deps: AppDependencies, snackbarHostState: SnackbarHostState) {
    val state by deps.profiles.state.collectAsStateWithLifecycle()
    val editor = state.editor ?: return
    RawEditorScreen(
        deps.profiles,
        editor,
        state.settings.rawEditorLineWrap,
        state.settings.hideServerAddresses,
        state.busy,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    )
}

@Composable
private fun AppPickerRoute(deps: AppDependencies, mode: AppPickerMode, onBack: () -> Unit) {
    val appsState by deps.apps.state.collectAsStateWithLifecycle()
    AppPickerScreen(state = appsState, viewModel = deps.apps, mode = mode, onBack = onBack)
}
