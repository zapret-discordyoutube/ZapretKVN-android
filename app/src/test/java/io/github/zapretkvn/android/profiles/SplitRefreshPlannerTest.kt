package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.SubscriptionBinding
import io.github.zapretkvn.android.importer.SubscriptionSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SplitRefreshPlannerTest {
    private val source = SubscriptionSource("https://sub.example/list")
    private val one = vlessLink("one.example", "One", '1')
    private val two = vlessLink("two.example", "Two", '2')
    private val three = vlessLink("three.example", "Three", '3')

    private val validated = mutableListOf<String>()
    private val planner = SplitRefreshPlanner(
        validate = { validated += it },
        buildNewProfile = { ManagedProfileFactory.single(it) },
    )

    private class Group(
        val bindings: Map<String, SubscriptionBinding>,
        val json: Map<String, String>,
    )

    private fun group(
        servers: List<ManagedServer>,
        known: List<ManagedServer> = servers,
        bound: List<Int> = servers.indices.toList(),
    ): Group {
        val keys = ManagedProfileFactory.stableMemberKeys(servers)
        val knownKeys = ManagedProfileFactory.stableMemberKeys(known).toSet()
        val bindings = bound.associate { index ->
            "p${index + 1}" to SubscriptionBinding(
                source = source,
                splitGroupId = "g",
                splitMemberKey = keys[index],
                splitBaseName = "База",
                knownMemberKeys = knownKeys,
            )
        }
        val json = bound.associate { index -> "p${index + 1}" to ManagedProfileFactory.single(servers[index]) }
        return Group(bindings, json)
    }

    private fun plan(fresh: List<ManagedServer>, group: Group, connected: String? = null) = runBlocking {
        planner.plan("g", source, fresh, group.bindings, group.json, connected)
    }

    @Test
    fun `unchanged subscription updates every member and adds or removes nothing`() {
        val servers = managedServers(one, two)
        val plan = plan(servers, group(servers))

        assertEquals(setOf("p1", "p2"), plan.updatedJson.keys)
        assertTrue(plan.additions.isEmpty())
        assertTrue(plan.removedProfileIds.isEmpty())
        assertEquals("База", plan.baseName)
        assertEquals(SplitRefreshSummary(2, 0, 0, false), plan.summary())
    }

    @Test
    fun `new server is added with its readable name and recorded as known`() {
        val old = managedServers(one, two)
        val fresh = managedServers(one, two, three)
        val plan = plan(fresh, group(old))

        val addition = plan.additions.single()
        assertEquals(ManagedProfileFactory.stableMemberKeys(fresh)[2], addition.memberKey)
        assertTrue(addition.name.contains("Three"))
        assertEquals(ManagedProfileFactory.stableMemberKeys(fresh).toSet(), plan.knownMemberKeys)
        assertEquals(1, validated.count { it == addition.json })
    }

    @Test
    fun `vanished server removes its profile`() {
        val old = managedServers(one, two)
        val plan = plan(managedServers(one), group(old))

        assertEquals(setOf("p2"), plan.removedProfileIds)
        assertEquals(setOf("p1"), plan.memberKeysByProfileId.keys)
        assertEquals(SplitRefreshSummary(1, 0, 1, false), plan.summary())
    }

    @Test
    fun `server deleted by the user is not brought back`() {
        val all = managedServers(one, two)
        // p2 удалён пользователем: привязки нет, но ключ в списке известных.
        val plan = plan(all, group(all, known = all, bound = listOf(0)))

        assertTrue(plan.additions.isEmpty())
        assertEquals(setOf("p1"), plan.updatedJson.keys)
        assertTrue(plan.removedProfileIds.isEmpty())
    }

    @Test
    fun `connected profile is reported only when it belongs to the group`() {
        val servers = managedServers(one, two)
        val removedConnected = plan(managedServers(one), group(servers), connected = "p2")
        val foreign = plan(servers, group(servers), connected = "foreign")

        assertEquals("p2", removedConnected.connectedProfileId)
        assertTrue(removedConnected.summary().connectedProfileRemoved)
        assertNull(foreign.connectedProfileId)
        assertFalse(foreign.summary().connectedProfileRemoved)
    }

    @Test
    fun `core rejection aborts planning`() {
        val servers = managedServers(one)
        val rejecting = SplitRefreshPlanner(
            validate = { error("ядро отказало") },
            buildNewProfile = { ManagedProfileFactory.single(it) },
        )
        val group = group(servers)

        try {
            runBlocking { rejecting.plan("g", source, servers, group.bindings, group.json, null) }
            fail("planning must fail")
        } catch (error: IllegalStateException) {
            assertEquals("ядро отказало", error.message)
        }
    }

    @Test
    fun `preview json falls back through updates additions and a fresh profile`() {
        val old = managedServers(one)
        val fresh = managedServers(two)
        val onlyAdded = plan(fresh, group(old))

        assertTrue(onlyAdded.updatedJson.isEmpty())
        assertEquals(onlyAdded.additions.first().json, onlyAdded.previewJson(fresh))
    }
}
