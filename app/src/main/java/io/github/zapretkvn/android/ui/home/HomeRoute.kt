package io.github.zapretkvn.android.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.diagnostics.VpnErrorContext
import io.github.zapretkvn.android.network.probes.PersistedServerLatency
import io.github.zapretkvn.android.network.probes.primaryGroup
import io.github.zapretkvn.android.profiles.ProfileServerSummary
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.AppPickerMode
import io.github.zapretkvn.android.ui.ScreenshotPrivacy
import io.github.zapretkvn.android.ui.nav.AppTab
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.core.uimodel.CountryGuess
import io.github.zapretkvn.core.uimodel.ServerName
import io.github.zapretkvn.core.uimodel.inferCountries
import io.github.zapretkvn.core.uimodel.protocolLabel
import io.github.zapretkvn.core.uimodel.protocolMonogram

@Composable
fun HomeRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val state by deps.profiles.state.collectAsStateWithLifecycle()
    val serverLatency by deps.profiles.serverLatency.collectAsStateWithLifecycle()
    val appsState by deps.apps.state.collectAsStateWithLifecycle()
    val routingState by deps.routing.state.collectAsStateWithLifecycle()
    val vpnState by deps.vpn.state.collectAsStateWithLifecycle()
    val selectorGroups by deps.vpn.selectorGroups.collectAsStateWithLifecycle()
    // Трафик тикает раз в секунду: читается только в листовых карточках через лямбду.
    val sessionStats = deps.vpn.sessionStats.collectAsStateWithLifecycle()

    val activeProfile = state.profiles.firstOrNull { it.id == state.settings.activeProfileId }
    val summary = activeProfile?.let { state.serverSummaries[it.id] }
    val latency = activeProfile?.let { serverLatency[it.id] }.orEmpty()
    val hide = state.settings.hideServerAddresses
    val server = remember(vpnState is VpnConnectionState.Connected, selectorGroups, summary, latency, hide) {
        if (vpnState is VpnConnectionState.Connected) {
            onlineServer(selectorGroups, hide)
        } else {
            offlineServer(summary, latency, hide)
        }
    }
    val input = HomeInput(
        vpnState = vpnState,
        hasProfile = activeProfile != null,
        selectedAppCount = appsState.allowedPackages.size,
        blockedAppCount = appsState.blockedPackages.size,
        scopeMode = appsState.scopeMode,
        serverTitle = server?.title,
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
        )
    }
    HomeScreen(ui = ui, stats = { sessionStats.value }, callbacks = callbacks, contentPadding = padding)
}

private fun onlineServer(groups: List<RuntimeSelectorGroup>, hide: Boolean): HomeServerUi? {
    val group = groups.primaryGroup() ?: return null
    val item = group.items.firstOrNull { it.tag == group.selected } ?: return null
    val name = ServerName.parse(ScreenshotPrivacy.serverLabel(item.tag, hide))
    val hint = inferCountries(groups.flatMap { g -> g.items.map { it.tag to it.endpoint } }.distinctBy { it.first })[item.tag]
    return HomeServerUi(
        title = name.title,
        flag = name.flag ?: hint?.let(CountryGuess::flagOf),
        protocol = protocolLabel(item.type),
        monogram = protocolMonogram(item.type),
        modeLabel = groupModeLabel(group.type),
        latency = latencyBadge(item.relay) ?: latencyBadge(item.icmp),
        relay = item.relay,
        icmp = item.icmp,
        switchable = group.items.size > 1,
    )
}

private fun offlineServer(
    summary: ProfileServerSummary?,
    latency: Map<String, PersistedServerLatency>,
    hide: Boolean,
): HomeServerUi? {
    if (summary == null || summary.serverCount == 0) return null
    val label = summary.selectedLabel ?: return null
    val option = summary.primaryGroup?.options?.firstOrNull { it.tag == label }
    val name = ServerName.parse(ScreenshotPrivacy.serverLabel(label, hide))
    val saved = latency[label]?.displayState()
    val hint = inferCountries(summary.groups.flatMap { g -> g.options.map { it.tag to it.endpoint } }.distinctBy { it.first })[label]
    return HomeServerUi(
        title = name.title,
        flag = name.flag ?: hint?.let(CountryGuess::flagOf),
        protocol = option?.type?.let(::protocolLabel) ?: "",
        monogram = protocolMonogram(option?.type.orEmpty()),
        modeLabel = if (summary.switchable) "Серверов в профиле: ${summary.serverCount}" else "Единственный сервер",
        latency = latencyBadge(saved),
        relay = null,
        icmp = null,
        switchable = summary.switchable,
    )
}

private fun ProfileServerSummary?.errorContext(): VpnErrorContext {
    if (this == null) return VpnErrorContext()
    val selected = primaryGroup?.options?.firstOrNull { it.tag == selectedLabel }
    return VpnErrorContext(
        protocol = selected?.type?.takeIf(String::isNotBlank),
        hasOtherServers = switchable,
    )
}

