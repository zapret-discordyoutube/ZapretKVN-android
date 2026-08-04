package io.github.zapretkvn.android.profiles

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.ConfigValidationResult
import io.github.zapretkvn.android.config.ConfigValidator
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.SelectorGroup
import io.github.zapretkvn.android.importer.AndroidImportReader
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.HttpSubscriptionFetcher
import io.github.zapretkvn.android.importer.ImportException
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.importer.ImportedConfigActivityScanner
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.importer.SubscriptionFetcher
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import io.github.zapretkvn.android.hardening.TunMtuMode
import io.github.zapretkvn.android.routing.RoutingConfigEditor
import io.github.zapretkvn.android.routing.RoutingPreset
import io.github.zapretkvn.android.routing.RuleSetAssetManager
import io.github.zapretkvn.android.ui.ThemeMode
import io.github.zapretkvn.android.ui.UiSettings
import io.github.zapretkvn.android.ui.UiSettingsStore
import io.github.zapretkvn.android.ui.NetworkTransportSetting
import io.github.zapretkvn.android.updates.UpdateChannel
import io.github.zapretkvn.android.vpn.VpnController
import io.github.zapretkvn.android.vpn.BootstrapCache
import io.github.zapretkvn.android.vpn.VpnConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfileEditorState(
    val profileId: String,
    val profileName: String,
    val originalText: String,
    val text: String,
    val search: String = "",
    val validationMessage: String? = null,
    val validationSuccessful: Boolean = false,
    val selectors: List<SelectorGroup> = emptyList(),
    val serverTags: List<String> = emptyList(),
    val hasBackup: Boolean = false,
) {
    val hasUnsavedChanges: Boolean get() = text != originalText
    val searchMatches: Int
        get() {
            if (search.isBlank()) return 0
            var count = 0
            var start = 0
            while (start <= text.length - search.length) {
                val found = text.indexOf(search, startIndex = start, ignoreCase = true)
                if (found < 0) break
                count++
                start = found + 1
            }
            return count
        }
}

data class ProfilesUiState(
    val profiles: List<ProfileMetadata> = emptyList(),
    val settings: UiSettings = UiSettings(),
    val editor: ProfileEditorState? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val importPreview: ImportPreviewState? = null,
    val importCompletion: ImportCompletion? = null,
    val refreshableProfileIds: Set<String> = emptySet(),
    val serverSummaries: Map<String, ProfileServerSummary> = emptyMap(),
    val serverPicker: ProfileServerPickerState? = null,
    val initialized: Boolean = false,
)

data class ProfileServerPickerState(
    val profileId: String,
    val profileName: String,
    val groups: List<ProfileServerGroup>,
    val liveSwitch: Boolean,
) {
    val serverCount: Int get() = groups.sumOf { it.options.size }
}

data class ImportCompletion(
    val profileId: String,
    val profileName: String,
)

/** Верхняя граница разложения подписки: дальше список профилей перестаёт быть управляемым. */
internal const val MAX_SPLIT_PROFILES = 200

data class ImportPreviewState(
    val suggestedName: String,
    val sourceDescription: String,
    val serverCount: Int,
    val serverLabels: List<String>,
    val activityWarning: String?,
    val appendTargets: List<ProfileMetadata>,
    val refreshProfileId: String? = null,
    val refreshProfileName: String? = null,
    val activeRefresh: Boolean = false,
    val selectionChanged: Boolean = false,
    internal val candidate: ImportCandidate,
    internal val preparedJson: String,
    internal val sourceUrl: String? = null,
) {
    val isSingleManaged: Boolean
        get() = candidate is ImportCandidate.Managed && candidate.servers.size == 1
    val isRefresh: Boolean get() = refreshProfileId != null

    /** Сколько отдельных профилей получится, если разложить подписку по серверам. */
    val splittableServerCount: Int
        get() = (candidate as? ImportCandidate.Managed)?.servers?.size?.takeIf { it > 1 } ?: 0

    val splitSupported: Boolean get() = splittableServerCount in 2..MAX_SPLIT_PROFILES

    val hasSubscriptionUrl: Boolean get() = sourceUrl != null
}

class ProfilesViewModel(
    private val store: ProfileStore,
    private val settingsStore: UiSettingsStore,
    private val validator: ConfigValidator,
    private val importReader: AndroidImportReader,
    private val subscriptionFetcher: SubscriptionFetcher,
    private val subscriptionSourceStore: SubscriptionSourceStore,
    private val vpnController: VpnController,
    private val bootstrapCache: BootstrapCache,
    private val ruleSetAssets: RuleSetAssetManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProfilesUiState())
    private var serverSummaryKey: List<Pair<String, Long>>? = null
    val state: StateFlow<ProfilesUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val profiles = store.initialize()
                subscriptionSourceStore.retain(profiles.map(ProfileMetadata::id).toSet())
                mutableState.update {
                    it.copy(refreshableProfileIds = subscriptionSourceStore.ids())
                }
                combine(store.profiles, settingsStore.settings) { profiles, settings ->
                    profiles to settings
                }.collect { (profiles, settings) ->
                    mutableState.update {
                        it.copy(profiles = profiles, settings = settings, initialized = true)
                    }
                    refreshServerSummaries(profiles)
                    if (settings.activeProfileId != null && profiles.none { it.id == settings.activeProfileId }) {
                        settingsStore.setActiveProfile(null)
                    }
                }
            } catch (error: Exception) {
                showMessage(error.userMessage("Не удалось открыть профили."))
            }
        }
    }

    fun importDocument(uri: Uri) = operation {
        val raw = withContext(Dispatchers.IO) { importReader.readDocument(uri) }
        val displayName = withContext(Dispatchers.IO) { importReader.documentDisplayName(uri) }
        val suggestedName = displayName
            ?.substringBeforeLast('.', displayName)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "Профиль из файла"
        val sourceDescription = displayName
            ?.let(SecretRedactor::redactInline)
            ?.let { "Системный файл: $it" }
            ?: "Системный файл"
        preview(raw, ProfileSource.File, suggestedName, sourceDescription)
    }

    fun importClipboard() = operation {
        val raw = importReader.readClipboardAfterUserAction()
        preview(raw, ProfileSource.Clipboard, "Профиль из буфера", "Буфер обмена")
    }

    fun importQr(contents: String) = operation {
        preview(contents, ProfileSource.Qr, "Профиль из QR", "QR-код")
    }

    fun importUrl(url: String) = operation {
        val validatedUrl = HttpSubscriptionFetcher.validatedUrl(url)
        val raw = withContext(Dispatchers.IO) { subscriptionFetcher.fetch(validatedUrl) }
        preview(
            raw = raw,
            source = ProfileSource.Url,
            suggestedName = "Подписка",
            sourceDescription = SecretRedactor.redactInline(validatedUrl),
            sourceUrl = validatedUrl,
        )
    }

    fun refreshSubscription(profileId: String) = operation {
        val sourceUrl = subscriptionSourceStore.get(profileId)
            ?: throw ImportException("Для этого профиля не сохранён URL ручного обновления.")
        val stored = store.read(profileId)
        val raw = withContext(Dispatchers.IO) { subscriptionFetcher.fetch(sourceUrl) }
        val candidate = withContext(Dispatchers.Default) {
            ImportParser.parse(raw, ProfileSource.Url, stored.metadata.name)
        }
        val candidateJson = candidate.toJson()
        val update = when {
            candidate is ImportCandidate.Managed && ManagedProfileEditor.isManaged(stored.json) ->
                ManagedProfileEditor.refreshServers(stored.json, candidate.servers)
            candidate is ImportCandidate.RawJson ->
                ManagedProfileEditor.preserveSelectorDefaults(stored.json, candidateJson)
            else -> ManagedProfileUpdate(candidateJson, selectedTag = "", selectionChanged = false)
        }
        requireValid(update.json)
        val currentVpn = vpnController.state.value
        mutableState.update {
            it.copy(
                importPreview = ImportPreviewState(
                    suggestedName = stored.metadata.name,
                    sourceDescription = SecretRedactor.redactInline(sourceUrl),
                    serverCount = candidate.serverCount(),
                    serverLabels = candidate.serverLabels(),
                    activityWarning = importWarnings(update.json),
                    appendTargets = emptyList(),
                    refreshProfileId = profileId,
                    refreshProfileName = stored.metadata.name,
                    activeRefresh = currentVpn is VpnConnectionState.Connected &&
                        currentVpn.profileId == profileId,
                    selectionChanged = update.selectionChanged,
                    candidate = candidate,
                    preparedJson = update.json,
                    sourceUrl = sourceUrl,
                ),
                message = null,
            )
        }
    }

    fun confirmImport(name: String) = operation {
        val pending = mutableState.value.importPreview
            ?.takeUnless { it.isRefresh }
            ?: throw ImportException("Предпросмотр импорта уже закрыт.")
        val metadata = store.create(name, pending.preparedJson, pending.candidate.source)
        pending.sourceUrl?.let { subscriptionSourceStore.put(metadata.id, it) }
        if (mutableState.value.settings.activeProfileId == null) {
            settingsStore.setActiveProfile(metadata.id)
            settingsStore.setDnsMode(
                if (pending.candidate is ImportCandidate.Managed) DnsMode.Automatic else DnsMode.FromJson,
            )
        }
        mutableState.update {
            it.copy(
                importPreview = null,
                importCompletion = ImportCompletion(metadata.id, metadata.name),
                refreshableProfileIds = if (pending.sourceUrl != null) {
                    it.refreshableProfileIds + metadata.id
                } else {
                    it.refreshableProfileIds
                },
            )
        }
        showMessage("Профиль сохранён. Подключение не запускалось.")
    }

    /**
     * Раскладывает подписку по одному профилю на сервер. Ни один профиль не создаётся,
     * пока ядро не приняло все получившиеся JSON.
     */
    fun confirmImportPerServer(baseName: String) = operation {
        val pending = mutableState.value.importPreview
            ?.takeUnless { it.isRefresh }
            ?: throw ImportException("Предпросмотр импорта уже закрыт.")
        val servers = (pending.candidate as? ImportCandidate.Managed)?.servers
            ?.takeIf { it.size > 1 }
            ?: throw ImportException("Разделять можно только подписку с несколькими серверами.")
        if (servers.size > MAX_SPLIT_PROFILES) {
            throw ImportException(
                "Разделение доступно максимум для $MAX_SPLIT_PROFILES серверов; " +
                    "для больших подписок используйте выбор сервера внутри профиля.",
            )
        }
        val installed = withContext(Dispatchers.IO) { ruleSetAssets.ensureInstalled() }
        val prepared = withContext(Dispatchers.Default) {
            servers.map { server ->
                RoutingConfigEditor.apply(
                    ManagedProfileFactory.single(server),
                    RoutingPreset.RussiaDirect,
                    emptyList(),
                    installed,
                ).json
            }
        }
        val names = SplitProfileNaming.names(servers.map(ManagedServer::displayName), baseName)
        // ProfileStore.create сам проверяет JSON ядром; второй проход удвоил бы ожидание
        // на больших подписках, поэтому частичный импорт откатывается вручную.
        val created = mutableListOf<ProfileMetadata>()
        try {
            prepared.forEachIndexed { index, json ->
                created += store.create(names[index], json, ProfileSource.Link)
            }
        } catch (error: Throwable) {
            created.forEach { profile -> runCatching { store.delete(profile.id) } }
            throw error
        }
        val first = created.first()
        if (mutableState.value.settings.activeProfileId == null) {
            settingsStore.setActiveProfile(first.id)
            settingsStore.setDnsMode(DnsMode.Automatic)
        }
        mutableState.update {
            it.copy(
                importPreview = null,
                importCompletion = ImportCompletion(first.id, first.name),
            )
        }
        showMessage("Создано профилей: ${created.size}. Подключение не запускалось.")
    }

    fun confirmAppend(targetProfileId: String) = operation {
        val pending = mutableState.value.importPreview
            ?.takeUnless { it.isRefresh }
            ?: throw ImportException("Предпросмотр импорта уже закрыт.")
        val server = (pending.candidate as? ImportCandidate.Managed)?.servers?.singleOrNull()
            ?: throw ImportException("Добавить можно только одну серверную ссылку.")
        val target = store.read(targetProfileId)
        val update = ManagedProfileEditor.appendServer(target.json, server)
        store.update(targetProfileId, update.json)
        mutableState.update { it.copy(importPreview = null) }
        showMessage("Сервер добавлен в ${target.metadata.name}. Работающий VPN не изменён.")
    }

    fun confirmRefresh(restartConnected: Boolean) = operation {
        val pending = mutableState.value.importPreview
            ?.takeIf { it.isRefresh }
            ?: throw ImportException("Предпросмотр обновления уже закрыт.")
        val profileId = checkNotNull(pending.refreshProfileId)
        store.update(profileId, pending.preparedJson)
        mutableState.update { it.copy(importPreview = null) }
        val connectedNow = (vpnController.state.value as? VpnConnectionState.Connected)
            ?.profileId == profileId
        if (restartConnected && connectedNow) {
            vpnController.restartIfConnected("Подписка обновлена пользователем")
            showMessage(
                if (pending.selectionChanged) {
                    "Выбранный сервер исчез: выбран первый доступный; VPN контролируемо перезапущен."
                } else {
                    "Подписка сохранена; подтверждён контролируемый перезапуск VPN."
                },
            )
        } else if (restartConnected && pending.activeRefresh) {
            showMessage("Подписка сохранена; VPN уже отключён, перезапуск не требуется.")
        } else if (pending.selectionChanged) {
            showMessage("Выбранный сервер исчез: выбран первый доступный. VPN не перезапущен.")
        } else {
            showMessage("Подписка обновлена. Работающий VPN не изменён.")
        }
    }

    fun dismissImportPreview() {
        mutableState.update { it.copy(importPreview = null) }
    }

    fun consumeImportCompletion() {
        mutableState.update { it.copy(importCompletion = null) }
    }

    fun selectProfile(id: String) = operation {
        require(mutableState.value.profiles.any { it.id == id }) { "Профиль не найден." }
        settingsStore.setActiveProfile(id)
        val switching = vpnController.switchProfileIfConnected(id)
        showMessage(
            if (switching) {
                "Активный профиль выбран; VPN переключается."
            } else {
                "Активный профиль выбран."
            },
        )
    }

    fun openServerPicker(profileId: String) = operation {
        val stored = store.read(profileId)
        val summary = withContext(Dispatchers.Default) {
            ProfileServerCatalog.summarize(stored.json)
        }
        if (summary.groups.isEmpty()) {
            throw ImportException("В профиле нет группы серверов sing-box (selector).")
        }
        mutableState.update {
            it.copy(
                serverPicker = ProfileServerPickerState(
                    profileId = profileId,
                    profileName = stored.metadata.name,
                    groups = summary.groups,
                    liveSwitch = isConnectedTo(profileId),
                ),
                serverSummaries = it.serverSummaries + (profileId to summary),
                message = null,
            )
        }
    }

    fun dismissServerPicker() {
        mutableState.update { it.copy(serverPicker = null) }
    }

    fun selectProfileServer(groupTag: String, serverTag: String) = operation {
        val picker = mutableState.value.serverPicker ?: return@operation
        val profileId = picker.profileId
        val live = isConnectedTo(profileId)
        if (live) {
            // Ядро само проверит, сохранит профиль и переключит outbound без разрыва туннеля.
            vpnController.selectOutbound(profileId, groupTag, serverTag)
        } else {
            val stored = store.read(profileId)
            val updated = withContext(Dispatchers.Default) {
                ConfigAnalyzer.selectServer(stored.json, groupTag, serverTag)
            }
            store.update(profileId, updated)
        }
        mutableState.update { state ->
            val current = state.serverPicker?.takeIf { it.profileId == profileId }
                ?: return@update state
            val groups = current.groups.map { group ->
                if (group.tag == groupTag) group.copy(selected = serverTag) else group
            }
            state.copy(
                serverPicker = current.copy(groups = groups, liveSwitch = live),
                serverSummaries = state.serverSummaries + (profileId to ProfileServerSummary(groups)),
            )
        }
        val label = SecretRedactor.redactInline(serverTag)
        showMessage(
            if (live) {
                "Сервер $label переключается без перезапуска VPN."
            } else {
                "Сервер $label выбран. VPN не запускался."
            },
        )
    }

    fun renameProfile(id: String, name: String) = operation {
        store.rename(id, name)
        mutableState.update { state ->
            state.copy(
                editor = state.editor?.takeIf { it.profileId == id }?.copy(profileName = name.trim())
                    ?: state.editor,
            )
        }
        showMessage("Профиль переименован.")
    }

    fun deleteProfile(id: String) = operation {
        val wasActive = mutableState.value.settings.activeProfileId == id
        store.delete(id)
        bootstrapCache.removeProfile(id)
        subscriptionSourceStore.remove(id)
        mutableState.update { state ->
            state.copy(
                refreshableProfileIds = state.refreshableProfileIds - id,
                serverSummaries = state.serverSummaries - id,
                serverPicker = state.serverPicker?.takeUnless { it.profileId == id },
            )
        }
        if (wasActive) settingsStore.setActiveProfile(store.profiles.value.firstOrNull()?.id)
        if (mutableState.value.editor?.profileId == id) closeEditor(force = true)
        showMessage("Профиль удалён.")
    }

    fun openEditor(id: String) = operation {
        val profile = store.read(id)
        mutableState.update {
            it.copy(
                editor = editorState(profile),
                message = null,
            )
        }
    }

    fun closeEditor(force: Boolean = false): Boolean {
        val editor = mutableState.value.editor ?: return true
        if (editor.hasUnsavedChanges && !force) return false
        mutableState.update { it.copy(editor = null) }
        return true
    }

    fun updateEditorText(text: String) {
        mutableState.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = editor.withText(text))
        }
    }

    fun updateSearch(search: String) {
        mutableState.update { state ->
            state.copy(editor = state.editor?.copy(search = search))
        }
    }

    fun formatEditor() {
        val editor = mutableState.value.editor ?: return
        try {
            updateEditorText(JsonConfig.format(editor.text))
            setEditorValidation("JSON отформатирован.", true)
        } catch (_: Exception) {
            setEditorValidation("Сначала исправьте синтаксис JSON.", false)
        }
    }

    fun validateEditor() = operation(markBusy = false) {
        val editor = mutableState.value.editor ?: return@operation
        when (val result = withContext(Dispatchers.IO) { validator.validate(editor.text) }) {
            ConfigValidationResult.Valid -> setEditorValidation("Конфигурация корректна.", true)
            is ConfigValidationResult.Invalid -> setEditorValidation(result.message, false)
        }
    }

    fun saveEditor() = operation {
        val editor = mutableState.value.editor ?: return@operation
        store.update(editor.profileId, editor.text)
        val stored = store.read(editor.profileId)
        mutableState.update { it.copy(editor = editorState(stored)) }
        showMessage("Профиль сохранён.")
    }

    fun restoreBackup() = operation {
        val editor = mutableState.value.editor ?: return@operation
        store.restoreBackup(editor.profileId)
        val stored = store.read(editor.profileId)
        mutableState.update { it.copy(editor = editorState(stored)) }
        showMessage("Backup восстановлен; прежняя текущая версия стала backup.")
    }

    fun selectServer(selectorTag: String, serverTag: String) {
        val editor = mutableState.value.editor ?: return
        try {
            updateEditorText(ConfigAnalyzer.selectServer(editor.text, selectorTag, serverTag))
            setEditorValidation("Выбор записан в selector.default. Нажмите «Сохранить».", true)
        } catch (error: Exception) {
            setEditorValidation(error.userMessage("Не удалось выбрать сервер."), false)
        }
    }

    fun createManagedSelector() {
        val editor = mutableState.value.editor ?: return
        try {
            updateEditorText(ConfigAnalyzer.addManagedSelector(editor.text, editor.serverTags))
            setEditorValidation("zapret-proxy добавлен явно. Нажмите «Сохранить».", true)
        } catch (error: Exception) {
            setEditorValidation(error.userMessage("Не удалось создать selector."), false)
        }
    }

    fun setTheme(mode: ThemeMode) = operation(markBusy = false) {
        settingsStore.setThemeMode(mode)
    }

    fun setRawEditorLineWrap(enabled: Boolean) = operation(markBusy = false) {
        settingsStore.setRawEditorLineWrap(enabled)
    }

    fun setDnsMode(mode: DnsMode) = operation(markBusy = false) {
        settingsStore.setDnsMode(mode)
        vpnController.restartIfConnected("Смена режима DNS")
    }

    fun setProxyIpv4Only(enabled: Boolean) = operation(markBusy = false) {
        settingsStore.setProxyIpv4Only(enabled)
        vpnController.restartIfConnected("Смена IP-стратегии DNS")
    }

    fun setDnsOverrideEnabled(enabled: Boolean) = operation(markBusy = false) {
        settingsStore.setDnsOverrideEnabled(enabled)
        vpnController.restartIfConnected("Смена DNS-переопределения")
    }

    fun setDnsOverride(hostname: String, ipv4Address: String) = operation(markBusy = false) {
        settingsStore.setDnsOverride(hostname, ipv4Address)
        vpnController.restartIfConnected("Смена DNS-переопределения")
    }

    fun setUpdateChannel(channel: UpdateChannel) = operation(markBusy = false) {
        settingsStore.setUpdateChannel(channel)
    }

    fun setVpnHidingBlockLocalEndpoints(enabled: Boolean) = operation(markBusy = false) {
        settingsStore.setVpnHidingBlockLocalEndpoints(enabled)
        vpnController.restartIfConnected("Смена защиты от localhost-чекеров")
    }

    fun setVpnHidingNeutralSessionName(enabled: Boolean) = operation(markBusy = false) {
        settingsStore.setVpnHidingNeutralSessionName(enabled)
        vpnController.restartIfConnected("Смена имени VPN-сессии")
    }

    fun setVpnHidingTunMtuMode(mode: TunMtuMode) = operation(markBusy = false) {
        settingsStore.setVpnHidingTunMtuMode(mode)
        vpnController.restartIfConnected("Смена MTU для скрытия VPN")
    }

    fun setNetworkAutomationEnabled(enabled: Boolean) = operation(markBusy = false) {
        settingsStore.setNetworkAutomationEnabled(enabled)
    }

    fun setUseVpnOnNetwork(transport: NetworkTransportSetting, enabled: Boolean) =
        operation(markBusy = false) {
            settingsStore.setUseVpnOnNetwork(transport, enabled)
        }

    fun setPauseOnTrustedWifi(enabled: Boolean) = operation(markBusy = false) {
        settingsStore.setPauseOnTrustedWifi(enabled)
    }

    fun addTrustedWifi(ssid: String) = operation(markBusy = false) {
        settingsStore.addTrustedWifi(ssid)
    }

    fun removeTrustedWifi(ssid: String) = operation(markBusy = false) {
        settingsStore.removeTrustedWifi(ssid)
    }

    fun consumeMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private suspend fun preview(
        raw: String,
        source: ProfileSource,
        suggestedName: String,
        sourceDescription: String,
        sourceUrl: String? = null,
    ) {
        val candidate = withContext(Dispatchers.Default) {
            ImportParser.parse(raw, source, suggestedName)
        }
        val baseJson = candidate.toJson()
        val json = if (candidate is ImportCandidate.Managed) {
            val installed = withContext(Dispatchers.IO) { ruleSetAssets.ensureInstalled() }
            withContext(Dispatchers.Default) {
                RoutingConfigEditor.apply(
                    baseJson,
                    RoutingPreset.RussiaDirect,
                    emptyList(),
                    installed,
                ).json
            }
        } else {
            baseJson
        }
        requireValid(json)
        val appendTargets = if (candidate is ImportCandidate.Managed && candidate.servers.size == 1) {
            buildList {
                for (profile in mutableState.value.profiles) {
                    if (runCatching { ManagedProfileEditor.isManaged(store.read(profile.id).json) }
                            .getOrDefault(false)
                    ) {
                        add(profile)
                    }
                }
            }
        } else {
            emptyList()
        }
        mutableState.update {
            it.copy(
                importPreview = ImportPreviewState(
                    suggestedName = SecretRedactor.redactInline(candidate.suggestedName),
                    sourceDescription = sourceDescription,
                    serverCount = candidate.serverCount(),
                    serverLabels = candidate.serverLabels(),
                    activityWarning = importWarnings(json),
                    appendTargets = appendTargets,
                    candidate = candidate,
                    preparedJson = json,
                    sourceUrl = sourceUrl,
                ),
                message = null,
            )
        }
    }

    private fun isConnectedTo(profileId: String): Boolean =
        (vpnController.state.value as? VpnConnectionState.Connected)?.profileId == profileId

    /**
     * Список серверов каждого профиля нужен на карточке профиля и на главной,
     * поэтому он пересчитывается при любом изменении содержимого профилей.
     */
    private fun refreshServerSummaries(profiles: List<ProfileMetadata>) {
        val key = profiles.map { it.id to it.updatedAtEpochMillis }
        if (key == serverSummaryKey) return
        serverSummaryKey = key
        viewModelScope.launch {
            val summaries = mutableMapOf<String, ProfileServerSummary>()
            for (profile in profiles) {
                val json = runCatching { store.read(profile.id).json }.getOrNull() ?: continue
                val summary = withContext(Dispatchers.Default) { ProfileServerCatalog.summarize(json) }
                if (summary.groups.isNotEmpty()) summaries[profile.id] = summary
            }
            if (serverSummaryKey != key) return@launch
            mutableState.update { it.copy(serverSummaries = summaries) }
        }
    }

    private fun importWarnings(json: String): String? = buildList {
        ImportedConfigActivityScanner.warning(ImportedConfigActivityScanner.scan(json))
            ?.let(::add)
        addAll(ConfigAnalyzer.dnsWarnings(json))
    }.takeIf(List<String>::isNotEmpty)?.joinToString("\n")

    private fun ImportCandidate.toJson(): String = when (this) {
        is ImportCandidate.RawJson -> json
        is ImportCandidate.Managed -> buildJson()
        is ImportCandidate.WireGuard -> json
    }

    private fun ImportCandidate.serverCount(): Int = when (this) {
        is ImportCandidate.RawJson -> runCatching {
            ConfigAnalyzer.serverOutboundTags(json).size
        }.getOrDefault(0)
        is ImportCandidate.Managed -> servers.size
        is ImportCandidate.WireGuard -> 1
    }

    private fun ImportCandidate.serverLabels(): List<String> = when (this) {
        is ImportCandidate.RawJson -> runCatching {
            ConfigAnalyzer.serverOutboundTags(json)
        }.getOrDefault(emptyList())
        is ImportCandidate.Managed -> servers.map(ManagedServer::displayName)
        is ImportCandidate.WireGuard -> listOfNotNull(
            endpointLabel?.let { "$protocolName · $it" } ?: protocolName,
        )
    }.take(8).map(SecretRedactor::redactInline)

    private suspend fun requireValid(rawJson: String) {
        when (val result = withContext(Dispatchers.IO) { validator.validate(rawJson) }) {
            ConfigValidationResult.Valid -> Unit
            is ConfigValidationResult.Invalid -> throw ImportException(result.message)
        }
    }

    private fun editorState(profile: StoredProfile): ProfileEditorState = ProfileEditorState(
        profileId = profile.metadata.id,
        profileName = profile.metadata.name,
        originalText = profile.json,
        text = profile.json,
        selectors = runCatching { ConfigAnalyzer.selectorGroups(profile.json) }.getOrDefault(emptyList()),
        serverTags = runCatching { ConfigAnalyzer.serverOutboundTags(profile.json) }.getOrDefault(emptyList()),
        hasBackup = profile.hasBackup,
    )

    private fun ProfileEditorState.withText(value: String): ProfileEditorState = copy(
        text = value,
        validationMessage = null,
        validationSuccessful = false,
        selectors = runCatching { ConfigAnalyzer.selectorGroups(value) }.getOrDefault(emptyList()),
        serverTags = runCatching { ConfigAnalyzer.serverOutboundTags(value) }.getOrDefault(emptyList()),
    )

    private fun setEditorValidation(message: String, successful: Boolean) {
        mutableState.update { state ->
            state.copy(
                editor = state.editor?.copy(
                    validationMessage = message,
                    validationSuccessful = successful,
                ),
            )
        }
    }

    private fun operation(markBusy: Boolean = true, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (markBusy) mutableState.update { it.copy(busy = true) }
            try {
                block()
            } catch (error: Exception) {
                showMessage(error.userMessage("Операция не выполнена."))
            } finally {
                if (markBusy) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun showMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    private fun Throwable.userMessage(fallback: String): String =
        SecretRedactor.redactInline(message?.takeIf(String::isNotBlank)?.take(320) ?: fallback)

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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ProfilesViewModel::class.java))
            return ProfilesViewModel(
                store,
                settingsStore,
                validator,
                importReader,
                subscriptionFetcher,
                subscriptionSourceStore,
                vpnController,
                bootstrapCache,
                ruleSetAssets,
            ) as T
        }
    }
}
