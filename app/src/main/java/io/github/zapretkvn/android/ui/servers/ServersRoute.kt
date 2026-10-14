package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.ProfilesScreen
import io.github.zapretkvn.core.designsystem.ZapretTopBar

@Composable
fun ServersRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val state by deps.profiles.state.collectAsStateWithLifecycle()
    ProfilesScreen(contentPadding = padding, state = state, viewModel = deps.profiles)
}

@Composable
fun ProfilesRoute(deps: AppDependencies, nav: AppNavigation) {
    val state by deps.profiles.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { ZapretTopBar(title = "Профили", onBack = nav.back) }) { padding ->
        ProfilesScreen(contentPadding = padding, state = state, viewModel = deps.profiles)
    }
}
