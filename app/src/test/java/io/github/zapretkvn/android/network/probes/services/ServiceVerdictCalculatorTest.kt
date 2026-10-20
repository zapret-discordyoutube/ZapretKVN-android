package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceVerdictCalculatorTest {
    private val primary = ProbeEndpoint("svc.example")
    private val geoApi = ProbeEndpoint(
        "api.example",
        "/v1",
        ProbeMethod.Get,
        EndpointRole.Secondary,
        anyStatus = true,
        geo = GeoRule(setOf(403, 451), listOf("unsupported_country")),
    )

    @Test
    fun statusTableForPrimaryEndpoint() {
        fun of(status: Int) = ServiceVerdictCalculator.classify(primary, http(status))
        assertTrue(of(204) is PathOutcome.Ok)
        assertTrue(of(302) is PathOutcome.Ok)
        assertTrue(of(404) is PathOutcome.Ok)
        // 403 от защиты без маркера региона: TLS и HTTP работают.
        assertTrue(of(403) is PathOutcome.Ok)
        assertTrue(of(429) is PathOutcome.Ok)
        assertEquals(PathOutcome.Blocked(HttpsProbeFailureKind.HttpStatus), of(502))
        assertTrue(of(451) is PathOutcome.GeoRefused)
    }

    @Test
    fun anyHttpStatusCountsOnEndpointsThatOnlyProveTls() {
        val cdn = ProbeEndpoint("cdn.example", role = EndpointRole.Secondary, anyStatus = true)
        assertTrue(ServiceVerdictCalculator.classify(cdn, http(503)) is PathOutcome.Ok)
    }

    @Test
    fun geoMarkerInBodyMeansServiceRefusedTheRegion() {
        val refused = ServiceVerdictCalculator.classify(geoApi, http(403, body = "{\"code\":\"unsupported_country\"}"))
        assertTrue(refused is PathOutcome.GeoRefused)
        // 401 без ключа: доступен, регион не проверяется.
        assertTrue(ServiceVerdictCalculator.classify(geoApi, http(401)) is PathOutcome.Ok)
        // 403 без маркера (например, защита): не отказ региона.
        assertTrue(ServiceVerdictCalculator.classify(geoApi, http(403, body = "forbidden")) is PathOutcome.Ok)
        // Маркер на статусе, для которого тело не читается, игнорируется.
        assertTrue(ServiceVerdictCalculator.classify(geoApi, http(200, body = "unsupported_country")) is PathOutcome.Ok)
    }

    @Test
    fun failuresKeepTheirKindAndTcpOpenIsOk() {
        assertEquals(
            PathOutcome.Blocked(HttpsProbeFailureKind.Tls),
            ServiceVerdictCalculator.classify(primary, failure(HttpsProbeFailureKind.Tls)),
        )
        assertTrue(ServiceVerdictCalculator.classify(primary, TransportResult.TcpOpen(30, 100)) is PathOutcome.Ok)
    }

    @Test
    fun verdictFromPrimaryAndSecondaries() {
        val ok = PathOutcome.Ok(50, 200)
        val blocked = PathOutcome.Blocked(HttpsProbeFailureKind.Reset)
        val v = ServiceVerdictCalculator::verdict
        assertEquals(ServiceVerdict.Ok, v(ok, listOf(ok)))
        assertEquals(ServiceVerdict.Partial, v(ok, listOf(ok, blocked)))
        assertEquals(ServiceVerdict.GeoRefused, v(ok, listOf(PathOutcome.GeoRefused(10, 403))))
        assertEquals(ServiceVerdict.Blocked, v(blocked, emptyList()))
        assertEquals(ServiceVerdict.Blocked, v(PathOutcome.Blocked(HttpsProbeFailureKind.Timeout), listOf(ok)))
        assertEquals(ServiceVerdict.Blocked, v(PathOutcome.Blocked(HttpsProbeFailureKind.Dns), emptyList()))
        assertEquals(ServiceVerdict.Unreachable, v(PathOutcome.Blocked(HttpsProbeFailureKind.Other), emptyList()))
        assertEquals(ServiceVerdict.Unreachable, v(PathOutcome.Blocked(HttpsProbeFailureKind.HttpStatus), emptyList()))
        assertEquals(ServiceVerdict.NotMeasured, v(null, listOf(ok)))
        assertEquals(ServiceVerdict.NotMeasured, v(PathOutcome.Skipped(SkipReason.NoVpn), listOf(ok)))
        // Побочный пропущен: на вердикт не влияет.
        assertEquals(ServiceVerdict.Ok, v(ok, listOf(PathOutcome.Skipped(SkipReason.Budget))))
    }

    @Test
    fun anOpeningServiceWithAVerySlowPrimaryRequestIsSlowNotOk() {
        val v = ServiceVerdictCalculator::verdict
        val slow = PathOutcome.Ok(ServiceQuality.SLOW_MILLIS, 200)
        val justFast = PathOutcome.Ok(ServiceQuality.SLOW_MILLIS - 1, 200)
        val blocked = PathOutcome.Blocked(HttpsProbeFailureKind.Reset)

        assertEquals(ServiceVerdict.Slow, v(slow, emptyList()))
        assertEquals(ServiceVerdict.Ok, v(justFast, emptyList()))
        // Закрытое побочное имя тяжелее медленного ответа: сервис открывается не целиком.
        assertEquals(ServiceVerdict.Partial, v(slow, listOf(blocked)))
        // Отказ региона по-прежнему главнее скорости.
        assertEquals(ServiceVerdict.GeoRefused, v(slow, listOf(PathOutcome.GeoRefused(10, 403))))
    }

    @Test
    fun slowServiceHasItsOwnHeadlineButStillCountsAsOpening() {
        val h = ServiceVerdictCalculator::headline
        val slow = ServiceVerdict.Slow

        assertEquals(Headline.Slow, h(slow, ServiceVerdict.Blocked, RouteHint.Proxy))
        assertEquals(Headline.Slow, h(slow, ServiceVerdict.NotMeasured, RouteHint.Unknown))
        // Если по правилам маршрутов сервис идёт напрямую и прямой путь открывается, важнее он.
        assertEquals(Headline.Direct, h(slow, ServiceVerdict.Ok, RouteHint.Direct))
        // Медленный прямой путь тоже считается открывающимся.
        assertEquals(Headline.ViaVpn, h(ServiceVerdict.Ok, slow, RouteHint.Proxy))
    }

    @Test
    fun headlineMatrix() {
        val h = ServiceVerdictCalculator::headline
        val ok = ServiceVerdict.Ok
        val blocked = ServiceVerdict.Blocked
        val none = ServiceVerdict.NotMeasured

        // Туннель открывает, напрямую нет: через VPN при любом маршруте.
        for (route in RouteHint.entries) assertEquals(Headline.ViaVpn, h(ok, blocked, route))
        // Оба открывают: решает маршрут.
        assertEquals(Headline.Direct, h(ok, ok, RouteHint.Direct))
        assertEquals(Headline.ViaVpn, h(ok, ok, RouteHint.Proxy))
        assertEquals(Headline.Both, h(ok, ok, RouteHint.Unknown))
        // Прямой путь не измерялся.
        assertEquals(Headline.Both, h(ok, none, RouteHint.Unknown))
        assertEquals(Headline.ViaVpn, h(ok, none, RouteHint.Proxy))
        // Частично через VPN.
        assertEquals(Headline.Partial, h(ServiceVerdict.Partial, blocked, RouteHint.Proxy))
        assertEquals(Headline.Direct, h(ServiceVerdict.Partial, ok, RouteHint.Direct))
        // Регион: смена сервера поможет, переход на прямой путь нет.
        assertEquals(Headline.GeoRefused, h(ServiceVerdict.GeoRefused, ok, RouteHint.Proxy))
        assertEquals(Headline.GeoRefused, h(ServiceVerdict.GeoRefused, blocked, RouteHint.Unknown))
        assertEquals(Headline.Direct, h(ServiceVerdict.GeoRefused, ok, RouteHint.Direct))
        // Туннель закрыт.
        assertEquals(Headline.Unavailable, h(blocked, blocked, RouteHint.Proxy))
        assertEquals(Headline.Unavailable, h(blocked, ok, RouteHint.Proxy))
        assertEquals(Headline.Direct, h(blocked, ok, RouteHint.Direct))
        assertEquals(Headline.Direct, h(blocked, ok, RouteHint.Unknown))
        assertEquals(Headline.Unavailable, h(ServiceVerdict.Unreachable, none, RouteHint.Unknown))
        // Туннеля не было.
        assertEquals(Headline.Direct, h(none, ok, RouteHint.Unknown))
        assertEquals(Headline.Unavailable, h(none, blocked, RouteHint.Unknown))
        assertEquals(Headline.NotChecked, h(none, none, RouteHint.Unknown))
    }

    @Test
    fun latencyFollowsTheVisiblePath() {
        val tunnel = path(ServiceVerdict.Ok, 120)
        val direct = path(ServiceVerdict.Ok, 30)
        assertEquals(30, ServiceVerdictCalculator.latency(Headline.Direct, tunnel, direct))
        assertEquals(120, ServiceVerdictCalculator.latency(Headline.ViaVpn, tunnel, direct))
        assertEquals(null, ServiceVerdictCalculator.latency(Headline.NotChecked, tunnel, direct))
    }
}
