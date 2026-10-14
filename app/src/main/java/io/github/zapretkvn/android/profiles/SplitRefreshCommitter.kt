package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.SubscriptionBinding
import io.github.zapretkvn.android.importer.SubscriptionSourceStore

/** Результат записи плана обновления группы подписки. */
internal data class SplitRefreshCommit(
    val createdIds: Set<String>,
    val survivingIds: Set<String>,
)

/**
 * Записывает план [SplitRefreshPlan]: привязки подписки, затем профили одной транзакцией.
 * Если транзакция профилей не удалась, привязки возвращаются к прежним.
 */
internal class SplitRefreshCommitter(
    private val store: ProfileStore,
    private val subscriptionSourceStore: SubscriptionSourceStore,
    private val removeBootstrap: suspend (String) -> Unit,
    private val newId: () -> String,
) {
    suspend fun commit(plan: SplitRefreshPlan): SplitRefreshCommit {
        val creates = plan.additions.map { addition ->
            ProfileBatchCreate(newId(), addition.name, addition.json, ProfileSource.Link)
        }
        val newBindings = bindingsFor(plan, creates)

        // Source metadata is committed first and restored if the profile transaction fails.
        subscriptionSourceStore.replaceSplitGroup(plan.groupId, newBindings)
        try {
            store.applyBatch(plan.updatedJson, creates, plan.removedProfileIds)
        } catch (error: Throwable) {
            runCatching { subscriptionSourceStore.replaceSplitGroup(plan.groupId, plan.previousBindings) }
            throw error
        }
        plan.removedProfileIds.forEach { removeBootstrap(it) }
        return SplitRefreshCommit(
            createdIds = creates.map(ProfileBatchCreate::id).toSet(),
            survivingIds = plan.memberKeysByProfileId.keys,
        )
    }

    companion object {
        /** Привязки группы после обновления: оставшиеся профили и профили из [creates] (по порядку добавлений). */
        fun bindingsFor(
            plan: SplitRefreshPlan,
            creates: List<ProfileBatchCreate>,
        ): Map<String, SubscriptionBinding> {
            fun binding(memberKey: String) = SubscriptionBinding(
                source = plan.source,
                splitGroupId = plan.groupId,
                splitMemberKey = memberKey,
                splitBaseName = plan.baseName,
                knownMemberKeys = plan.knownMemberKeys,
            )
            return buildMap {
                plan.memberKeysByProfileId.forEach { (profileId, memberKey) ->
                    put(profileId, binding(memberKey))
                }
                creates.zip(plan.additions).forEach { (create, addition) ->
                    put(create.id, binding(addition.memberKey))
                }
            }
        }
    }
}
