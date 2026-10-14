package io.github.zapretkvn.android.ui.routing

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.AppPickerMode
import io.github.zapretkvn.android.ui.RoutingScreen

@Composable
fun RoutingRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val appsState by deps.apps.state.collectAsStateWithLifecycle()
    val routingState by deps.routing.state.collectAsStateWithLifecycle()
    RoutingScreen(
        contentPadding = padding,
        appsState = appsState,
        appsViewModel = deps.apps,
        routingState = routingState,
        routingViewModel = deps.routing,
        onOpenPicker = { nav.openAppPicker(AppPickerMode.VpnScope) },
        onOpenBlockPicker = { nav.openAppPicker(AppPickerMode.Blocked) },
        onOpenAdvancedJson = { routingState.activeProfileId?.let(deps.profiles::openEditor) },
    )
}
