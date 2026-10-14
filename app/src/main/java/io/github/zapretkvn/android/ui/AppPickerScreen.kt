package io.github.zapretkvn.android.ui

import androidx.compose.runtime.Composable
import io.github.zapretkvn.android.apps.AppsUiState
import io.github.zapretkvn.android.apps.AppsViewModel
import io.github.zapretkvn.android.ui.apps.AppPickerContent
import io.github.zapretkvn.android.ui.apps.removeMissing
import io.github.zapretkvn.android.ui.apps.setSelected

enum class AppPickerMode {
    VpnScope,
    Blocked,
}

/** Точка входа для `AppRoot`; весь экран живёт в пакете `ui.apps`. */
@Composable
fun AppPickerScreen(
    state: AppsUiState,
    viewModel: AppsViewModel,
    mode: AppPickerMode,
    onBack: () -> Unit,
) {
    AppPickerContent(
        state = state,
        mode = mode,
        onToggle = { packageName, selected -> viewModel.setSelected(mode, packageName, selected) },
        onRemoveMissing = { packageName -> viewModel.setSelected(mode, packageName, false) },
        onRemoveAllMissing = { viewModel.removeMissing(mode) },
        onRefresh = viewModel::refresh,
        onBack = onBack,
    )
}
