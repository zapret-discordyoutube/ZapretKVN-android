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

    // --- секции по странам ---

    private val us = "🇺🇸"

    /** 20 серверов: 8 NL, 6 DE, 4 US, 2 без страны. Выбран первый. */
    private fun bigGroup(selectedTag: String? = null): List<ServerGroupUi> {
        val nlItems = (1..8).map { RuntimeOutboundItem("$nl NL$it", "vless", "n$it.example:443", relay = ok(100 + it)) }
        val deItems = (1..6).map { RuntimeOutboundItem("VLESS $de DE$it", "vless", "d$it.example:443", relay = ok(50 + it)) }
        val usItems = (1..4).map { RuntimeOutboundItem("$us US$it", "vless", "u$it.example:443") }
        val none = (1..2).map { RuntimeOutboundItem("plain$it", "vless", "p$it.example:443") }
        val all = nlItems + deItems + usItems + none
        return groups(rtGroup(all, selected = selectedTag ?: all.first().tag))
    }

    private fun sections(model: ServerListModel) = model.items.filterIsInstance<ServerListItem.Section>()

    @Test
    fun favoritesGetStarChipAndOpenSectionOnTopOfLongGroup() {
        val favorites = setOf("$nl NL3", "VLESS $de DE2")
        val g = bigGroup().withFavorites(favorites)
        val model = buildServerList(g, header(), ServerFilter())

        val first = sections(model).first()
        assertTrue(first.section.favorites)
        assertTrue(first.expanded)
        assertEquals(favorites, first.section.rows.map { it.tag }.toSet())
        // Копии строк в «Избранных» не повторяют ключи строк в странах.
        val keys = model.items.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(g.single().rows.single { it.tag == "$nl NL3" }.a11y.endsWith("в избранном"))

        val chips = serverChips(g)
        assertEquals(ChipUi(ServerChip.Favorites, "★ 2", 2), chips.primary[1])
        val onlyFavorites = buildServerList(g, header(), ServerFilter(chip = ServerChip.Favorites))
        assertEquals(favorites, onlyFavorites.items.filterIsInstance<ServerListItem.Server>().map { it.row.tag }.toSet())
    }

    @Test
    fun searchInSingleGroupOffersToProbeOnlyTheResults() {
        val model = buildServerList(bigGroup(), header(), ServerFilter(query = "de"))

        val found = model.items.filterIsInstance<ServerListItem.FoundProbe>().single()
        assertEquals((1..6).map { "VLESS $de DE$it" }, found.found)
        assertTrue(model.items.none { it is ServerListItem.Group })
    }

    @Test
    fun searchAcrossGroupsProbesOnlyResultsOfEachGroup() {
        val a = rtGroup(items(10, prefix = "a"), tag = "first")
        val b = rtGroup(items(10, prefix = "b"), tag = "second", primary = false)
        val model = buildServerList(groups(a, b), header(), ServerFilter(query = "a1"))

        val cards = model.items.filterIsInstance<ServerListItem.Group>()
        assertEquals(listOf("first"), cards.map { it.group.tag })
        assertEquals(listOf("a1", "a10"), cards.single().found)
    }

    @Test
    fun countrySectionsOfConnectedGroupCanBeProbedOneByOne() {
        val model = buildServerList(bigGroup(), header(), ServerFilter())

        assertTrue(sections(model).all { it.measurable && it.groupTag == "zapret-proxy" })
    }

    @Test
    fun bigGroupIsSplitIntoCountrySectionsWithOtherLast() {
        val model = buildServerList(bigGroup(), header(), ServerFilter())
        val s = sections(model)
        assertEquals(listOf("NL", "DE", "US", null), s.map { it.section.code })
        assertEquals(listOf(8, 6, 4, 2), s.map { it.section.rows.size })
        assertEquals("Другие", s.last().section.title)
        assertEquals(101, s[0].section.bestMillis)
        assertEquals("лучший 101 мс", s[0].meta.substringAfter("·").trim())
        assertTrue(s[0].meta.startsWith("8 серверов"))
        assertEquals(null, s.last().section.bestMillis)
    }

    @Test
    fun onlySectionWithSelectedServerIsExpandedByDefault() {
        val model = buildServerList(bigGroup(), header(), ServerFilter())
        val s = sections(model)
        assertEquals(listOf(true, false, false, false), s.map { it.expanded })
        assertEquals(8, servers(model).size)
        assertTrue(s[0].hasSelected)
        assertTrue(model.selectedIndex > 0)
        // Строки раскрытой секции: Middle…, последняя — Last.
        val positions = model.items.filterIsInstance<ServerListItem.Server>().map { it.position }
        assertEquals(RowPosition.Last, positions.last())
        assertTrue(positions.dropLast(1).all { it == RowPosition.Middle })
    }

    @Test
    fun userStateOpensAndClosesSections() {
        val base = buildServerList(bigGroup(), header(), ServerFilter())
        val deKey = sections(base)[1].section.key
        val nlKey = sections(base)[0].section.key
        val opened = buildServerList(bigGroup(), header(), ServerFilter(), sections = SectionState().with(deKey, true))
        assertEquals(14, servers(opened).size)
        val closed = buildServerList(bigGroup(), header(), ServerFilter(), sections = SectionState().with(nlKey, false))
        assertEquals(0, servers(closed).size)
        assertTrue(sections(closed)[0].meta.endsWith("выбран"))
        // Секция «выбран» остаётся открытой, когда выбор уехал: сохранён в pinnedTags.
        val moved = bigGroup(selectedTag = "VLESS $de DE1")
        val pinned = buildServerList(moved, header(), ServerFilter(), pinnedTags = setOf("$nl NL1"))
        assertEquals(listOf(true, true, false, false), sections(pinned).map { it.expanded })
    }

    @Test
    fun sectionStateSurvivesSaveRestore() {
        val state = SectionState().with("a", true).with("b", false)
        assertEquals(state, SectionState.restore(state.save()))
    }

    @Test
    fun activeSearchOrFastChipShowsFlatListWithoutSections() {
        val byQuery = buildServerList(bigGroup(), header(), ServerFilter(query = "de"))
        assertTrue(sections(byQuery).isEmpty())
        assertEquals(6, servers(byQuery).size)
        val fast = buildServerList(bigGroup(), header(), ServerFilter(chip = ServerChip.Fast))
        assertTrue(sections(fast).isEmpty())
        assertEquals(14, servers(fast).size) // NL и DE с задержками
        val country = buildServerList(bigGroup(), header(), ServerFilter(chip = ServerChip.Country("US")))
        assertTrue(sections(country).isEmpty())
        assertEquals(4, servers(country).size)
    }

    @Test
    fun groupOfTwelveOrFewerStaysFlat() {
        val g = groups(rtGroup(items(12) { i -> if (i % 2 == 0) nl else de }))
        assertTrue(sections(buildServerList(g, header(), ServerFilter())).isEmpty())
    }

    @Test
    fun bigGroupWithoutAnyCountryStaysFlat() {
        val model = buildServerList(groups(rtGroup(items(20))), header(), ServerFilter())
        assertTrue(sections(model).isEmpty())
        assertEquals(20, servers(model).size)
    }

    @Test
    fun smallSectionsAnimateAndBigOnesDoNot() {
        val many = items(20) { nl } + items(2, "x") { de }
        val model = buildServerList(groups(rtGroup(many)), header(), ServerFilter())
        assertTrue(model.items.filterIsInstance<ServerListItem.Server>().none { it.animate }) // 20 > 15
        val small = buildServerList(bigGroup(), header(), ServerFilter())
        assertTrue(small.items.filterIsInstance<ServerListItem.Server>().all { it.animate })
    }

    @Test
    fun sectionOrderFollowsSort() {
        val byFastest = buildServerList(bigGroup(), header(), ServerFilter(), sort = ServerSort.Fastest)
        assertEquals(listOf("DE", "NL", "US", null), sections(byFastest).map { it.section.code })
        val byName = buildServerList(bigGroup(), header(), ServerFilter(), sort = ServerSort.Name)
        val titles = sections(byName).map { it.section.title }
        assertEquals(titles.dropLast(1).sorted(), titles.dropLast(1))
        assertEquals("Другие", titles.last())
    }

    @Test
    fun sectionAndItemKeysAreUnique() {
        val model = buildServerList(bigGroup(), header(), ServerFilter(), sections = SectionState().with("sec:zapret-proxy:DE", true))
        val keys = model.items.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun activeSearchDropsProfileCardAndSingleGroupCard() {
        val g = bigGroup()
        val idle = buildServerList(g, header(), ServerFilter())
        assertTrue(idle.items.any { it is ServerListItem.Profile })
        assertTrue(idle.items.any { it is ServerListItem.Group })
        val searching = buildServerList(g, header(), ServerFilter(query = "nl"))
        assertEquals(listOf("tools"), searching.items.take(1).map { it.key })
        assertTrue(searching.items.none { it is ServerListItem.Profile || it is ServerListItem.Group })
    }

    private fun servers(model: ServerListModel) =
        model.items.filterIsInstance<ServerListItem.Server>().map { it.row }
}
