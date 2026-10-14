package io.github.zapretkvn.android.ui.servers

import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencySample
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerListTest {
    private val now = 1_000_000_000L
    private val nl = "🇳🇱"
    private val de = "🇩🇪"

    private fun ok(millis: Int) = LatencyProbeState.Success(LatencySample(millis, now - 1_000L, null))

    private fun items(count: Int, prefix: String = "s", flag: (Int) -> String = { "" }) =
        (1..count).map { i ->
            RuntimeOutboundItem("${flag(i)} $prefix$i".trim(), "vless", "h$i.example:443")
        }

    private fun rtGroup(
        items: List<RuntimeOutboundItem>,
        tag: String = "zapret-proxy",
        type: String = "selector",
        selectable: Boolean = true,
        primary: Boolean = true,
        selected: String = items.first().tag,
    ) = RuntimeSelectorGroup(tag, type, selected, selectable, items, primary = primary)

    private fun groups(vararg g: RuntimeSelectorGroup) = liveServerGroups(g.toList(), false, now)

    private fun header() = ProfileHeaderUi("Профиль", "x", null, 0, "Обновлено только что")

    @Test
    fun smallProfileHasNoToolsAndShowsHeaderGroupAndRows() {
        val model = buildServerList(groups(rtGroup(items(3))), header(), ServerFilter())
        assertFalse(model.showTools)
        assertEquals(
            listOf("profile", "group-zapret-proxy", "zapret-proxy/s1", "zapret-proxy/s2", "zapret-proxy/s3"),
            model.items.map { it.key },
        )
        assertEquals(2, model.selectedIndex) // первый сервер выбран
        val positions = model.items.filterIsInstance<ServerListItem.Server>().map { it.position }
        assertEquals(listOf(RowPosition.First, RowPosition.Middle, RowPosition.Last), positions)
    }

    @Test
    fun manyServersShowStickyToolsAfterHeader() {
        val model = buildServerList(groups(rtGroup(items(9))), header(), ServerFilter())
        assertTrue(model.showTools)
        assertEquals(listOf("profile", "tools"), model.items.take(2).map { it.key })
    }

    @Test
    fun singleServerIsShownWithoutGroupCard() {
        val model = buildServerList(groups(rtGroup(items(1))), header(), ServerFilter())
        assertEquals(listOf("profile", "single", "zapret-proxy/s1"), model.items.map { it.key })
        assertEquals(RowPosition.Only, (model.items.last() as ServerListItem.Server).position)
    }

    @Test
    fun searchAndChipsFilterRowsAndEmptyResultIsReported() {
        val src = items(10, "srv") { if (it <= 4) nl else de }
        val g = groups(rtGroup(src))
        val byQuery = buildServerList(g, null, ServerFilter(query = "srv2"))
        assertEquals(listOf("srv2"), servers(byQuery).map { it.title })

        val byCountry = buildServerList(g, null, ServerFilter(chip = ServerChip.Country("NL")))
        assertEquals(4, servers(byCountry).size)

        val none = buildServerList(g, null, ServerFilter(query = "нет такого"))
        assertTrue(none.items.last() is ServerListItem.EmptyFilter)
        assertTrue(servers(none).isEmpty())
    }

    @Test
    fun fastChipKeepsFastAndNormalOnly() {
        val base = items(9)
        val src = base.mapIndexed { i, item ->
            when (i) {
                0 -> item.copy(relay = ok(80))
                1 -> item.copy(relay = ok(250))
                2 -> item.copy(relay = ok(900))
                3 -> item.copy(relay = LatencyProbeState.Failed(LatencyFailure.NoResponse))
                else -> item
            }
        }
        val g = groups(rtGroup(src))
        val chips = serverChips(g)
        assertEquals("Все 9", chips.primary[0].label)
        assertEquals("⚡ Быстрые 2", chips.primary[1].label)
        val model = buildServerList(g, null, ServerFilter(chip = ServerChip.Fast))
        assertEquals(2, servers(model).size)
    }

    @Test
    fun countryChipsAreSortedByCountAndOverflowGoesToMore() {
        val flagOf = listOf(
            "🇦🇦", "🇧🇧", "🇨🇨",
            "🇩🇩", "🇪🇪", "🇫🇫",
            "🇬🇬", "🇭🇭",
        )
        // 8 стран; у последней два сервера, поэтому она идёт первой среди стран
        val src = flagOf.mapIndexed { i, f -> RuntimeOutboundItem("$f s$i", "vless", null) } +
            RuntimeOutboundItem("${flagOf.last()} extra", "vless", null)
        val chips = serverChips(groups(rtGroup(src)))
        assertEquals("Все 9", chips.primary[0].label)
        assertEquals(ServerChip.Country("HH"), chips.primary[1].chip)
        assertEquals(1 + 6, chips.primary.size) // «Все» + 6 стран
        assertEquals(2, chips.more.size)
    }

    @Test
    fun frozenOrderDoesNotChangeWhenLatencyChangesLater() {
        val first = groups(
            rtGroup(
                listOf(
                    RuntimeOutboundItem("a", "vless", null, relay = ok(300)),
                    RuntimeOutboundItem("b", "vless", null, relay = ok(50)),
                    RuntimeOutboundItem("c", "vless", null),
                ),
            ),
        )
        val order = frozenOrder(first, ServerSort.Fastest)
        assertEquals(listOf("b", "a", "c"), order)

        // позже c оказался самым быстрым: порядок остаётся прежним
        val later = groups(
            rtGroup(
                listOf(
                    RuntimeOutboundItem("a", "vless", null, relay = ok(300)),
                    RuntimeOutboundItem("b", "vless", null, relay = ok(50)),
                    RuntimeOutboundItem("c", "vless", null, relay = ok(10)),
                ),
            ),
        )
        val model = buildServerList(later, null, ServerFilter(), order = order)
        assertEquals(listOf("b", "a", "c"), servers(model).map { it.tag })
    }

    @Test
    fun nameAndSubscriptionOrders() {
        val g = groups(rtGroup(listOf(RuntimeOutboundItem("Beta", "vless", null), RuntimeOutboundItem("alpha", "vless", null))))
        assertEquals(listOf("alpha", "Beta"), frozenOrder(g, ServerSort.Name))
        assertTrue(frozenOrder(g, ServerSort.Subscription).isEmpty())
    }

    @Test
    fun secondaryGroupsAreCollapsedByDefaultAndToggleWorks() {
        val primary = rtGroup(items(3, "p"), tag = "main")
        val secondary = rtGroup(items(2, "q"), tag = "extra", primary = false)
        val g = groups(primary, secondary)

        val collapsed = buildServerList(g, null, ServerFilter())
        val groupItems = collapsed.items.filterIsInstance<ServerListItem.Group>()
        assertTrue(groupItems[0].expanded)
        assertFalse(groupItems[1].expanded)
        assertTrue(groupItems.all { it.collapsible })
        assertEquals(3, servers(collapsed).size)

        val toggled = buildServerList(g, null, ServerFilter(), toggledGroups = setOf("extra"))
        assertEquals(5, servers(toggled).size)

        val collapsePrimary = buildServerList(g, null, ServerFilter(), toggledGroups = setOf("main"))
        assertEquals(0, servers(collapsePrimary).size)
    }

    @Test
    fun readOnlyGroupGetsNoteAndRowsWithoutClick() {
        val auto = rtGroup(items(3), type = "urltest", selectable = false)
        val model = buildServerList(groups(auto), null, ServerFilter())
        assertTrue(model.items.any { it is ServerListItem.Note && it.text.startsWith("Сервер выбирается автоматически") })
        assertTrue(model.items.filterIsInstance<ServerListItem.Server>().all { it.readOnly })
    }

    @Test
    fun compactListSkipsCardForSingleGroup() {
        val model = buildServerList(groups(rtGroup(items(3))), null, ServerFilter(), compact = true, groupCards = false)
        assertTrue(model.items.none { it is ServerListItem.Group })
        assertEquals(3, servers(model).size)
    }

    @Test
    fun itemKeysAreUnique() {
        val g = groups(rtGroup(items(12), tag = "a"), rtGroup(items(12), tag = "b", primary = false))
        val model = buildServerList(g, header(), ServerFilter(), toggledGroups = setOf("b"))
        val keys = model.items.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    private fun servers(model: ServerListModel) =
        model.items.filterIsInstance<ServerListItem.Server>().map { it.row }
}
