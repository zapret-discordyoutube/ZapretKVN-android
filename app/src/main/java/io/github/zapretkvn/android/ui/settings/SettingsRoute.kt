package io.github.zapretkvn.android.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.UiSettings
import io.github.zapretkvn.core.designsystem.ZapretTopBar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Вкладка «Настройки»: главный список. Страницы открываются как отдельные маршруты. */
@Composable
fun SettingsRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    // Состояние обновления меняется при каждом байте загрузки, а списку нужна
    // только строка — перерисовываем его, когда строка действительно другая.
    val updateSummary by remember(deps.updates) {
        deps.updates.state.map { updateSummary(it, CurrentAppInfo.versionName) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = remember(deps.updates) { updateSummary(deps.updates.state.value, CurrentAppInfo.versionName) },
    )
    val ui = remember(settings, updateSummary) {
        buildSettingsMain(settings, updateSummary, CurrentAppInfo.versionName)
    }
    SettingsMainScreen(
        ui = ui,
        contentPadding = padding,
        onOpenPage = { page -> nav.openSettingsPage(page.id) },
        onThemeSelected = deps.profiles.settings::setTheme,
        onSlowServerSwitchChange = deps.profiles.settings::setSlowServerSwitch,
        onHideServerAddressesChange = deps.profiles.settings::setHideServerAddresses,
        onDynamicColorChange = deps.profiles.settings::setDynamicColor,
        onSubscriptionUpdateChange = deps.profiles.settings::setSubscriptionAutoUpdateHours,
    )
}

/**
 * Вложенная страница настроек. Показывается на весь экран без нижней
 * панели, поэтому рисует собственную шапку со стрелкой «Назад».
 */
@Composable
fun SettingsPageRoute(deps: AppDependencies, nav: AppNavigation, page: String) {
    val resolved = SettingsPage.fromId(page)
    if (resolved == null) {
        LaunchedEffect(page) { nav.back() }
        return
    }
    Scaffold(
        topBar = { ZapretTopBar(title = resolved.title, onBack = nav.back) },
    ) { padding ->
        when (resolved) {
            SettingsPage.Dns -> DnsRoute(deps, padding)
            SettingsPage.Automation -> AutomationRoute(deps, padding)
            SettingsPage.Services -> ServiceChecksRoute(deps, padding)
            SettingsPage.Hiding -> HidingRoute(deps, padding)
            SettingsPage.Diagnostics -> DiagnosticsRoute(deps, nav, padding)
            SettingsPage.Updates -> UpdatesRoute(deps, padding)
            SettingsPage.Community -> CommunityScreen(padding)
            SettingsPage.About -> AboutScreen(padding, CurrentAppInfo)
            SettingsPage.Advanced -> AdvancedRoute(deps, nav, padding)
        }
    }
}

/** Настройки как состояние: перерисовка только при смене самих настроек. */
@Composable
internal fun rememberUiSettings(deps: AppDependencies): State<UiSettings> =
    remember(deps.profiles) {
        deps.profiles.state.map { it.settings }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = remember(deps.profiles) { deps.profiles.state.value.settings })

/** Сообщает корню, что диагностика на экране: от этого зависит поток логов VPN. */
@Composable
internal fun ReportDiagnosticsShown(nav: AppNavigation) {
    DisposableEffect(nav) {
        nav.setDiagnosticsShown(true)
        onDispose { nav.setDiagnosticsShown(false) }
    }
}
