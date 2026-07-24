package io.github.zapretkvn.android.vpn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppsUiState(
    val apps: List<InstalledApp> = emptyList(),
    val allowedPackages: Set<String> = emptySet(),
    val scopeMode: AppScopeMode = AppScopeMode.Include,
    val initialized: Boolean = false,
    val catalogLoaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
) {
    val needsAppSelection: Boolean
        get() = initialized && allowedPackages.isEmpty()

    val missingPackages: Set<String>
        get() = if (catalogLoaded) {
            allowedPackages - apps.asSequence().map(InstalledApp::packageName).toSet()
        } else {
            emptySet()
        }
}

class AppsViewModel(
    private val selectionStore: AppSelectionStore,
    private val appCatalog: AppCatalog,
) : ViewModel() {
    private data class CatalogState(
        val apps: List<InstalledApp> = emptyList(),
        val loaded: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
        val warning: String? = null,
    )

    private val catalog = MutableStateFlow(CatalogState())

    init {
        refresh()
    }

    val state = combine(
        catalog,
        selectionStore.selection,
    ) { catalogState, selection ->
        AppsUiState(
            apps = catalogState.apps,
            allowedPackages = selection.allowedPackages,
            scopeMode = selection.mode,
            initialized = selection.initialized,
            catalogLoaded = catalogState.loaded,
            loading = catalogState.loading,
            error = catalogState.error,
            warning = catalogState.warning,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppsUiState(),
    )

    fun refresh() {
        if (catalog.value.loading) return
        catalog.value = catalog.value.copy(loading = true, error = null, warning = null)
        viewModelScope.launch {
            try {
                val snapshot = appCatalog.load()
                val rawProblemText = snapshot.rawProblemText()
                val failed = snapshot.discovery.completeness == AppDiscoveryCompleteness.Failed
                catalog.value = catalog.value.copy(
                    apps = if (failed) catalog.value.apps else snapshot.apps,
                    loaded = !failed,
                    error = rawProblemText.takeIf { failed },
                    warning = rawProblemText.takeUnless { failed },
                )
                if (failed) return@launch
                val installedSuggestedApps = defaultVpnPackages(snapshot.apps)
                val installedSuggestionMigrations =
                    PopularAppSuggestions.packagesAddedByRevision.mapValues { (_, packages) ->
                        installedSuggestedApps.intersect(packages)
                    }
                selectionStore.initializeIfNeeded(
                    suggestedPackages = installedSuggestedApps,
                    suggestedPackageMigrations = installedSuggestionMigrations,
                    suggestionRevision = PopularAppSuggestions.MIGRATION_REVISION,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                catalog.value = catalog.value.copy(
                    error = failure.toString(),
                )
            } finally {
                catalog.value = catalog.value.copy(loading = false)
            }
        }
    }

    fun setAllowed(packageName: String, allowed: Boolean) {
        viewModelScope.launch {
            selectionStore.setAllowed(packageName, allowed)
        }
    }

    fun setScopeMode(mode: AppScopeMode) {
        viewModelScope.launch { selectionStore.setMode(mode) }
    }

    fun removeMissingPackages() {
        val available = catalog.value.apps.asSequence().map(InstalledApp::packageName).toSet()
        viewModelScope.launch {
            selectionStore.replaceAllowlist(state.value.allowedPackages.intersect(available))
        }
    }

    class Factory(
        private val selectionStore: AppSelectionStore,
        private val appCatalog: AppCatalog,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppsViewModel(selectionStore, appCatalog) as T
    }
}

internal fun defaultVpnPackages(installedApps: List<InstalledApp>): Set<String> = installedApps
    .asSequence()
    .filter { it.suggestion != null }
    .map(InstalledApp::packageName)
    .toSet()
