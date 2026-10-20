package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind
import io.github.zapretkvn.android.network.probes.services.Headline
import io.github.zapretkvn.android.network.probes.services.PathResult
import io.github.zapretkvn.android.network.probes.services.RouteHint
import io.github.zapretkvn.android.network.probes.services.ServiceAvailability
import io.github.zapretkvn.android.network.probes.services.ServiceAvailabilityState
import io.github.zapretkvn.android.network.probes.services.ServiceCatalog
import io.github.zapretkvn.android.network.probes.services.ServiceId
import io.github.zapretkvn.android.network.probes.services.ServiceSnapshot
import io.github.zapretkvn.android.network.probes.services.ServiceVerdict
import io.github.zapretkvn.core.designsystem.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectServicesPresenterTest {
    @Test
    fun slowDirectServiceOpensButIsAWarning() {
        val verdict = directVerdict(PathResult(ServiceVerdict.Slow, 6_000, null, 1, 1))

        assertEquals("Открывается медленно", verdict.label)
        assertEquals(Tone.Warning, verdict.tone)
        assertTrue(verdict.opens)
        assertFalse(verdict.blocked)
    }

    private val now = 10_000_000L
    private val six = ServiceCatalog.defaults.map { it.id }

    private fun direct(
        verdict: ServiceVerdict,
        failure: HttpsProbeFailureKind? = null,
        latency: Int? = null,
    ) = PathResult(verdict, latency, failure, 1, 1)

    private fun snap(id: ServiceId, path: PathResult, ageMillis: Long = 30_000) = ServiceSnapshot(
        service = id,
        tunnel = PathResult.NotMeasured,
        direct = path,
        route = RouteHint.Unknown,
        headline = if (path.verdict == ServiceVerdict.Ok) Headline.Direct else Headline.Unavailable,
        latencyMillis = path.latencyMillis,
        measuredAtEpochMillis = now - ageMillis,
        networkIdentity = "wifi-1",
        generation = 0,
    )

    private fun open(id: ServiceId, ageMillis: Long = 30_000) =
        ServiceAvailability.Result(snap(id, direct(ServiceVerdict.Ok, latency = 120), ageMillis))

    private fun blocked(id: ServiceId, kind: HttpsProbeFailureKind) =
        ServiceAvailability.Result(snap(id, direct(ServiceVerdict.Blocked, kind)))

    private fun state(vararg items: Pair<ServiceId, ServiceAvailability>, running: Boolean = false, noNetwork: Boolean = false) =
        ServiceAvailabilityState(running = running, items = mapOf(*items), noNetwork = noNetwork)

    @Test
    fun beforeAnyCheckItInvitesTheUserAndShowsNoRows() {
        val ui = directServicesCardUi(ServiceAvailabilityState.Initial, now, six)

        assertEquals("Узнайте, что у вас заблокировано", ui.summary)
        assertEquals("Проверить без VPN", ui.actionLabel)
        assertTrue(ui.rows.isEmpty())
        assertFalse(ui.hasResults)
        assertFalse(ui.checking)
        assertTrue(ui.footnote.contains("без VPN"))
    }

    @Test
    fun whileCheckingItCountsFinishedServices() {
        val state = state(
            ServiceId.YouTube to open(ServiceId.YouTube),
            ServiceId.Discord to open(ServiceId.Discord),
            ServiceId.Telegram to ServiceAvailability.Checking(null),
            ServiceId.Gemini to ServiceAvailability.Checking(null),
            ServiceId.ChatGpt to ServiceAvailability.Checking(null),
            ServiceId.Instagram to ServiceAvailability.Checking(null),
            running = true,
        )

        val ui = directServicesCardUi(state, now, six)

        assertEquals("Проверяем без VPN…", ui.summary)
        assertEquals("Идёт 2 из 6", ui.footnote)
        assertTrue(ui.checking)
        assertNull(ui.actionLabel)
        assertEquals(4, ui.rows.count { it.checking })
        assertEquals("Проверяем…", ui.rows.first { it.id == ServiceId.Telegram }.verdict)
    }

    @Test
    fun resetAndTimeoutReadAsBlockedWhileDnsAndHttpReadAsUnavailable() {
        val reset = directVerdict(direct(ServiceVerdict.Blocked, HttpsProbeFailureKind.Reset))
        val timeout = directVerdict(direct(ServiceVerdict.Blocked, HttpsProbeFailureKind.Timeout))
        val tls = directVerdict(direct(ServiceVerdict.Blocked, HttpsProbeFailureKind.Tls))
        val dns = directVerdict(direct(ServiceVerdict.Blocked, HttpsProbeFailureKind.Dns))
        val http = directVerdict(direct(ServiceVerdict.Unreachable, HttpsProbeFailureKind.HttpStatus))
        val other = directVerdict(direct(ServiceVerdict.Unreachable, HttpsProbeFailureKind.Other))

        listOf(reset, timeout, tls).forEach {
            assertEquals("Заблокировано", it.label)
            assertEquals(Tone.Bad, it.tone)
            assertTrue(it.blocked)
        }
        listOf(dns, http, other).forEach {
            assertEquals("Недоступно", it.label)
            assertFalse(it.blocked)
        }
        assertEquals("Открывается напрямую", directVerdict(direct(ServiceVerdict.Ok)).label)
        assertEquals(Tone.Good, directVerdict(direct(ServiceVerdict.Ok)).tone)
    }

    @Test
    fun everythingOpensMeansTheVpnIsNotNeeded() {
        val state = state(*six.map { it to open(it) }.toTypedArray())

        val ui = directServicesCardUi(state, now, six)

        assertEquals("Всё открывается без VPN", ui.summary)
        assertEquals("VPN сейчас не нужен для этих сервисов", ui.advice)
        assertTrue(ui.footnote.startsWith("Проверено"))
        assertNull(ui.actionLabel)
        assertTrue(ui.rows.all { it.verdict == "Открывается напрямую" && it.tone == Tone.Good && it.latency == "120 мс" })
    }

    @Test
    fun blockedServicesAreNamedWithAdviceToTurnTheVpnOn() {
        val state = state(
            ServiceId.YouTube to blocked(ServiceId.YouTube, HttpsProbeFailureKind.Reset),
            ServiceId.Instagram to blocked(ServiceId.Instagram, HttpsProbeFailureKind.Timeout),
            ServiceId.Telegram to open(ServiceId.Telegram),
            ServiceId.Discord to open(ServiceId.Discord),
            ServiceId.Gemini to open(ServiceId.Gemini),
            ServiceId.ChatGpt to open(ServiceId.ChatGpt),
        )

        val ui = directServicesCardUi(state, now, six)

        assertEquals("Заблокировано 2 из 6", ui.summary)
        assertEquals("YouTube и Instagram не открываются без VPN. Включите VPN", ui.advice)
        val youtube = ui.rows.first { it.id == ServiceId.YouTube }
        assertEquals("Заблокировано", youtube.verdict)
        assertNull(youtube.latency)
        assertEquals(Tone.Bad, youtube.tone)
    }

    @Test
    fun oneBlockedServiceUsesTheSingularVerb() {
        val state = state(
            ServiceId.YouTube to blocked(ServiceId.YouTube, HttpsProbeFailureKind.Reset),
            ServiceId.Telegram to open(ServiceId.Telegram),
        )

        val ui = directServicesCardUi(state, now, listOf(ServiceId.YouTube, ServiceId.Telegram))

        assertEquals("Заблокировано 1 из 2", ui.summary)
        assertEquals("YouTube не открывается без VPN. Включите VPN", ui.advice)
    }

    @Test
    fun mixedFailuresAreNotAllCalledBlocked() {
        val state = state(
            ServiceId.YouTube to blocked(ServiceId.YouTube, HttpsProbeFailureKind.Reset),
            ServiceId.Telegram to blocked(ServiceId.Telegram, HttpsProbeFailureKind.Dns),
            ServiceId.Discord to open(ServiceId.Discord),
        )

        val ui = directServicesCardUi(state, now, listOf(ServiceId.YouTube, ServiceId.Telegram, ServiceId.Discord))

        assertEquals("Не открывается: 2 из 3", ui.summary)
    }

    @Test
    fun longListsOfBlockedServicesAreShortened() {
        assertEquals("A", joinTitles(listOf("A")))
        assertEquals("A и B", joinTitles(listOf("A", "B")))
        assertEquals("A, B и C", joinTitles(listOf("A", "B", "C")))
        assertEquals("A, B, C и ещё 2", joinTitles(listOf("A", "B", "C", "D", "E")))
    }

    @Test
    fun staleResultsKeepTheirVerdictButAskForARefresh() {
        val state = state(
            ServiceId.YouTube to ServiceAvailability.Stale(snap(ServiceId.YouTube, direct(ServiceVerdict.Ok, latency = 50))),
        )

        val ui = directServicesCardUi(state, now, listOf(ServiceId.YouTube))

        val row = ui.rows.single()
        assertTrue(row.stale)
        assertEquals("устарело", row.age)
        assertEquals("Открывается напрямую", row.verdict)
        assertEquals(Tone.Neutral, row.tone)
        assertEquals("Данные устарели. Нажмите, чтобы обновить", ui.footnote)
    }

    @Test
    fun resultsOlderThanFiveMinutesLookStaleEvenWithoutAMark() {
        val state = state(ServiceId.YouTube to open(ServiceId.YouTube, ageMillis = 301_000))
        val ui = directServicesCardUi(state, now, listOf(ServiceId.YouTube))
        assertTrue(ui.rows.single().stale)
    }

    @Test
    fun noNetworkWithoutResultsSaysSoAndOffersARetry() {
        val ui = directServicesCardUi(state(noNetwork = true), now, six)

        assertEquals("Нет интернета", ui.summary)
        assertEquals("Проверьте подключение", ui.footnote)
        assertEquals("Проверить снова", ui.actionLabel)
        assertTrue(ui.rows.isEmpty())
    }

    @Test
    fun onlySelectedServicesAreShown() {
        val state = state(
            ServiceId.YouTube to open(ServiceId.YouTube),
            ServiceId.Claude to blocked(ServiceId.Claude, HttpsProbeFailureKind.Reset),
        )

        val ui = directServicesCardUi(state, now, listOf(ServiceId.YouTube))

        assertEquals(listOf(ServiceId.YouTube), ui.rows.map { it.id })
        assertEquals("Всё открывается без VPN", ui.summary)
        assertNotNull(ui.advice)
    }
}
