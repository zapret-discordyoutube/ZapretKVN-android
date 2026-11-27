package io.github.zapretkvn.android.ui.servers

import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Папки списка серверов по уровням пользователя на настоящих ключах владельца (1500 ключей, адреса замаскированы). */
class ServerGroupingTest {
    private val now = 1_000_000_000L

    /** Настоящие имена и протоколы из `real_server_keys.tsv` (та же выборка, что у Windows-тестов). */
    private val real: List<RuntimeOutboundItem> by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("real_server_keys.tsv")).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        text.split('\n').filter { it.isNotEmpty() }.map { line ->
            val (scheme, name) = line.split('\t', limit = 2)
            RuntimeOutboundItem(name, scheme, null)
        }
    }

    private fun rows(): List<ServerRowUi> {
        val group = RuntimeSelectorGroup("zapret-proxy", "selector", real.first().tag, true, real, primary = true)
        return liveServerGroups(listOf(group), false, now).single().rows
    }

    private fun flat(folders: List<ServerFolder>): List<ServerFolder> = folders.flatMap { listOf(it) + flat(it.children) }

    @Test
    fun `every real key is in exactly one leaf folder whatever the order of levels`() {
        val rows = rows()
        for (levels in listOf(
            listOf(GroupLevel.Country, GroupLevel.Server, GroupLevel.Protocol),
            listOf(GroupLevel.Protocol, GroupLevel.Country),
            listOf(GroupLevel.Server),
        )) {
            val folders = buildFolders("zapret-proxy", rows, levels)
            val leaves = flat(folders).flatMap { it.leaves }
            // names repeat in real subscriptions, so rows are compared by count, not by key
            assertEquals(levels.toString(), rows.size, leaves.size)
        }
    }

    @Test
    fun `a folder counts the keys below it`() {
        val folders = buildFolders(
            "zapret-proxy", rows(), listOf(GroupLevel.Country, GroupLevel.Server, GroupLevel.Protocol),
        )
        for (folder in flat(folders)) {
            val below = if (folder.children.isEmpty()) folder.leaves.size else folder.children.sumOf { it.rows.size }
            assertEquals(folder.key, folder.rows.size, below)
        }
        assertEquals(setOf(0, 1, 2), flat(folders).map { it.depth }.toSet())
    }

    @Test
    fun `the server level follows the provider's own names`() {
        val servers = buildFolders("zapret-proxy", rows(), listOf(GroupLevel.Server))
        // Windows groups the same 1500 keys into 40 servers (tests/test_nodes_grouping_levels.py, same fixture).
        assertEquals(40, servers.size)
    }

    @Test
    fun `NaiveProxy keys get their own protocol folder`() {
        val items = real.take(30) + RuntimeOutboundItem("🇩🇪 Edge · NaiveProxy", "naive", null)
        val group = RuntimeSelectorGroup("zapret-proxy", "selector", items.first().tag, true, items, primary = true)
        val rows = liveServerGroups(listOf(group), false, now).single().rows
        val folders = buildFolders("zapret-proxy", rows, listOf(GroupLevel.Protocol))
        assertTrue(folders.map { it.title }.toString(), "NaiveProxy" in folders.map { it.title })
        assertEquals(1, folders.single { it.title == "NaiveProxy" }.rows.size)
    }

    @Test
    fun `the same server under two countries is two folders with their own keys`() {
        val items = listOf(
            RuntimeOutboundItem("🇩🇪 Alpha · VLESS", "vless", null),
            RuntimeOutboundItem("🇳🇱 Alpha · VLESS", "vless", null),
        )
        val group = RuntimeSelectorGroup("g", "selector", items.first().tag, true, items, primary = true)
        val rows = liveServerGroups(listOf(group), false, now).single().rows
        val keys = flat(buildFolders("g", rows, listOf(GroupLevel.Country, GroupLevel.Server))).filter { it.depth == 1 }.map { it.key }
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun `keys without a country go to the last folder`() {
        val items = listOf(
            RuntimeOutboundItem("proxy", "vless", null),
            RuntimeOutboundItem("🇩🇪 Alpha", "vless", null),
        )
        val group = RuntimeSelectorGroup("g", "selector", items.first().tag, true, items, primary = true)
        val rows = liveServerGroups(listOf(group), false, now).single().rows
        val folders = buildFolders("g", rows, listOf(GroupLevel.Country))
        assertEquals(OTHER_SECTION_TITLE, folders.last().title)
        assertTrue(folders.last().other)
        assertFalse(folders.first().other)
    }

    @Test
    fun `modes are read and written the same way and a bad mode is auto`() {
        assertEquals(null, ServerGrouping.levels("auto"))
        assertEquals(emptyList<GroupLevel>(), ServerGrouping.levels("none"))
        assertEquals(listOf(GroupLevel.Country, GroupLevel.Server), ServerGrouping.levels("country+server"))
        for (bad in listOf("", "nonsense", "country+country", "country+server+protocol+server", "country+bogus")) {
            assertEquals(bad, null, ServerGrouping.levels(bad))
        }
        assertEquals("country+protocol", ServerGrouping.modeOf(listOf(GroupLevel.Country, null, GroupLevel.Protocol)))
        assertEquals("server", ServerGrouping.modeOf(listOf(GroupLevel.Server, GroupLevel.Server, null)))
        assertEquals("none", ServerGrouping.modeOf(listOf(null, null, null)))
        for ((mode, _) in ServerGrouping.presets) assertEquals(mode, ServerGrouping.normalize(mode))
    }

    @Test
    fun `the list nests folders and indents rows, a collapsed folder hides what is below`() {
        val rows = rows().take(300)
        val group = RuntimeSelectorGroup("zapret-proxy", "selector", rows.first().tag, true, real.take(300), primary = true)
        val groups = liveServerGroups(listOf(group), false, now)
        val levels = listOf(GroupLevel.Country, GroupLevel.Server)
        val model = buildServerList(groups, null, ServerFilter(), grouping = levels)
        val sections = model.items.filterIsInstance<ServerListItem.Section>()
        assertEquals(setOf(0, 1), sections.map { it.depth }.toSet())
        // by default only the folders on the selected server's path are open
        val opened = sections.filter { it.expanded }
        assertTrue(opened.isNotEmpty())
        assertTrue(opened.size < sections.size)
        // closing the top folder removes everything below it
        val top = sections.first { it.depth == 0 && it.expanded }
        val closed = buildServerList(
            groups, null, ServerFilter(), grouping = levels, sections = SectionState().with(top.section.key, false),
        )
        assertTrue(closed.items.size < model.items.size)
        assertFalse(closed.items.any { it is ServerListItem.Section && it.section.key.startsWith(top.section.key + ":") })
    }

    @Test
    fun `no grouping is a flat list and a search ignores the folders`() {
        val group = RuntimeSelectorGroup("zapret-proxy", "selector", real.first().tag, true, real.take(40), primary = true)
        val groups = liveServerGroups(listOf(group), false, now)
        val flat = buildServerList(groups, null, ServerFilter(), grouping = emptyList())
        assertTrue(flat.items.none { it is ServerListItem.Section })
        val search = buildServerList(
            groups, null, ServerFilter(query = "vless"), grouping = listOf(GroupLevel.Country),
        )
        assertTrue(search.items.none { it is ServerListItem.Section })
    }
}
