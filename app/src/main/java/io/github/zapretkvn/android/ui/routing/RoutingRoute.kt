package io.github.zapretkvn.android.ui.routing

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.AppPickerMode
import io.github.zapretkvn.android.ui.nav.AppTab

@Composable
fun RoutingRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val appsState by deps.apps.state.collectAsStateWithLifecycle()
    val routingState by deps.routing.state.collectAsStateWithLifecycle()
    RoutingScreen(
        contentPadding = padding,
        appsState = appsState,
        routingState = routingState,
        onScopeModeChange = deps.apps::setScopeMode,
        onOpenPicker = { nav.openAppPicker(AppPickerMode.VpnScope) },
        onOpenBlockPicker = { nav.openAppPicker(AppPickerMode.Blocked) },
        onApplyPreset = deps.routing::applyPreset,
        onSaveRule = deps.routing::saveRule,
        onDeleteRule = deps.routing::deleteRule,
        onOpenJson = { routingState.activeProfileId?.let(deps.profiles::openEditor) },
        onOpenServers = { nav.switchTab(AppTab.Servers) },
    )
}
