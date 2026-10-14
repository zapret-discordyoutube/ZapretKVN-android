package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.config.ConfigAnalyzer
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceProbePlannerTest {
    private val defaults = ServiceCatalog.defaults

    @Test
    fun tunnelChecksEveryEndpointAndDirectOnlyThePrimary() {
        val plan = ServiceProbePlanner.plan(defaults, tunnel = true, direct = true, random = Random(1))

        val tunnel = plan.requests.filter { it.path == PathKind.Tunnel }
        val direct = plan.requests.filter { it.path == PathKind.Direct }
        // 6 сервисов: по 2 адреса, у Telegram один адрес плюс TCP.
        assertEquals(defaults.sumOf { it.endpoints.size + it.tcp.size }, tunnel.size)
        assertEquals(defaults.size, direct.size)
        assertTrue(direct.all { it.isPrimary && it.target is ProbeTarget.Https })
        assertTrue(plan.requests.size <= ServiceProbeBudget.MAX_REQUESTS)
    }

    @Test
    fun tunnelRequestsGoBeforeDirectOnes() {
        val plan = ServiceProbePlanner.plan(defaults, tunnel = true, direct = true, random = Random(7))
        val firstDirect = plan.requests.indexOfFirst { it.path == PathKind.Direct }
        assertTrue(plan.requests.drop(firstDirect).all { it.path == PathKind.Direct })
    }

    @Test
    fun orderIsShuffledButReproduciblePerSeed() {
        fun order(seed: Int) = ServiceProbePlanner.plan(defaults, true, true, Random(seed)).requests.map { it.key }
        assertEquals(order(3), order(3))
        assertTrue((1..10).map(::order).toSet().size > 1)
    }

    @Test
    fun identicalRequestsOfTwoServicesAreSentOnce() {
        val shared = ProbeEndpoint("shared.example")
        val a = ServiceDefinition(ServiceId.YouTube, listOf(shared))
        val b = ServiceDefinition(ServiceId.Discord, listOf(shared))

        val plan = ServiceProbePlanner.plan(listOf(a, b), tunnel = true, direct = false)

        val request = plan.requests.single()
        assertEquals(setOf(ServiceId.YouTube, ServiceId.Discord), request.consumers.map { it.service }.toSet())
    }

    @Test
    fun budgetCapsTheRequestCountAndDropsDirectFirst() {
        val many = (0 until 60).map { index ->
            ServiceDefinition(ServiceId.entries[index % ServiceId.entries.size], listOf(ProbeEndpoint("h$index.example")))
        }

        val plan = ServiceProbePlanner.plan(many, tunnel = true, direct = true, random = Random(2))

        assertEquals(ServiceProbeBudget.MAX_REQUESTS, plan.requests.size)
        assertTrue(plan.dropped > 0)
        assertTrue(plan.requests.all { it.path == PathKind.Tunnel })
    }

    @Test
    fun missingPathsAreNotPlanned() {
        assertTrue(ServiceProbePlanner.plan(defaults, tunnel = false, direct = true).requests.all { it.path == PathKind.Direct })
        assertTrue(ServiceProbePlanner.plan(defaults, tunnel = true, direct = false).requests.all { it.path == PathKind.Tunnel })
    }

    /**
     * Инвариант приватности: проверка может обратиться только к адресам каталога
     * и никогда к серверу из профиля (иначе ТСПУ увидит лишние соединения с узлом).
     */
    @Test
    fun plannedTargetsAreInTheCatalogAndNeverAProfileServer() {
        val profile = """
            {"outbounds":[
              {"type":"vless","tag":"de","server":"de.node.example","server_port":443},
              {"type":"hysteria2","tag":"nl","server":"198.51.100.20","server_port":8443},
              {"type":"vless","tag":"v6","server":"2001:db8::7","server_port":443},
              {"type":"selector","tag":"zapret-proxy","outbounds":["de","nl","v6"],"default":"de"},
              {"type":"direct","tag":"direct"}]}
        """.trimIndent()
        val servers = ConfigAnalyzer.outboundDescriptions(profile).values.mapNotNull { it.serverHost }.toSet()
        assertEquals(setOf("de.node.example", "198.51.100.20", "2001:db8::7"), servers)

        for (seed in 0 until 25) {
            for (definitions in listOf(ServiceCatalog.defaults, ServiceCatalog.all)) {
                val plan = ServiceProbePlanner.plan(definitions, true, true, Random(seed))
                assertTrue(plan.hosts.isNotEmpty())
                assertTrue("target outside catalog", ServiceCatalog.allTargets.containsAll(plan.hosts))
                assertEquals(emptySet<String>(), plan.hosts intersect servers)
            }
        }
    }

    @Test
    fun noCatalogTargetLooksLikeAnotherServerOfTheSameCatalog() {
        // Каталог не пересекается сам с собой по IP и именам: ни один адрес не повторён под другой ролью.
        val hosts = ServiceCatalog.all.flatMap { it.endpoints.map(ProbeEndpoint::host) + it.tcp.map(TcpEndpoint::ip) }
        assertNotEquals(0, hosts.size)
        assertEquals(hosts.size, hosts.toSet().size)
    }
}
