package io.github.zapretkvn.android.profiles

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Собранные вместе контроллеры профилей над одним [ProfilesUiState]. */
internal class ProfilesModule(
    val state: MutableStateFlow<ProfilesUiState>,
    val operations: ProfilesOperations,
    val list: ProfileListController,
    val imports: ImportController,
    val subscriptionSettings: SubscriptionSettingsController,
    val servers: ServerCatalogController,
    val editor: ProfileEditorController,
    val settings: ProfileSettingsActions,
    val serverLatency: StateFlow<ServerLatencyEntries>,
    val serverCountries: StateFlow<Map<String, String>>,
) {
    companion object {
        fun create(
            scope: CoroutineScope,
            store: ProfileStore,
            settingsStore: UiSettingsStore,
            validator: ConfigValidator,
            importReader: AndroidImportReader,
            subscriptionFetcher: SubscriptionFetcher,
            subscriptionSourceStore: SubscriptionSourceStore,
            vpnController: VpnController,
            bootstrapCache: BootstrapCache,
            ruleSetAssets: RuleSetAssetManager,
            serverLatencyStore: ServerLatencyStore?,
            serverCountryStore: ServerCountryStore?,
        ): ProfilesModule {
            val state = MutableStateFlow(ProfilesUiState())
            val operations = ProfilesOperations(state, scope)
            val vpn = VpnControllerPort(vpnController)
            val settingsPort = UiSettingsStorePort(settingsStore)
            val installRuleSets = suspend { withContext(Dispatchers.IO) { ruleSetAssets.ensureInstalled() } }
            val removeBootstrap: suspend (String) -> Unit = bootstrapCache::removeProfile

            val importService = ImportService(
                store = store,
                validator = validator,
                input = AndroidImportInput(importReader),
                subscriptionFetcher = subscriptionFetcher,
                subscriptionSourceStore = subscriptionSourceStore,
                settings = settingsPort,
                vpn = vpn,
                installRuleSets = installRuleSets,
            )
            val servers = ServerCatalogController(
                state, operations, scope, store, vpn, serverLatencyStore, serverCountryStore,
            )
            val editor = ProfileEditorController(state, operations, scope, store, validator)
            val list = ProfileListController(
                state = state,
                operations = operations,
                scope = scope,
                store = store,
                settings = settingsPort,
                vpn = vpn,
                subscriptionSourceStore = subscriptionSourceStore,
                removeBootstrap = removeBootstrap,
                onProfilesChanged = servers::refreshSummaries,
                onProfileRenamed = editor::onProfileRenamed,
                onProfileDeleted = editor::onProfileDeleted,
            )
            val imports = ImportController(
                state = state,
                operations = operations,
                service = importService,
                committer = ImportCommitter(store, subscriptionSourceStore, settingsPort, installRuleSets),
                splitCommitter = SplitRefreshCommitter(
                    store, subscriptionSourceStore, removeBootstrap, ::newProfileId,
                ),
                store = store,
                vpn = vpn,
                settings = settingsPort,
            )
            return ProfilesModule(
                state = state,
                operations = operations,
                list = list,
                imports = imports,
                subscriptionSettings = SubscriptionSettingsController(
                    state, operations, importService, subscriptionSourceStore, settingsPort,
                ),
                servers = servers,
                editor = editor,
                settings = ProfileSettingsActions(settingsStore, operations, vpn),
                serverLatency = serverLatencyStore?.entries
                    ?: MutableStateFlow<ServerLatencyEntries>(emptyMap()).asStateFlow(),
                serverCountries = serverCountryStore?.countries
                    ?: MutableStateFlow<Map<String, String>>(emptyMap()).asStateFlow(),
            )
        }
    }
}
