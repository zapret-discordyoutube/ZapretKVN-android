package io.github.zapretkvn.android.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.network.probes.services.ServiceCatalog
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.AppPickerMode
import io.github.zapretkvn.android.ui.nav.AppTab
import io.github.zapretkvn.android.vpn.VpnConnectionState

@Composable
fun HomeRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val state by deps.profiles.state.collectAsStateWithLifecycle()
    val serverLatency by deps.profiles.serverLatency.collectAsStateWithLifecycle()
    val hostCountries by deps.profiles.serverCountries.collectAsStateWithLifecycle()
    val appsState by deps.apps.state.collectAsStateWithLifecycle()
    val routingState by deps.routing.state.collectAsStateWithLifecycle()
    val vpnState by deps.vpn.state.collectAsStateWithLifecycle()
    val linkLost by deps.vpn.linkLost.collectAsStateWithLifecycle()
    val selectorGroups by deps.vpn.selectorGroups.collectAsStateWithLifecycle()
    // Трафик тикает раз в секунду: читается только в листовых карточках через лямбду.
    val sessionStats = deps.vpn.sessionStats.collectAsStateWithLifecycle()
    val sessionServices = deps.vpn.serviceAvailability.collectAsStateWithLifecycle()
    val directServices = deps.directServices.collectAsStateWithLifecycle()

    val activeProfile = state.profiles.firstOrNull { it.id == state.settings.activeProfileId }
    val summary = activeProfile?.let { state.serverSummaries[it.id] }
    val latency = activeProfile?.let { serverLatency[it.id] }.orEmpty()
    val hide = state.settings.hideServerAddresses
    val server = remember(vpnState is VpnConnectionState.Connected, selectorGroups, summary, latency, hide, hostCountries) {
        if (vpnState is VpnConnectionState.Connected) {
            onlineServer(
                selectorGroups,
                hide,
                hostCountries,
                connectedAtEpochMillis = (vpnState as VpnConnectionState.Connected).connectedAtEpochMillis,
            )
        } else {
            offlineServer(summary, latency, hide, hostCountries)
        }
    }
    val input = HomeInput(
        vpnState = vpnState,
        hasProfile = activeProfile != null,
        selectedAppCount = appsState.allowedPackages.size,
        blockedAppCount = appsState.blockedPackages.size,
        scopeMode = appsState.scopeMode,
        serverTitle = server?.title,
        linkLost = linkLost,
    )
    val stage = homeStage(input)
    val (primary, secondary) = homeActions(stage, appsState.scopeMode)
    val ui = HomeUi(
        vpnState = vpnState,
        stage = stage,
        headline = homeHeadline(stage, input),
        primary = primary,
        secondary = secondary,
        profileName = (vpnState as? VpnConnectionState.Connected)?.profileName ?: activeProfile?.name,
        server = server,
        preset = routingState.inspection?.preset?.takeIf { activeProfile != null },
        appsSummary = appsSummary(
            appsState.scopeMode,
            appsState.allowedPackages.size,
            appsState.blockedPackages.size,
        ),
        errorContext = summary.errorContext(),
        appVersion = BuildConfig.VERSION_NAME,
        services = ServiceCatalog.resolve(state.settings.serviceChecks).map { it.id },
        linkLost = linkLost,
    )
    val callbacks = remember(deps, nav) {
        HomeCallbacks(
            onAction = { action ->
                when (action) {
                    HomeAction.AddProfile -> nav.switchTab(AppTab.Servers)
                    HomeAction.SelectApps -> nav.openAppPicker(AppPickerMode.VpnScope)
                    HomeAction.SelectDirectApps -> nav.openAppPicker(AppPickerMode.VpnScope)
                    HomeAction.Connect -> deps.profiles.state.value.settings.activeProfileId
                        ?.let(deps.startVpn)
                    HomeAction.Stop, HomeAction.Disconnect -> deps.vpn.stop()
                    HomeAction.ResumeNow, HomeAction.Restart -> deps.restartVpn()
                    HomeAction.None -> Unit
                }
            },
            onOpenServers = { nav.switchTab(AppTab.Servers) },
            onOpenRouting = { nav.switchTab(AppTab.Routing) },
            onSelectPreset = { preset -> deps.routing.applyPreset(preset) },
            createDiagnosticShare = deps.createDiagnosticShare,
            // Остановленный VPN: проверка напрямую; подключённый: проверка сессии (через VPN и напрямую).
            onCheckServices = {
                if (deps.vpn.state.value is VpnConnectionState.Stopped) deps.checkServicesDirect()
                else deps.vpn.checkServices()
            },
            onRequestExternalIp = deps.vpn::requestExternalIp,
        )
    }
    HomeScreen(
        ui = ui,
        stats = { sessionStats.value },
        services = { if (vpnState is VpnConnectionState.Stopped) directServices.value else sessionServices.value },
        callbacks = callbacks,
        contentPadding = padding,
    )
}
