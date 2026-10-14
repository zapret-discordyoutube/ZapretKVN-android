package io.github.zapretkvn.android.apps

data class AppsUiState(
    val apps: List<InstalledApp> = emptyList(),
    val allowedPackages: Set<String> = emptySet(),
    val blockedPackages: Set<String> = emptySet(),
    val scopeMode: AppScopeMode = AppScopeMode.Include,
    val initialized: Boolean = false,
    val catalogLoaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
) {
    val needsAppSelection: Boolean
        get() = initialized && when (scopeMode) {
            AppScopeMode.Include -> allowedPackages.isEmpty() && blockedPackages.isEmpty()
            AppScopeMode.Exclude -> allowedPackages.isEmpty()
        }

    val missingAllowedPackages: Set<String>
        get() = if (catalogLoaded) {
            allowedPackages - apps.asSequence().map(InstalledApp::packageName).toSet()
        } else {
            emptySet()
        }

    val missingBlockedPackages: Set<String>
        get() = if (catalogLoaded) {
            blockedPackages - apps.asSequence().map(InstalledApp::packageName).toSet()
        } else {
            emptySet()
        }
}
