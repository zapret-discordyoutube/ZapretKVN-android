package io.github.zapretkvn.android.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.AppPickerMode
import io.github.zapretkvn.android.ui.HomeScreen
import io.github.zapretkvn.android.ui.ProfileServerPickerSheet
import io.github.zapretkvn.android.ui.nav.AppTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val state by deps.profiles.state.collectAsStateWithLifecycle()
    val serverLatency by deps.profiles.serverLatency.collectAsStateWithLifecycle()
    val appsState by deps.apps.state.collectAsStateWithLifecycle()
    val vpnState by deps.vpn.state.collectAsStateWithLifecycle()
    val selectorGroups by deps.vpn.selectorGroups.collectAsStateWithLifecycle()
    val sessionStats by deps.vpn.sessionStats.collectAsStateWithLifecycle()
    val activeProfile = state.profiles.firstOrNull { it.id == state.settings.activeProfileId }
    HomeScreen(
        contentPadding = padding,
        activeProfile = activeProfile,
        activeProfileServers = activeProfile?.let { state.serverSummaries[it.id] },
        hideServerAddresses = state.settings.hideServerAddresses,
        onOpenServers = { activeProfile?.id?.let(deps.profiles::openServerPicker) },
        selectedAppCount = appsState.allowedPackages.size,
        blockedAppCount = appsState.blockedPackages.size,
        appScopeMode = appsState.scopeMode,
        onAddProfile = { nav.switchTab(AppTab.Servers) },
        onSelectApps = { nav.openAppPicker(AppPickerMode.VpnScope) },
        vpnState = vpnState,
        selectorGroups = selectorGroups,
        sessionStats = sessionStats,
        onStart = deps.startVpn,
        onStop = deps.vpn::stop,
        onRestart = deps.restartVpn,
        onSelectOutbound = deps.vpn::selectOutbound,
        onMeasureGroup = deps.vpn::measureGroup,
        onCreateDiagnosticShare = deps.createDiagnosticShare,
        activeProfileLatency = activeProfile?.let { serverLatency[it.id] }.orEmpty(),
    )
    state.serverPicker?.let { picker ->
        ModalBottomSheet(onDismissRequest = deps.profiles::dismissServerPicker) {
            ProfileServerPickerSheet(
                picker = picker,
                latency = serverLatency[picker.profileId].orEmpty(),
                hideServerAddresses = state.settings.hideServerAddresses,
                busy = state.busy,
                onSelect = deps.profiles::selectProfileServer,
            )
        }
    }
}
