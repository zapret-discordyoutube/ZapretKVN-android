package io.github.zapretkvn.android.profiles

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zapretkvn.android.config.ConfigValidator
import io.github.zapretkvn.android.importer.AndroidImportReader
import io.github.zapretkvn.android.importer.SubscriptionFetcher
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import io.github.zapretkvn.android.network.BootstrapCache
import io.github.zapretkvn.android.network.geo.ServerCountryStore
import io.github.zapretkvn.android.network.probes.ServerLatencyEntries
import io.github.zapretkvn.android.network.probes.ServerLatencyStore
import io.github.zapretkvn.android.routing.RuleSetAssetManager
import io.github.zapretkvn.android.ui.UiSettingsStore
import io.github.zapretkvn.android.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Единое состояние экранов профилей ([ProfilesUiState]) и точка входа для их действий.
 * Сама логика живёт в контроллерах: список профилей ([ProfileListController]), импорт
 * ([ImportController]), настройки подписки ([SubscriptionSettingsController]), серверы
 * ([ServerCatalogController]) и редактор JSON ([ProfileEditorController]).
 */
class ProfilesViewModel internal constructor(
    moduleFactory: (CoroutineScope) -> ProfilesModule,
) : ViewModel() {
    private val module = moduleFactory(viewModelScope)
    private val imports = module.imports
    private val subscriptionSettings = module.subscriptionSettings
    private val list = module.list
    private val servers = module.servers
    private val editor = module.editor

    val state: StateFlow<ProfilesUiState> = module.state.asStateFlow()

    /** Запись настроек из экранов (тема, DNS, скрытие VPN, автоматизация, канал обновлений). */
    val settings: ProfileSettingsActions get() = module.settings

    /** Последний пинг серверов всех профилей, сохранённый между запусками. */
    val serverLatency: StateFlow<ServerLatencyEntries> get() = module.serverLatency

    /** Хост сервера → код страны по его адресу (офлайн-таблица GeoIP); сам адрес не показывается. */
    val serverCountries: StateFlow<Map<String, String>> get() = module.serverCountries

    init {
        list.start()
    }

    // Импорт и обновление подписки
    fun importDocument(uri: Uri) = imports.importDocument(uri)
    fun importClipboard() = imports.importClipboard()
    fun importQr(contents: String) = imports.importQr(contents)
    fun importQrImage(uri: Uri) = imports.importQrImage(uri)
    fun closeSubscriptionLinkPrompt() = imports.closeSubscriptionLinkPrompt()
    fun importPromptedSubscription(url: String, identity: SubscriptionIdentityInput) =
        imports.importPromptedSubscription(url, identity)
    fun importUrl(url: String, identity: SubscriptionIdentityInput = SubscriptionIdentityInput()) =
        imports.importUrl(url, identity)
    fun refreshSubscription(profileId: String) = imports.refreshSubscription(profileId)
    fun confirmImport(name: String) = imports.confirmImport(name)
    fun confirmImportPerServer(baseName: String) = imports.confirmImportPerServer(baseName)
    fun confirmAppend(targetProfileId: String) = imports.confirmAppend(targetProfileId)
    fun confirmRefresh(restartConnected: Boolean) = imports.confirmRefresh(restartConnected)
    fun dismissImportPreview() = imports.dismissPreview()
    fun consumeImportCompletion() = imports.consumeCompletion()

    // Настройки подписки профиля
    fun openSubscriptionSettings(profileId: String) = subscriptionSettings.open(profileId)
    fun closeSubscriptionSettings() = subscriptionSettings.close()
    fun saveSubscriptionSettings(identity: SubscriptionIdentityInput) =
        subscriptionSettings.save(identity)

    // Список профилей
    fun selectProfile(id: String) = list.select(id)
    fun renameProfile(id: String, name: String) = list.rename(id, name)
    fun deleteProfile(id: String) = list.delete(id)

    // Серверы профиля
    fun openServerPicker(profileId: String) = servers.openPicker(profileId)
    fun dismissServerPicker() = servers.dismissPicker()
    fun selectProfileServer(groupTag: String, serverTag: String) =
        servers.selectServer(groupTag, serverTag)

    // Редактор сырого JSON
    fun openEditor(id: String) = editor.open(id)
    fun closeEditor(force: Boolean = false): Boolean = editor.close(force)
    fun updateEditorText(text: String) = editor.updateText(text)
    fun updateSearch(search: String) = editor.updateSearch(search)
    fun formatEditor() = editor.format()
    fun validateEditor() = editor.validate()
    fun saveEditor() = editor.save()
    fun restoreBackup() = editor.restoreBackup()
    fun selectServer(selectorTag: String, serverTag: String) = editor.selectServer(selectorTag, serverTag)
    fun createManagedSelector() = editor.createManagedSelector()

    fun consumeMessage() = module.operations.consumeMessage()

    class Factory(
        private val store: ProfileStore,
        private val settingsStore: UiSettingsStore,
        private val validator: ConfigValidator,
        private val importReader: AndroidImportReader,
        private val subscriptionFetcher: SubscriptionFetcher,
        private val subscriptionSourceStore: SubscriptionSourceStore,
        private val vpnController: VpnController,
        private val bootstrapCache: BootstrapCache,
        private val ruleSetAssets: RuleSetAssetManager,
        private val serverLatencyStore: ServerLatencyStore? = null,
        private val serverCountryStore: ServerCountryStore? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ProfilesViewModel::class.java))
            return ProfilesViewModel { scope ->
                ProfilesModule.create(
                    scope = scope,
                    store = store,
                    settingsStore = settingsStore,
                    validator = validator,
                    importReader = importReader,
                    subscriptionFetcher = subscriptionFetcher,
                    subscriptionSourceStore = subscriptionSourceStore,
                    vpnController = vpnController,
                    bootstrapCache = bootstrapCache,
                    ruleSetAssets = ruleSetAssets,
                    serverLatencyStore = serverLatencyStore,
                    serverCountryStore = serverCountryStore,
                )
            } as T
        }
    }
}
