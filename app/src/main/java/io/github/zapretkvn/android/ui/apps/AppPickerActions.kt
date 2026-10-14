package io.github.zapretkvn.android.ui.apps

import io.github.zapretkvn.android.apps.AppsViewModel
import io.github.zapretkvn.android.ui.AppPickerMode

/** Единственное место, где режим выбора решает, в какой список писать: в разрешённые или заблокированные. */
internal fun AppsViewModel.setSelected(mode: AppPickerMode, packageName: String, selected: Boolean) {
    when (mode) {
        AppPickerMode.VpnScope -> setAllowed(packageName, selected)
        AppPickerMode.Blocked -> setBlocked(packageName, selected)
    }
}

internal fun AppsViewModel.removeMissing(mode: AppPickerMode) {
    when (mode) {
        AppPickerMode.VpnScope -> removeMissingAllowedPackages()
        AppPickerMode.Blocked -> removeMissingBlockedPackages()
    }
}
