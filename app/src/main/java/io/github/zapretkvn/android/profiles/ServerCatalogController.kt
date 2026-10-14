package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.importer.ImportException
import io.github.zapretkvn.android.network.geo.ServerCountryStore
import io.github.zapretkvn.android.network.probes.ServerLatencyFingerprint
import io.github.zapretkvn.android.network.probes.ServerLatencyStore
import io.github.zapretkvn.core.uimodel.endpointHostKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Производные данные о серверах профилей: сводки (карточки профилей, Главная), страны по IP,
 * очистка сохранённого пинга и офлайн-выбор сервера через «пикер» профиля.
 */
internal class ServerCatalogController(
    private val state: MutableStateFlow<ProfilesUiState>,
    private val operations: ProfilesOperations,
    private val scope: CoroutineScope,
    private val store: ProfileStore,
    private val vpn: ProfilesVpnPort,
    private val serverLatencyStore: ServerLatencyStore? = null,
    private val serverCountryStore: ServerCountryStore? = null,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var summaryKey: List<Pair<String, Long>>? = null

    /**
     * Список серверов каждого профиля нужен на карточке профиля и на главной,
     * поэтому он пересчитывается при любом изменении содержимого профилей.
     */
    fun refreshSummaries(profiles: List<ProfileMetadata>) {
        val key = profiles.map { it.id to it.updatedAtEpochMillis }
        if (key == summaryKey) return
        summaryKey = key
        scope.launch {
            val summaries = mutableMapOf<String, ProfileServerSummary>()
            val fingerprints = mutableMapOf<String, Map<String, String>>()
            var complete = true
            for (profile in profiles) {
                val json = runCatching { store.read(profile.id).json }.getOrNull()
                if (json == null) {
                    complete = false
                    continue
                }
                val summary = withContext(defaultDispatcher) { ProfileServerCatalog.summarize(json) }
                if (summary.groups.isNotEmpty()) summaries[profile.id] = summary
                fingerprints[profile.id] = fingerprintsOf(summary)
            }
            if (summaryKey != key) return@launch
            state.update { it.copy(serverSummaries = summaries) }
            // Пинг исчезнувших профилей и серверов удаляется; при сбое чтения
            // любого профиля ничего не трогаем, чтобы не потерять его историю.
            if (complete) serverLatencyStore?.retain(fingerprints)
            requestCountries(summaries)
        }
    }

    fun openPicker(profileId: String) = operations.run {
        val stored = store.read(profileId)
        val summary = withContext(defaultDispatcher) { ProfileServerCatalog.summarize(stored.json) }
        if (summary.groups.isEmpty()) {
            throw ImportException("В профиле нет группы серверов sing-box (selector).")
        }
        state.update {
            it.copy(
                serverPicker = ProfileServerPickerState(
                    profileId = profileId,
                    profileName = stored.metadata.name,
                    groups = summary.groups,
                    liveSwitch = vpn.isConnectedTo(profileId),
                ),
                serverSummaries = it.serverSummaries + (profileId to summary),
                message = null,
            )
        }
    }

    fun dismissPicker() {
        state.update { it.copy(serverPicker = null) }
    }

    fun selectServer(groupTag: String, serverTag: String) = operations.run {
        val picker = state.value.serverPicker ?: return@run
        val profileId = picker.profileId
        val live = vpn.isConnectedTo(profileId)
        if (live) {
            // Ядро само проверит, сохранит профиль и переключит outbound без разрыва туннеля.
            vpn.selectOutbound(profileId, groupTag, serverTag)
        } else {
            val stored = store.read(profileId)
            val updated = withContext(defaultDispatcher) {
                ConfigAnalyzer.selectServer(stored.json, groupTag, serverTag)
            }
            store.update(profileId, updated)
        }
        state.update { current ->
            val pickerNow = current.serverPicker?.takeIf { it.profileId == profileId }
                ?: return@update current
            val groups = pickerNow.groups.map { group ->
                if (group.tag == groupTag) group.copy(selected = serverTag) else group
            }
            current.copy(
                serverPicker = pickerNow.copy(groups = groups, liveSwitch = live),
                serverSummaries = current.serverSummaries + (profileId to ProfileServerSummary(groups)),
            )
        }
        val label = SecretRedactor.redactInline(serverTag)
        operations.showMessage(
            if (live) {
                "Сервер $label переключается без перезапуска VPN."
            } else {
                "Сервер $label выбран. VPN не запускался."
            },
        )
    }

    /** Страны серверов: запускается при старте и когда профили изменились (импорт, обновление подписки). */
    private fun requestCountries(summaries: Map<String, ProfileServerSummary>) {
        val countries = serverCountryStore ?: return
        val activeId = state.value.settings.activeProfileId
        countries.request(
            hosts = summaries.values.flatMapTo(mutableSetOf()) { hostKeys(it) },
            priority = hostKeys(summaries[activeId]),
        )
    }

    internal companion object {
        fun hostKeys(summary: ProfileServerSummary?): Set<String> = summary?.groups.orEmpty()
            .flatMap { group -> group.options.mapNotNull { endpointHostKey(it.endpoint) } }
            .toSet()

        /** Тег сервера -> отпечаток (тип и адрес) для привязки сохранённого пинга. */
        fun fingerprintsOf(summary: ProfileServerSummary): Map<String, String> = summary.groups
            .flatMap(ProfileServerGroup::options)
            .associate { it.tag to ServerLatencyFingerprint.of(it.type, it.endpoint) }
    }
}
