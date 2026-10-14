package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.network.probes.services.Headline
import io.github.zapretkvn.android.network.probes.services.PathResult
import io.github.zapretkvn.android.network.probes.services.RouteHint
import io.github.zapretkvn.android.network.probes.services.ServiceAvailability
import io.github.zapretkvn.android.network.probes.services.ServiceAvailabilityState
import io.github.zapretkvn.android.network.probes.services.ServiceId
import io.github.zapretkvn.android.network.probes.services.ServiceSnapshot
import io.github.zapretkvn.android.network.probes.services.ServiceVerdict
import io.github.zapretkvn.core.designsystem.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicesPresenterTest {
    private val now = 10_000_000L

    private fun snap(
        id: ServiceId,
        headline: Headline,
        ageMillis: Long = 30_000,
        latency: Int? = 84,
        direct: ServiceVerdict = ServiceVerdict.Blocked,
        tunnel: ServiceVerdict = ServiceVerdict.Ok,
    ) = ServiceSnapshot(
        service = id,
        tunnel = PathResult(tunnel, latency, null, 1, 1),
        direct = PathResult(direct, null, null, 1, 1),
        route = RouteHint.Unknown,
        headline = headline,
        latencyMillis = latency,
        measuredAtEpochMillis = now - ageMillis,
        networkIdentity = "wifi-1",
        generation = 1,
    )

    private fun results(vararg pairs: Pair<ServiceId, ServiceAvailability>) =
        ServiceAvailabilityState(items = mapOf(*pairs))

    @Test
    fun verdictWordsAndTones() {
        assertEquals("Через VPN", headlineLabel(Headline.ViaVpn))
        assertEquals("Напрямую", headlineLabel(Headline.Direct))
        assertEquals("Недоступно", headlineLabel(Headline.Unavailable))
        assertEquals("Сервис не работает из региона сервера", headlineLabel(Headline.GeoRefused))
        assertEquals("Частично", headlineLabel(Headline.Partial))
        assertEquals("Не проверено", headlineLabel(Headline.NotChecked))
        assertEquals(Tone.Good, headlineTone(Headline.ViaVpn))
        assertEquals(Tone.Bad, headlineTone(Headline.Unavailable))
        assertEquals(Tone.Warning, headlineTone(Headline.GeoRefused))
    }

    @Test
    fun emptyStateShowsSixNotCheckedRowsAndAnInvitationToTap() {
        val ui = servicesCardUi(ServiceAvailabilityState.Initial, now)

        assertEquals(6, ui.rows.size)
        assertTrue(ui.rows.all { it.verdict == "Не проверено" && it.latency == null })
        assertEquals("Пока не проверено", ui.summary)
        assertEquals("Нажмите, чтобы проверить", ui.footnote)
        assertFalse(ui.hasResults)
    }

    @Test
    fun allOpenSummaryAndAge() {
        val state = ServiceAvailabilityState(
            items = ServiceId.entries.take(6).associateWith {
                ServiceAvailability.Result(snap(it, Headline.ViaVpn, ageMillis = 120_000))
            },
        )

        val ui = servicesCardUi(state, now)

        assertEquals("Все 6 открываются", ui.summary)
        assertEquals("Проверено 2 мин назад. Нажмите, чтобы повторить", ui.footnote)
        val row = ui.rows.first()
        assertEquals("Через VPN", row.verdict)
        assertEquals("84 мс", row.latency)
        assertEquals("2 мин назад", row.age)
        assertEquals("Без VPN не открывается", row.detail)
        assertEquals(Tone.Good, row.tone)
    }

    @Test
    fun partialOpeningSummaryCountsOnlyOpenOnes() {
        val state = results(
            ServiceId.YouTube to ServiceAvailability.Result(snap(ServiceId.YouTube, Headline.ViaVpn)),
            ServiceId.ChatGpt to ServiceAvailability.Result(snap(ServiceId.ChatGpt, Headline.GeoRefused)),
            ServiceId.Telegram to ServiceAvailability.Result(snap(ServiceId.Telegram, Headline.Unavailable)),
        )

        val ui = servicesCardUi(state, now)

        assertEquals("Открывается: 1 из 6", ui.summary)
        val geo = ui.rows.first { it.id == ServiceId.ChatGpt }
        assertEquals("Сервис не работает из региона сервера", geo.verdict)
        assertEquals("Смените сервер: сервис отказал этому выходу", geo.detail)
    }

    @Test
    fun resultsOlderThanFiveMinutesLookStaleEvenWithoutAStaleMark() {
        val fresh = servicesCardUi(
            results(ServiceId.YouTube to ServiceAvailability.Result(snap(ServiceId.YouTube, Headline.ViaVpn, ageMillis = 299_000))),
            now,
        )
        val old = servicesCardUi(
            results(ServiceId.YouTube to ServiceAvailability.Result(snap(ServiceId.YouTube, Headline.ViaVpn, ageMillis = 301_000))),
            now,
        )

        assertFalse(fresh.rows.first().stale)
        val row = old.rows.first()
        assertTrue(row.stale)
        assertEquals("устарело", row.age)
        assertEquals(Tone.Neutral, row.tone)
        assertEquals("Данные устарели. Нажмите, чтобы обновить", old.footnote)
    }

    @Test
    fun staleStateIsShownAsStaleAndKeepsTheLastVerdict() {
        val ui = servicesCardUi(
            results(ServiceId.YouTube to ServiceAvailability.Stale(snap(ServiceId.YouTube, Headline.ViaVpn, ageMillis = 1_000))),
            now,
        )
        val row = ui.rows.first()
        assertTrue(row.stale)
        assertEquals("Через VPN", row.verdict)
    }

    @Test
    fun checkingShowsProgressWithoutOldLatency() {
        val state = ServiceAvailabilityState(
            running = true,
            items = mapOf(
                ServiceId.YouTube to ServiceAvailability.Checking(snap(ServiceId.YouTube, Headline.ViaVpn)),
                ServiceId.Telegram to ServiceAvailability.Result(snap(ServiceId.Telegram, Headline.Direct)),
            ),
        )

        val ui = servicesCardUi(state, now)

        assertTrue(ui.checking)
        assertEquals("Проверяем сервисы…", ui.summary)
        val row = ui.rows.first { it.id == ServiceId.YouTube }
        assertTrue(row.checking)
        assertEquals("Проверяем…", row.verdict)
        assertNull(row.latency)
        assertEquals("Напрямую", ui.rows.first { it.id == ServiceId.Telegram }.verdict)
    }

    @Test
    fun rowDescriptionForTalkBackNamesServiceVerdictAndAge() {
        val ui = servicesCardUi(
            results(ServiceId.Discord to ServiceAvailability.Result(snap(ServiceId.Discord, Headline.ViaVpn))),
            now,
        )
        val description = ui.rows.first { it.id == ServiceId.Discord }.description
        assertTrue("Discord: Через VPN" in description)
        assertTrue("Отклик 84 мс" in description)
    }
}
