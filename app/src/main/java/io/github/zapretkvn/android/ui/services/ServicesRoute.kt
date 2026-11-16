package io.github.zapretkvn.android.ui.services

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.network.probes.primaryGroup
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.core.uimodel.ScreenshotPrivacy
import io.github.zapretkvn.core.uimodel.ServerName

/** Адаптер вкладки «Сервисы»: берёт состояние у приложения и отдаёт его чистому построителю экрана. */
@Composable
fun ServicesRoute(deps: AppDependencies, padding: PaddingValues) {
    val state by deps.profiles.state.collectAsStateWithLifecycle()
    val vpnState by deps.vpn.state.collectAsStateWithLifecycle()
    val slotStates by deps.vpn.slotStates.collectAsStateWithLifecycle()
    val groups by deps.vpn.selectorGroups.collectAsStateWithLifecycle()

    val connected = vpnState is VpnConnectionState.Connected
    val hide = state.hideActiveAddresses
    val mainTag = if (connected) groups.primaryGroup()?.selected else null
    val ui = remember(
        state.settings.serviceSlots, connected, slotStates, state.profiles, state.serverSummaries,
        state.settings.activeProfileId, state.settings.slotPins, hide, mainTag,
    ) {
        buildServicesUi(
            enabled = state.settings.serviceSlots,
            connected = connected,
            slots = slotStates,
            profiles = state.profiles,
            summaries = state.serverSummaries,
            activeProfileId = state.settings.activeProfileId,
            pins = state.settings.slotPins,
            hide = hide,
            mainServerTitle = mainTag?.let { tag ->
                val masked = ScreenshotPrivacy.serverLabel(tag, hide)
                if (masked != tag) masked else ServerName.parse(tag).title
            },
        )
    }
    ServicesScreen(
        ui = ui,
        contentPadding = padding,
        onPin = deps.profiles.settings::setSlotPin,
        onEnabledChange = deps.profiles.settings::setServiceSlots,
    )
}
