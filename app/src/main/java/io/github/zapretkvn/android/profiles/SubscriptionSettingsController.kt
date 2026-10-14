package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.ImportException
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Диалог настроек идентификации подписки профиля (клиентский профиль, HWID). */
internal class SubscriptionSettingsController(
    private val state: MutableStateFlow<ProfilesUiState>,
    private val operations: ProfilesOperations,
    private val service: ImportService,
    private val subscriptionSourceStore: SubscriptionSourceStore,
    private val settings: ProfilesSettingsPort,
) {
    fun open(profileId: String) = operations.run {
        val subscription = subscriptionSourceStore.get(profileId)
            ?: throw ImportException("Для этого профиля не сохранён URL ручного обновления.")
        val name = state.value.profiles.firstOrNull { it.id == profileId }?.name.orEmpty()
        val installationHwid = settings.subscriptionDeviceId()
        state.update {
            it.copy(
                subscriptionSettings = SubscriptionSettingsState(
                    profileId = profileId,
                    profileName = name,
                    url = subscription.url,
                    clientProfile = subscription.clientProfile,
                    sendHwid = subscription.sendHwid,
                    hwid = subscription.hwid,
                    installationHwid = installationHwid,
                ),
                message = null,
            )
        }
    }

    fun close() {
        state.update { it.copy(subscriptionSettings = null) }
    }

    fun save(identity: SubscriptionIdentityInput) = operations.run {
        val current = state.value.subscriptionSettings
            ?: throw ImportException("Настройки подписки уже закрыты.")
        val subscription = service.resolveSubscription(current.url, identity)
        subscriptionSourceStore.put(current.profileId, subscription)
        state.update {
            it.copy(
                subscriptionSettings = null,
                message = "Настройки подписки сохранены.",
            )
        }
    }
}
