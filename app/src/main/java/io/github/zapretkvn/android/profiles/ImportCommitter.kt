package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportException
import io.github.zapretkvn.android.importer.SubscriptionBinding
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import io.github.zapretkvn.android.routing.InstalledRuleSets
import io.github.zapretkvn.android.routing.RoutingConfigEditor
import io.github.zapretkvn.android.routing.RoutingPreset
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun newProfileId(): String = UUID.randomUUID().toString().replace("-", "")

/** Записывает подтверждённый предпросмотр импорта: новые профили, добавление сервера, привязки подписки. */
internal class ImportCommitter(
    private val store: ProfileStore,
    private val subscriptionSourceStore: SubscriptionSourceStore,
    private val settings: ProfilesSettingsPort,
    private val installRuleSets: suspend () -> InstalledRuleSets,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val newId: () -> String = ::newProfileId,
) {
    /** Один профиль из предпросмотра. Первый импорт становится активным профилем. */
    suspend fun create(
        name: String,
        pending: ImportPreviewState,
        hasActiveProfile: Boolean,
    ): ProfileMetadata {
        val metadata = store.create(name, pending.preparedJson, pending.candidate.source)
        pending.source?.let { subscriptionSourceStore.put(metadata.id, it) }
        if (!hasActiveProfile) {
            settings.setActiveProfile(metadata.id)
            settings.setDnsMode(
                if (pending.candidate is ImportCandidate.Managed) DnsMode.Automatic else DnsMode.FromJson,
            )
        }
        return metadata
    }

    /**
     * Раскладывает подписку по одному профилю на сервер. Ни один профиль не остаётся,
     * пока ядро не приняло все получившиеся JSON.
     */
    suspend fun createPerServer(
        baseName: String,
        pending: ImportPreviewState,
        hasActiveProfile: Boolean,
    ): List<ProfileMetadata> {
        val servers = (pending.candidate as? ImportCandidate.Managed)?.servers
            ?.takeIf { it.size > 1 }
            ?: throw ImportException("Разделять можно только подписку с несколькими серверами.")
        if (servers.size > MAX_SPLIT_PROFILES) {
            throw ImportException(
                "Разделение доступно максимум для $MAX_SPLIT_PROFILES серверов; " +
                    "для больших подписок используйте выбор сервера внутри профиля.",
            )
        }
        val installed = installRuleSets()
        val prepared = withContext(defaultDispatcher) {
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
            pending.source?.let { source ->
                val groupId = newId()
                val keys = ManagedProfileFactory.stableMemberKeys(servers)
                val knownKeys = keys.toSet()
                val bindings = created.mapIndexed { index, profile ->
                    profile.id to SubscriptionBinding(
                        source = source,
                        splitGroupId = groupId,
                        splitMemberKey = keys[index],
                        splitBaseName = baseName.trim().ifBlank { "Подписка" },
                        knownMemberKeys = knownKeys,
                    )
                }.toMap()
                subscriptionSourceStore.replaceSplitGroup(groupId, bindings)
            }
        } catch (error: Throwable) {
            created.forEach { profile -> runCatching { store.delete(profile.id) } }
            throw error
        }
        if (!hasActiveProfile) {
            settings.setActiveProfile(created.first().id)
            settings.setDnsMode(DnsMode.Automatic)
        }
        return created
    }

    /** Дописывает единственный сервер предпросмотра в управляемый профиль; возвращает его имя. */
    suspend fun append(targetProfileId: String, pending: ImportPreviewState): String {
        val server = (pending.candidate as? ImportCandidate.Managed)?.servers?.singleOrNull()
            ?: throw ImportException("Добавить можно только одну серверную ссылку.")
        val target = store.read(targetProfileId)
        val update = ManagedProfileEditor.appendServer(target.json, server)
        store.update(targetProfileId, update.json)
        return target.metadata.name
    }
}
