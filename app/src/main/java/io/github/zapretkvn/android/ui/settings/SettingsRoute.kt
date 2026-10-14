package io.github.zapretkvn.android.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.SettingsScreen

@Composable
fun SettingsRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val state by deps.profiles.state.collectAsStateWithLifecycle()
    val vpnState by deps.vpn.state.collectAsStateWithLifecycle()
    val diagnostics by deps.vpn.diagnostics.collectAsStateWithLifecycle()
    val updateState by deps.updates.state.collectAsStateWithLifecycle()
    SettingsScreen(
        contentPadding = padding,
        state = state,
        vpnState = vpnState,
        diagnostics = diagnostics,
        viewModel = deps.profiles,
        onDiagnosticsSelected = nav.setDiagnosticsShown,
        onCreateDiagnosticShare = deps.createDiagnosticShare,
        onClearDnsCache = deps.vpn::clearDnsCache,
        updateState = updateState,
        onCheckUpdate = deps.updates::check,
        onDownloadUpdate = deps.updates::download,
        onInstallUpdate = deps.installUpdate,
        onCancelUpdate = deps.updates::cancelAndDelete,
    )
}

/** Вложенные страницы настроек; пока их ведёт сам SettingsScreen. */
@Composable
fun SettingsPageRoute(deps: AppDependencies, nav: AppNavigation, page: String) {
    LaunchedEffect(page) { nav.back() }
}
