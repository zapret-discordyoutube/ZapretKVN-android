package io.github.zapretkvn.android.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
        val message: String? = null,
    )

    private val catalog = MutableStateFlow(CatalogState())

    /** Номер изменения пакетов, при котором каталог читался последний раз. */
    private var loadedPackageSequence: Int? = null

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
            blockedPackages = selection.blockedPackages,
            scopeMode = selection.mode,
            initialized = selection.initialized,
            catalogLoaded = catalogState.loaded,
            loading = catalogState.loading,
            error = catalogState.error,
            warning = catalogState.warning,
            message = catalogState.message,
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
                val sequence = appCatalog.packageChangeSequence()
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
                loadedPackageSequence = sequence
                val installedSuggestedApps = defaultVpnPackages(snapshot.apps)
                val adopted = selectionStore.adoptSuggestions(installedSuggestedApps)
                selectionStore.migrateGeminiCompanionIfNeeded()
                adoptedAppsMessage(adopted, snapshot.apps)?.let { message ->
                    catalog.value = catalog.value.copy(message = message)
                }
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

    /**
     * Возврат в приложение: каталог перечитывается, только если с прошлого чтения что-то
     * установили или удалили, — так новый Telegram попадает в VPN до нажатия «Подключить».
     */
    fun refreshIfPackagesChanged() {
        if (loadedPackageSequence == appCatalog.packageChangeSequence()) return
        refresh()
    }

    fun consumeMessage() {
        catalog.value = catalog.value.copy(message = null)
    }

    fun setAllowed(packageName: String, allowed: Boolean) {
        viewModelScope.launch {
            selectionStore.setAllowed(packageName, allowed)
        }
    }

    fun setScopeMode(mode: AppScopeMode) {
        viewModelScope.launch { selectionStore.setMode(mode) }
    }

    fun setBlocked(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            selectionStore.setBlocked(packageName, blocked)
        }
    }

    fun removeMissingAllowedPackages() {
        val available = catalog.value.apps.asSequence().map(InstalledApp::packageName).toSet()
        viewModelScope.launch {
            selectionStore.replaceAllowlist(state.value.allowedPackages.intersect(available))
        }
    }

    fun removeMissingBlockedPackages() {
        val available = catalog.value.apps.asSequence().map(InstalledApp::packageName).toSet()
        viewModelScope.launch {
            selectionStore.replaceBlocklist(state.value.blockedPackages.intersect(available))
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

/** Сообщение о приложениях, которые сами попали в VPN; `null`, если ничего не добавлено. */
internal fun adoptedAppsMessage(adopted: Set<String>, apps: List<InstalledApp>): String? {
    if (adopted.isEmpty()) return null
    val labels = apps.asSequence()
        .filter { it.packageName in adopted }
        .map(InstalledApp::label)
        .sorted()
        .toList()
        .ifEmpty { adopted.sorted() }
    return "Через VPN теперь идут и новые приложения: ${labels.joinToString()}. " +
        "Если VPN уже включён, нажмите «Перезапустить» на Главной."
}
