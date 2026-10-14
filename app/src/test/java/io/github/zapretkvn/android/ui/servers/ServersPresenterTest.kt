package io.github.zapretkvn.android.ui.servers

import io.github.zapretkvn.android.network.probes.PersistedProbeResult
import io.github.zapretkvn.android.network.probes.PersistedServerLatency
import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileServerGroup
import io.github.zapretkvn.android.profiles.ProfileServerOption
import io.github.zapretkvn.android.profiles.ProfileServerSummary
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeProgress
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencySample
import io.github.zapretkvn.android.vpn.LatencyUnsupportedReason
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.uimodel.LatencyBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServersPresenterTest {
    private val now = 1_000_000_000L
    private val nl = "🇳🇱"
    private val de = "🇩🇪"
    private val fi = "🇫🇮"

    private fun success(millis: Int, ageMillis: Long = 1_000L) =
        LatencyProbeState.Success(LatencySample(millis, now - ageMillis, null))

    private fun item(tag: String, relay: LatencyProbeState = LatencyProbeState.NotTested, type: String = "vless") =
        RuntimeOutboundItem(tag, type, "$tag.example:443", relay = relay)

    private fun group(
        items: List<RuntimeOutboundItem>,
        selected: String = items.first().tag,
        type: String = "selector",
        selectable: Boolean = true,
        tag: String = "zapret-proxy",
        progress: LatencyProbeProgress? = null,
    ) = RuntimeSelectorGroup(tag, type, selected, selectable, items, primary = true, probeProgress = progress)

    private fun live(g: RuntimeSelectorGroup, hide: Boolean = false) =
        liveServerGroups(listOf(g), hide, now).single()

    // --- задержка словами ---

    @Test
    fun latencyBandsMapToWordsAndTones() {
        val fast = latencyDisplay(success(84), now)
        assertEquals("84 мс", fast.value)
        assertEquals("быстро", fast.word)
        assertEquals(Tone.Good, fast.tone)
        assertEquals(LatencyBand.Fast, fast.band)

        val normal = latencyDisplay(success(200), now)
        assertEquals("нормально", normal.word)
        assertEquals(Tone.Warning, normal.tone)

        val slow = latencyDisplay(success(450), now)
        assertEquals("медленно", slow.word)
        assertEquals(Tone.Bad, slow.tone)
        assertEquals(LatencyIcon.Slow, slow.icon)
    }

    @Test
    fun failuresAndUnsupportedStatesHaveOwnWords() {
        fun failed(reason: LatencyFailure) = latencyDisplay(LatencyProbeState.Failed(reason), now)
        assertEquals("не отвечает", failed(LatencyFailure.NoResponse).word)
        assertEquals("не нашёлся адрес", failed(LatencyFailure.Dns).word)
        assertEquals("ошибка проверки", failed(LatencyFailure.Failed).word)
        assertTrue(failed(LatencyFailure.Dns).failed)
        assertEquals(Tone.Bad, failed(LatencyFailure.Dns).tone)
        assertEquals("не проверен", latencyDisplay(null, now).word)
        assertEquals("не проверен", latencyDisplay(LatencyProbeState.NotTested, now).word)
        assertEquals(
            "адрес неизвестен",
            latencyDisplay(LatencyProbeState.Unsupported(LatencyUnsupportedReason.MissingEndpoint), now).word,
        )
        assertEquals(
            "внутренняя группа",
            latencyDisplay(LatencyProbeState.Unsupported(LatencyUnsupportedReason.NestedGroup), now).word,
        )
    }

    @Test
    fun runningKeepsPreviousNumberAndStaleBecomesOld() {
        val running = latencyDisplay(LatencyProbeState.Running(LatencySample(120, now - 10, null)), now)
        assertTrue(running.running)
        assertEquals("120 мс", running.value)
        assertEquals("проверяем…", running.word)

        val stale = latencyDisplay(
            LatencyProbeState.Stale(LatencySample(90, now - 3 * 3_600_000L, null)),
            now,
        )
        assertEquals("давно (3 ч назад)", stale.word)
        assertNull("устаревший замер не должен считаться «быстрым»", stale.band)
        assertEquals(90, stale.rankMillis)

        val otherNetwork = latencyDisplay(LatencyProbeState.Stale(LatencySample(90, now - 1_000L, null)), now)
        assertEquals("другая сеть", otherNetwork.word)
    }

    @Test
    fun successOlderThanADayIsShownAsOld() {
        val old = latencyDisplay(success(80, ageMillis = 2 * 24 * 3_600_000L), now)
        assertEquals("давно (2 д назад)", old.word)
        assertNull(old.band)
        assertEquals(LatencyIcon.Old, old.icon)
    }

    // --- строка сервера ---

    @Test
    fun rowSplitsFlagProtocolAndTitle() {
        val g = live(group(listOf(item("VLESS $nl Amsterdam-2", success(84)))))
        val row = g.rows.single()
        assertEquals("Amsterdam-2", row.title)
        assertEquals(nl, row.flag)
        assertEquals("NL", row.countryCode)
        assertEquals("VLESS", row.protocol)
        assertEquals("VLESS $nl Amsterdam-2", row.tag)
        assertEquals("zapret-proxy/VLESS $nl Amsterdam-2", row.key)
        assertTrue(row.a11y.contains("Amsterdam-2"))
        assertTrue(row.a11y.contains("VLESS"))
        assertTrue(row.a11y.contains("84 миллисекунды".replace("миллисекунды", "миллисекунд")))
        assertTrue(row.a11y.endsWith("выбран"))
    }

    @Test
    fun rowWithoutFlagUsesProtocolMonogram() {
        val row = live(group(listOf(item("plain", type = "hysteria2")))).rows.single()
        assertNull(row.flag)
        assertEquals("HY", row.monogram)
        assertEquals("Hysteria2", row.protocol)
    }

    @Test
    fun hiddenAddressesMaskEndpointAndAddressLikeTitles() {
        val g = live(group(listOf(item("vpn.example.com:443"), item("Berlin"))), hide = true)
        val masked = g.rows[0]
        assertEquals("********", masked.title)
        assertEquals("********", masked.endpoint)
        assertFalse(masked.searchText.contains("example"))
        assertEquals("Berlin", g.rows[1].title)
        assertEquals("********", g.rows[1].endpoint)
    }

    @Test
    fun longTitleIsNeverShortened() {
        val long = "A".repeat(120)
        assertEquals(long, live(group(listOf(item(long)))).rows.single().title)
    }

    // --- «лучший» и подсказки ---

    @Test
    fun clearlyFastestGetsBadgeAndNotSelectedGetsHint() {
        val g = live(
            group(
                listOf(
                    item("a", success(200)),
                    item("b", success(90)),
                    item("c", success(300)),
                ),
                selected = "a",
            ),
        )
        assertEquals("★ Лучший", g.rows.first { it.tag == "b" }.badge)
        assertNull(g.rows.first { it.tag == "c" }.badge)
        assertEquals("b быстрее текущего на 110 мс", g.better?.text)
        assertEquals("b", g.better?.serverTag)
    }

    @Test
    fun selectedFastestIsMarkedAndGivesNoHint() {
        val g = live(group(listOf(item("a", success(60)), item("b", success(200))), selected = "a"))
        assertEquals("★ Выбран и самый быстрый", g.rows[0].badge)
        assertNull(g.better)
    }

    @Test
    fun closeResultsGetNoBestBadge() {
        val g = live(group(listOf(item("a", success(100)), item("b", success(105)))))
        assertTrue(g.rows.all { it.badge == null })
    }

    @Test
    fun hintNeedsAtLeast30msGap() {
        val g = live(group(listOf(item("a", success(130)), item("b", success(100))), selected = "a"))
        // b быстрее на 30 мс и «явно» быстрее (>=20 мс): подсказка есть
        assertNotNull(g.better)
        val close = live(group(listOf(item("a", success(120)), item("b", success(100))), selected = "a"))
        assertNull(close.better)
    }

    @Test
    fun autoGroupIsReadOnlyAndMarksCurrentServer() {
        val g = live(
            group(
                listOf(item("a", success(60)), item("b", success(70))),
                selected = "b", type = "urltest", selectable = false,
            ),
        )
        assertFalse(g.selectable)
        assertEquals(GroupKind.UrlTest, g.kind)
        assertEquals("Авто · самый быстрый", g.title)
        assertEquals("★ Сейчас выбран", g.rows[1].badge)
        assertNull(g.rows[0].badge)
    }

    // --- строка проверки ---

    @Test
    fun probeLineFollowsStates() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals(
            "Нажмите «Проверить все», чтобы увидеть, какой сервер быстрее",
            live(group(items)).probeLine,
        )
        val running = LatencyProbeProgress(1, "net", relayCompleted = 12, relayTotal = 48, icmpCompleted = 0, icmpTotal = 0, running = true)
        val g = live(group(items, progress = running))
        assertEquals("Проверено 12 из 48", g.probeLine)
        assertTrue(g.measuring)
        assertEquals(0.25f, g.progressFraction)

        val done = live(group(listOf(item("a", success(84)), item("b", success(300)))))
        assertEquals("Лучший: a, 84 мс", done.probeLine)

        val allFailed = live(
            group(
                listOf(
                    item("a", LatencyProbeState.Failed(LatencyFailure.NoResponse)),
                    item("b", LatencyProbeState.Failed(LatencyFailure.Failed)),
                ),
            ),
        )
        assertEquals("Ни один сервер не ответил. Возможно, проблема в сети, а не в серверах", allFailed.probeLine)
    }

    @Test
    fun singleServerGroupHasNoProbeLine() {
        assertNull(live(group(listOf(item("only")))).probeLine)
    }

    @Test
    fun collapsedSummaryNamesCurrentServer() {
        val g = live(group(listOf(item("$nl Amsterdam-2", success(84)), item("other")), selected = "$nl Amsterdam-2"))
        assertEquals("Сейчас: $nl Amsterdam-2 · 84 мс быстро", g.currentLine)
    }

    @Test
    fun groupTitleDropsGenericTags() {
        assertNull(groupTagLabel("proxy"))
        assertNull(groupTagLabel("zapret-proxy"))
        assertEquals("Streaming", groupTagLabel("Streaming"))
        assertEquals("Streaming · Ручной выбор", live(group(listOf(item("a")), tag = "Streaming")).title)
        assertEquals(GroupKind.Fallback, GroupKind.of("fallback"))
        assertEquals(GroupKind.Other, GroupKind.of("weird"))
    }

    // --- офлайн ---

    @Test
    fun offlineGroupsUseSavedLatencyAndCannotMeasure() {
        val summary = ProfileServerSummary(
            listOf(
                ProfileServerGroup(
                    tag = "zapret-proxy",
                    selected = "b",
                    options = listOf(
                        ProfileServerOption("a", "vless", "a.example:443"),
                        ProfileServerOption("b", "trojan", "b.example:443"),
                    ),
                ),
            ),
        )
        val saved = mapOf(
            "a" to PersistedServerLatency("fp", relay = PersistedProbeResult(95, null, now - 60_000L)),
        )
        val g = offlineServerGroups(summary, saved, hideAddresses = false, nowEpochMillis = now).single()
        assertFalse(g.live)
        assertTrue(g.selectable)
        assertEquals("95 мс", g.rows[0].latency.value)
        assertEquals("не проверен", g.rows[1].latency.word)
        assertTrue(g.rows[1].selected)
        assertEquals("Проверка доступна после подключения", g.probeLine)
    }

    // --- шапка профиля ---

    @Test
    fun statsCountServersCountriesAndFlags() {
        val stats = serverStats(
            listOf("$nl Amsterdam-1", "$nl Amsterdam-2", "$de Berlin", "$fi Helsinki", "plain", "$nl Amsterdam-1"),
        )
        assertEquals(5, stats.servers)
        assertEquals(3, stats.countries)
        assertEquals(listOf(nl, de, fi), stats.flags)
        assertEquals("5 серверов · 3 страны", stats.countsLabel())
        assertEquals(0, stats.extraFlags())
    }

    @Test
    fun statsWithoutFlagsShowOnlyServerCount() {
        assertEquals("1 сервер", serverStats(listOf("a")).countsLabel())
        assertEquals("2 сервера", serverStats(listOf("a", "b")).countsLabel())
        assertNull(serverStats(emptyList()).countsLabel())
    }

    @Test
    fun flagStripShowsFiveAndRest() {
        val flags = listOf("A", "B", "C", "D", "E", "F", "G")
        val stats = ServerStats(7, 7, flags)
        assertEquals("A B C D E", stats.flagStrip())
        assertEquals(2, stats.extraFlags())
    }

    @Test
    fun memberTagsSkipNestedGroups() {
        val summary = ProfileServerSummary(
            listOf(
                ProfileServerGroup(
                    "main", "auto",
                    listOf(ProfileServerOption("auto", "urltest", null), ProfileServerOption("s1", "vless", null)),
                ),
                ProfileServerGroup("auto", "s1", listOf(ProfileServerOption("s1", "vless", null))),
            ),
        )
        assertEquals(listOf("s1"), memberTags(summary))
    }

    // --- ui: какое состояние показываем ---

    private fun profile(id: String, updatedAgo: Long = 2 * 3_600_000L) = ProfileMetadata(
        id = id, name = "Моя подписка", source = ProfileSource.Url,
        createdAtEpochMillis = now - 10 * updatedAgo, updatedAtEpochMillis = now - updatedAgo,
    )

    private fun summaryOf(vararg tags: String) = ProfileServerSummary(
        listOf(
            ProfileServerGroup(
                "zapret-proxy", tags.first(),
                tags.map { ProfileServerOption(it, "vless", "$it.example:443") },
            ),
        ),
    )

    @Test
    fun uiStatesFollowProfilesAndSummaries() {
        fun ui(
            profiles: List<ProfileMetadata>,
            active: String?,
            summaries: Map<String, ProfileServerSummary> = emptyMap(),
            runtime: List<RuntimeSelectorGroup> = emptyList(),
            live: Boolean = false,
        ) = buildServersUi(profiles, active, summaries, emptyMap(), runtime, live, false, now)

        assertEquals(ServersUi.Empty, ui(emptyList(), null))
        assertEquals(ServersUi.NoActive, ui(listOf(profile("p")), null))
        assertTrue(ui(listOf(profile("p")), "p") is ServersUi.NoServers)

        val content = ui(
            listOf(profile("p")), "p", mapOf("p" to summaryOf("$nl one", "$de two")),
        ) as ServersUi.Content
        assertEquals("Моя подписка", content.header.name)
        assertEquals("2 сервера · 2 страны", content.header.counts)
        assertEquals("Обновлено 2 ч назад", content.header.updated)
        assertFalse(content.groups.single().live)
    }

    @Test
    fun liveRuntimeGroupsWinOverSavedSummaryWhenConnected() {
        val runtime = listOf(group(listOf(item("a", success(50)), item("b", success(300))), selected = "b"))
        val content = buildServersUi(
            listOf(profile("p")), "p", mapOf("p" to summaryOf("a", "b")), emptyMap(),
            runtime, live = true, hideAddresses = false, nowEpochMillis = now,
        ) as ServersUi.Content
        val g = content.groups.single()
        assertTrue(g.live)
        assertEquals("50 мс", g.rows[0].latency.value)
        assertTrue(g.rows[1].selected)
        assertEquals("★ Лучший", g.rows[0].badge)
    }

    @Test
    fun connectedButRuntimeNotReadyFallsBackToSavedProfile() {
        val content = buildServersUi(
            listOf(profile("p")), "p", mapOf("p" to summaryOf("a", "b")), emptyMap(),
            emptyList(), live = true, hideAddresses = false, nowEpochMillis = now,
        ) as ServersUi.Content
        assertFalse(content.groups.single().live)
    }
}
