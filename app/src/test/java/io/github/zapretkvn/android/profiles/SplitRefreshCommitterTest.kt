package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.SubscriptionBinding
import io.github.zapretkvn.android.importer.SubscriptionSource
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SplitRefreshCommitterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val source = SubscriptionSource("https://sub.example/list")

    private fun binding(group: String, key: String, known: Set<String>) = SubscriptionBinding(
        source = source,
        splitGroupId = group,
        splitMemberKey = key,
        splitBaseName = "База",
        knownMemberKeys = known,
    )

    private fun plan(
        previous: Map<String, SubscriptionBinding>,
        updated: Map<String, String>,
        additions: List<SplitRefreshAddition>,
        removed: Set<String>,
        retained: Map<String, String>,
        known: Set<String>,
    ) = SplitRefreshPlan(
        groupId = "g",
        source = source,
        baseName = "База",
        previousBindings = previous,
        updatedJson = updated,
        additions = additions,
        removedProfileIds = removed,
        knownMemberKeys = known,
        memberKeysByProfileId = retained,
        connectedProfileId = null,
    )

    @Test
    fun `bindings cover retained profiles and created ones in order`() {
        val plan = plan(
            previous = emptyMap(),
            updated = emptyMap(),
            additions = listOf(
                SplitRefreshAddition("A", "{}", "key-a"),
                SplitRefreshAddition("B", "{}", "key-b"),
            ),
            removed = emptySet(),
            retained = mapOf("old" to "key-old"),
            known = setOf("key-old", "key-a", "key-b"),
        )
        val creates = listOf(
            ProfileBatchCreate("id-a", "A", "{}", ProfileSource.Link),
            ProfileBatchCreate("id-b", "B", "{}", ProfileSource.Link),
        )

        val bindings = SplitRefreshCommitter.bindingsFor(plan, creates)

        assertEquals(setOf("old", "id-a", "id-b"), bindings.keys)
        assertEquals("key-old", bindings.getValue("old").splitMemberKey)
        assertEquals("key-b", bindings.getValue("id-b").splitMemberKey)
        assertTrue(bindings.values.all { it.splitGroupId == "g" && it.splitBaseName == "База" })
        assertTrue(bindings.values.all { it.knownMemberKeys == plan.knownMemberKeys })
    }

    @Test
    fun `commit applies the batch updates bindings and cleans removed profiles`() = runBlocking {
        val store = ProfileStore(
            temporaryFolder.newFolder("profiles"), JsonSyntaxValidator, JvmAtomicProfileWriter(),
        )
        store.initialize()
        val keep = store.create("keep", OLD_JSON, ProfileSource.Link)
        val drop = store.create("drop", OLD_JSON, ProfileSource.Link)
        val sources = SubscriptionSourceStore(temporaryFolder.newFolder("subs"), JvmAtomicProfileWriter())
        val previous = mapOf(
            keep.id to binding("g", "k1", setOf("k1", "k2")),
            drop.id to binding("g", "k2", setOf("k1", "k2")),
        )
        sources.replaceSplitGroup("g", previous)
        val removedBootstrap = mutableListOf<String>()
        val committer = SplitRefreshCommitter(
            store, sources, { removedBootstrap += it }, { "0123456789abcdef0123456789abcdef" },
        )

        val result = committer.commit(
            plan(
                previous = previous,
                updated = mapOf(keep.id to NEW_JSON),
                additions = listOf(SplitRefreshAddition("new", OLD_JSON, "k3")),
                removed = setOf(drop.id),
                retained = mapOf(keep.id to "k1"),
                known = setOf("k1", "k3"),
            ),
        )

        assertEquals(setOf(keep.id), result.survivingIds)
        assertEquals(setOf("0123456789abcdef0123456789abcdef"), result.createdIds)
        assertEquals(NEW_JSON, store.read(keep.id).json)
        assertEquals(listOf(drop.id), removedBootstrap)
        assertNull(sources.binding(drop.id))
        assertEquals("k3", sources.binding("0123456789abcdef0123456789abcdef")?.splitMemberKey)
        assertEquals(setOf(keep.id, "0123456789abcdef0123456789abcdef"), store.profiles.value.map { it.id }.toSet())
    }

    @Test
    fun `failed profile transaction restores the previous bindings`() = runBlocking {
        val store = ProfileStore(
            temporaryFolder.newFolder("profiles"), JsonSyntaxValidator, JvmAtomicProfileWriter(),
        )
        store.initialize()
        val keep = store.create("keep", OLD_JSON, ProfileSource.Link)
        val sources = SubscriptionSourceStore(File(temporaryFolder.root, "subs2"), JvmAtomicProfileWriter())
        val previous = mapOf(keep.id to binding("g", "k1", setOf("k1")))
        sources.replaceSplitGroup("g", previous)
        val committer = SplitRefreshCommitter(store, sources, {}, ::newProfileId)

        val failure = runCatching {
            committer.commit(
                plan(
                    previous = previous,
                    updated = mapOf(keep.id to "{broken"),
                    additions = emptyList(),
                    removed = emptySet(),
                    retained = mapOf(keep.id to "k1"),
                    known = setOf("k1", "k9"),
                ),
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(setOf("k1"), sources.binding(keep.id)?.knownMemberKeys)
        assertEquals(OLD_JSON, store.read(keep.id).json)
    }

    private companion object {
        const val OLD_JSON = """{"outbounds":[{"type":"direct","tag":"old"}]}"""
        const val NEW_JSON = """{"outbounds":[{"type":"direct","tag":"new"}]}"""
    }
}
