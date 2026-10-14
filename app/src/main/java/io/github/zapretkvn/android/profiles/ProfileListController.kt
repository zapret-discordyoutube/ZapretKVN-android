package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Список профилей и активный профиль: загрузка и подписка на хранилище и настройки,
 * выбор, переименование, удаление.
 */
internal class ProfileListController(
    private val state: MutableStateFlow<ProfilesUiState>,
    private val operations: ProfilesOperations,
    private val scope: CoroutineScope,
    private val store: ProfileStore,
    private val settings: ProfilesSettingsPort,
    private val vpn: ProfilesVpnPort,
    private val subscriptionSourceStore: SubscriptionSourceStore,
    private val removeBootstrap: suspend (String) -> Unit,
    private val onProfilesChanged: (List<ProfileMetadata>) -> Unit,
    private val onProfileRenamed: (id: String, name: String) -> Unit,
    private val onProfileDeleted: (id: String) -> Unit,
) {
    fun start() {
        scope.launch {
            try {
                val profiles = store.initialize()
                subscriptionSourceStore.retain(profiles.map(ProfileMetadata::id).toSet())
                state.update { it.copy(refreshableProfileIds = subscriptionSourceStore.ids()) }
                combine(store.profiles, settings.settings) { profiles, settings ->
                    profiles to settings
                }.collect { (profiles, settings) ->
                    state.update {
                        it.copy(profiles = profiles, settings = settings, initialized = true)
                    }
                    onProfilesChanged(profiles)
                    if (settings.activeProfileId != null && profiles.none { it.id == settings.activeProfileId }) {
                        this@ProfileListController.settings.setActiveProfile(null)
                    }
                }
            } catch (error: Exception) {
                operations.showMessage(error.userMessage("Не удалось открыть профили."))
            }
        }
    }

    fun select(id: String) = operations.run {
        require(state.value.profiles.any { it.id == id }) { "Профиль не найден." }
        settings.setActiveProfile(id)
        val switching = vpn.switchProfileIfConnected(id)
        operations.showMessage(
            if (switching) {
                "Активный профиль выбран; VPN переключается."
            } else {
                "Активный профиль выбран."
            },
        )
    }

    fun rename(id: String, name: String) = operations.run {
        store.rename(id, name)
        onProfileRenamed(id, name)
        operations.showMessage("Профиль переименован.")
    }

    fun delete(id: String) = operations.run {
        val wasActive = state.value.settings.activeProfileId == id
        store.delete(id)
        removeBootstrap(id)
        subscriptionSourceStore.remove(id)
        state.update {
            it.copy(
                refreshableProfileIds = it.refreshableProfileIds - id,
                serverSummaries = it.serverSummaries - id,
                serverPicker = it.serverPicker?.takeUnless { picker -> picker.profileId == id },
            )
        }
        if (wasActive) settings.setActiveProfile(store.profiles.value.firstOrNull()?.id)
        onProfileDeleted(id)
        operations.showMessage("Профиль удалён.")
    }
}
