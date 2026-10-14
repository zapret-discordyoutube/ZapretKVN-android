package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceProbeRunnerTest {
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val published = CopyOnWriteArrayList<ServiceSnapshot>()

    private fun runner(
        workers: Int = ServiceProbeRunner.WORKERS,
        runTimeout: Long = ServiceProbeBudget.RUN_TIMEOUT_MILLIS,
        requestTimeout: Long = 5_000,
    ) = ServiceProbeRunner(
        random = Random(11),
        sleep = { sleeps += it },
        clock = { 123L },
        workers = workers,
        runTimeoutMillis = runTimeout,
        requestTimeoutMillis = requestTimeout,
    )

    private suspend fun ServiceProbeRunner.go(
        paths: RunPaths,
        definitions: List<ServiceDefinition> = ServiceCatalog.defaults,
        route: (ServiceId) -> RouteHint = { RouteHint.Unknown },
    ): ServiceRunReport = run(definitions, paths, "wifi-1", 5L, route) { batch -> published += batch.values }

    private fun headline(id: ServiceId) = published.single { it.service == id }.headline

    @Test
    fun viaVpnWhenTunnelOpensAndDirectIsBlocked() = runBlocking {
        val tunnel = FakeTunnel(FakeTransport { http(200) })
        val direct = FakeTransport { failure(HttpsProbeFailureKind.Reset) }

        val report = runner().go(RunPaths({ tunnel }, direct))

        assertEquals(ServiceCatalog.defaults.size, published.size)
        assertTrue(published.all { it.headline == Headline.ViaVpn })
        assertTrue(published.all { it.direct.verdict == ServiceVerdict.Blocked })
        assertTrue(published.all { it.generation == 5L && it.networkIdentity == "wifi-1" && it.measuredAtEpochMillis == 123L })
        assertTrue(tunnel.closed)
        assertTrue(report.tunnelAvailable && report.directAvailable && !report.timedOut)
    }

    @Test
    fun directWinsWhenTheRouteIsDirectAndBothOpen() = runBlocking {
        val tunnel = FakeTunnel(FakeTransport { http(200, millis = 90) })
        val direct = FakeTransport { http(200, millis = 20) }

        runner().go(RunPaths({ tunnel }, direct), route = { RouteHint.Direct })

        assertTrue(published.all { it.headline == Headline.Direct })
        assertTrue(published.all { it.latencyMillis == 20 })
    }

    @Test
    fun partialWhenOnlyTheSecondaryEndpointIsBlocked() = runBlocking {
        val tunnel = FakeTunnel(
            FakeTransport { target ->
                val host = (target as? ProbeTarget.Https)?.endpoint?.host
                if (host == "redirector.googlevideo.com") failure(HttpsProbeFailureKind.Reset) else http(204)
            },
        )

        runner().go(RunPaths({ tunnel }, null), definitions = ServiceCatalog.defaults.filter { it.id == ServiceId.YouTube })

        assertEquals(Headline.Partial, headline(ServiceId.YouTube))
    }

    @Test
    fun geoRefusalFromTheApiHostShowsAsRegionProblem() = runBlocking {
        val tunnel = FakeTunnel(
            FakeTransport { target ->
                val host = (target as? ProbeTarget.Https)?.endpoint?.host
                if (host == "api.openai.com") http(403, body = "unsupported_country_region_territory") else http(403)
            },
        )

        runner().go(RunPaths({ tunnel }, null), definitions = ServiceCatalog.defaults.filter { it.id == ServiceId.ChatGpt })

        assertEquals(Headline.GeoRefused, headline(ServiceId.ChatGpt))
    }

    @Test
    fun telegramNeedsHttpsAndTcp() = runBlocking {
        val tunnel = FakeTunnel(
            FakeTransport { target -> if (target is ProbeTarget.Tcp) failure(HttpsProbeFailureKind.Timeout) else http(302) },
        )

        runner().go(RunPaths({ tunnel }, null), definitions = ServiceCatalog.defaults.filter { it.id == ServiceId.Telegram })

        assertEquals(Headline.Partial, headline(ServiceId.Telegram))
    }

    @Test
    fun neverMoreThanThreeRequestsInFlight() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val slow: suspend (ProbeTarget) -> TransportResult = {
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now, ::maxOf)
            delay(15)
            inFlight.decrementAndGet()
            http(200)
        }

        runner().go(RunPaths({ FakeTunnel(FakeTransport(slow)) }, FakeTransport(slow)))

        assertTrue("peak=${peak.get()}", peak.get() in 2..3)
        assertEquals(ServiceCatalog.defaults.size, published.size)
    }

    @Test
    fun startJitterStaysInTheDocumentedBounds() = runBlocking {
        val tunnel = FakeTunnel(FakeTransport { http(200) })
        val direct = FakeTransport { http(200) }

        runner().go(RunPaths({ tunnel }, direct))

        val tunnelRequests = tunnel.transport.let { (it as FakeTransport).calls.size }
        val directRequests = direct.calls.size
        assertEquals(tunnelRequests + directRequests, sleeps.size)
        val directJitter = sleeps.filter { it >= ServiceProbeRunner.DIRECT_JITTER_MIN_MILLIS }
        assertTrue(sleeps.all { it in 0..ServiceProbeRunner.DIRECT_JITTER_MAX_MILLIS })
        assertTrue(directJitter.size >= directRequests)
        assertTrue(sleeps.count { it <= ServiceProbeRunner.TUNNEL_JITTER_MILLIS } >= tunnelRequests)
    }

    @Test
    fun onlyTheTunnelPrimaryIsRetriedOnceAfterAPause() = runBlocking {
        val tunnelTransport = FakeTransport { failure(HttpsProbeFailureKind.Timeout) }
        val direct = FakeTransport { failure(HttpsProbeFailureKind.Timeout) }
        val only = ServiceCatalog.defaults.filter { it.id == ServiceId.Instagram }

        runner().go(RunPaths({ FakeTunnel(tunnelTransport) }, direct), definitions = only)

        // Основной адрес — дважды, побочный — один раз; прямой путь без повторов.
        assertEquals(listOf("www.instagram.com", "www.instagram.com", "static.cdninstagram.com").sorted(), tunnelTransport.hosts.sorted())
        assertEquals(listOf("www.instagram.com"), direct.hosts)
        val retryPauses = sleeps.filter { it >= ServiceProbeRunner.RETRY_DELAY_MIN_MILLIS }
        assertEquals(1, retryPauses.size)
        assertTrue(retryPauses.single() <= ServiceProbeRunner.RETRY_DELAY_MAX_MILLIS)
        assertEquals(Headline.Unavailable, headline(ServiceId.Instagram))
    }

    @Test
    fun tlsFailureOnTheTunnelPrimaryIsNotRetried() = runBlocking {
        val calls = AtomicInteger()
        val transport = FakeTransport {
            calls.incrementAndGet()
            failure(HttpsProbeFailureKind.Tls)
        }

        runner().go(
            RunPaths({ FakeTunnel(transport) }, null),
            definitions = ServiceCatalog.defaults.filter { it.id == ServiceId.Telegram },
        )

        // TLS-сбой не повторяется (повтор только для таймаута и сброса): HTTPS и TCP по одному разу.
        assertEquals(2, calls.get())
    }

    @Test
    fun lostVpnNetworkAbortsTheTunnelHalfWithoutFallingBackToTheDirectSocket() = runBlocking {
        val tunnelCalls = AtomicInteger()
        val lost = FakeTransport {
            if (tunnelCalls.incrementAndGet() > 2) throw PathLostException()
            http(200)
        }
        val direct = FakeTransport { failure(HttpsProbeFailureKind.Reset) }

        val report = runner(workers = 1).go(RunPaths({ FakeTunnel(lost) }, direct))

        // Туннельные цели не уходили на несвязанный/прямой транспорт.
        val tunnelOnlyHosts = ServiceCatalog.defaults.flatMap { it.endpoints.filter { e -> e.role == EndpointRole.Secondary } }.map { it.host }
        assertTrue(direct.hosts.none { it in tunnelOnlyHosts })
        assertTrue(direct.calls.none { it is ProbeTarget.Tcp })
        assertEquals(ServiceCatalog.defaults.size, direct.calls.size)
        // После потери новые туннельные запросы не стартуют.
        assertEquals(3, tunnelCalls.get())
        assertTrue(report.tunnelAvailable)
        assertEquals(ServiceCatalog.defaults.size, published.size)
        assertTrue(published.any { it.tunnel.verdict == ServiceVerdict.NotMeasured })
        assertTrue(published.none { it.headline == Headline.ViaVpn && it.tunnel.verdict != ServiceVerdict.Ok })
    }

    @Test
    fun withoutVpnLeaseOnlyTheDirectPathRuns() = runBlocking {
        val direct = FakeTransport { http(200) }

        val report = runner().go(RunPaths({ null }, direct))

        assertFalse(report.tunnelAvailable)
        assertTrue(published.all { it.headline == Headline.Direct && it.tunnel.verdict == ServiceVerdict.NotMeasured })
    }

    @Test
    fun withoutDirectNetworkOnlyTheTunnelRuns() = runBlocking {
        val tunnel = FakeTunnel(FakeTransport { http(200) })

        val report = runner().go(RunPaths({ tunnel }, null), route = { RouteHint.Proxy })

        assertFalse(report.directAvailable)
        assertTrue(published.all { it.headline == Headline.ViaVpn && it.direct.verdict == ServiceVerdict.NotMeasured })
    }

    @Test
    fun requestCountIsCappedAtTheBudget() = runBlocking {
        val many = (0 until 60).map { index ->
            ServiceDefinition(ServiceId.entries[index % ServiceId.entries.size], listOf(ProbeEndpoint("h$index.example")))
        }
        val transport = FakeTransport { http(200, bytes = 100) }

        val report = runner().go(RunPaths({ FakeTunnel(transport) }, null), definitions = many)

        assertTrue(report.requestsMade <= ServiceProbeBudget.MAX_REQUESTS)
        assertEquals(ServiceProbeBudget.MAX_REQUESTS, transport.calls.size)
    }

    @Test
    fun byteBudgetStopsFurtherRequests() = runBlocking {
        val heavy = FakeTransport { http(200, bytes = 300_000) }
        val tunnel = FakeTunnel(heavy)

        val report = runner(workers = 1).go(RunPaths({ tunnel }, null))

        assertTrue("made=${report.requestsMade}", report.requestsMade <= 2)
        assertTrue(report.bytes < ServiceProbeBudget.MAX_BYTES + 300_000)
        assertTrue(report.requestsPlanned > report.requestsMade)
    }

    @Test
    fun aHungRequestBecomesATimeoutInsteadOfBlockingTheRun() = runBlocking {
        val hung = FakeTransport {
            delay(10_000)
            http(200)
        }

        val report = runner(requestTimeout = 30).go(
            RunPaths({ FakeTunnel(hung) }, null),
            definitions = ServiceCatalog.defaults.filter { it.id == ServiceId.Discord },
        )

        assertEquals(Headline.Unavailable, headline(ServiceId.Discord))
        assertEquals(HttpsProbeFailureKind.Timeout, published.single().tunnel.failure)
        assertTrue(report.durationMillis < 5_000)
    }

    @Test
    fun theWholeRunHasATimeCap() = runBlocking {
        val hung = FakeTransport {
            delay(10_000)
            http(200)
        }

        val report = runner(runTimeout = 100).go(RunPaths({ FakeTunnel(hung) }, null))

        assertTrue(report.timedOut)
        assertNotNull(report)
        // Незавершённое не выдаётся за блокировку: строки остаются «не проверено».
        assertTrue(published.all { it.headline == Headline.NotChecked })
    }
}
