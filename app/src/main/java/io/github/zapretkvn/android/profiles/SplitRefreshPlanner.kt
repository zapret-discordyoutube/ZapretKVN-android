package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.SubscriptionBinding
import io.github.zapretkvn.android.importer.SubscriptionSource

/**
 * Сопоставляет свежий список серверов подписки с уже созданными профилями раздельной подписки:
 * какие профили обновить, какие добавить, какие удалить. Ничего не пишет на диск.
 *
 * [validate] бросает исключение, если ядро не принимает JSON; [buildNewProfile] собирает
 * JSON профиля для нового сервера (с пресетом маршрутизации).
 */
internal class SplitRefreshPlanner(
    private val validate: suspend (String) -> Unit,
    private val buildNewProfile: suspend (ManagedServer) -> String,
) {
    /**
     * @param group привязки всей группы (id профиля -> привязка), не пустая.
     * @param storedJson текущий JSON каждого профиля группы.
     */
    suspend fun plan(
        groupId: String,
        subscription: SubscriptionSource,
        servers: List<ManagedServer>,
        group: Map<String, SubscriptionBinding>,
        storedJson: Map<String, String>,
        connectedProfileId: String?,
    ): SplitRefreshPlan {
        val freshKeys = ManagedProfileFactory.stableMemberKeys(servers)
        val directOwners = group.entries.groupBy { checkNotNull(it.value.splitMemberKey) }
        val existingByKey = directOwners.mapNotNull { (key, owners) ->
            owners.singleOrNull()?.let { key to it.key }
        }.toMap()
        val migratedAliases = group.keys.associateWith { id ->
            ManagedProfileFactory.migratedHysteria2MemberAliases(checkNotNull(storedJson[id]))
        }
        val credentialOwners = migratedAliases.entries
            .mapNotNull { (id, aliases) -> aliases?.credentialKey?.let { it to id } }
            .groupBy({ it.first }, { it.second })
        val existingAliasOwners = linkedMapOf<String, MutableSet<String>>()
        migratedAliases.forEach { (id, aliases) ->
            if (aliases == null) return@forEach
            aliases.aliases.forEach { alias ->
                existingAliasOwners.getOrPut(alias, ::linkedSetOf).add(id)
            }
            if (credentialOwners[aliases.credentialKey]?.size == 1) {
                existingAliasOwners.getOrPut(aliases.credentialKey, ::linkedSetOf).add(id)
            }
        }
        val existingByAlias = existingAliasOwners.mapNotNull { (alias, owners) ->
            owners.singleOrNull()?.let { alias to it }
        }.toMap()
        val freshAliases = servers.map { server ->
            ManagedProfileFactory.hysteria2ReconciliationAliases(server)?.let { aliases ->
                aliases.aliases + aliases.credentialKey
            }.orEmpty()
        }
        val freshAliasOwners = buildMap<String, MutableSet<Int>> {
            freshAliases.forEachIndexed { index, aliases ->
                aliases.forEach { alias -> getOrPut(alias, ::linkedSetOf).add(index) }
            }
        }
        val oldKnownKeys = group.values.flatMap { it.knownMemberKeys }.toSet()
            .ifEmpty { existingByKey.keys }
        val baseName = group.values.firstNotNullOfOrNull(SubscriptionBinding::splitBaseName)
            ?.takeIf(String::isNotBlank)
            ?: "Подписка"
        val allNames = SplitProfileNaming.names(servers.map(ManagedServer::displayName), baseName)

        val updates = linkedMapOf<String, String>()
        val additions = mutableListOf<SplitRefreshAddition>()
        val retainedMemberKeys = linkedMapOf<String, String>()
        val claimedExistingIds = mutableSetOf<String>()
        freshKeys.forEachIndexed { index, key ->
            val server = servers[index]
            val existingId = existingByKey[key]
                ?.takeIf(claimedExistingIds::add)
                ?: freshAliases[index].asSequence()
                    .filter { alias -> freshAliasOwners[alias]?.size == 1 }
                    .mapNotNull(existingByAlias::get)
                    .firstOrNull(claimedExistingIds::add)
            when {
                existingId != null -> {
                    val json = checkNotNull(storedJson[existingId])
                    val refreshed = ManagedProfileEditor.refreshServers(json, listOf(server)).json
                    validate(refreshed)
                    updates[existingId] = refreshed
                    retainedMemberKeys[existingId] = key
                }
                key !in oldKnownKeys -> {
                    val json = buildNewProfile(server)
                    validate(json)
                    additions += SplitRefreshAddition(allNames[index], json, key)
                }
                // The key was known but has no bound profile: the user deleted it intentionally.
                else -> Unit
            }
        }
        val removedIds = group.keys - retainedMemberKeys.keys
        return SplitRefreshPlan(
            groupId = groupId,
            source = subscription,
            baseName = baseName,
            previousBindings = group,
            updatedJson = updates,
            additions = additions,
            removedProfileIds = removedIds,
            knownMemberKeys = freshKeys.toSet(),
            memberKeysByProfileId = retainedMemberKeys,
            connectedProfileId = connectedProfileId?.takeIf(group::containsKey),
        )
    }
}

internal fun SplitRefreshPlan.summary(): SplitRefreshSummary = SplitRefreshSummary(
    updated = updatedJson.size,
    added = additions.size,
    removed = removedProfileIds.size,
    connectedProfileRemoved = connectedProfileId in removedProfileIds,
)

/** JSON для поля `preparedJson` предпросмотра: подписка даёт хотя бы один сервер, поэтому он всегда есть. */
internal fun SplitRefreshPlan.previewJson(servers: List<ManagedServer>): String =
    updatedJson.values.firstOrNull()
        ?: additions.firstOrNull()?.json
        ?: ManagedProfileFactory.single(servers.first())
