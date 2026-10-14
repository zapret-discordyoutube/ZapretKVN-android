package io.github.zapretkvn.android.network.probes.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCatalogTest {
    @Test
    fun defaultsAreTheSixDocumentedServices() {
        assertEquals(
            listOf(
                ServiceId.YouTube, ServiceId.ChatGpt, ServiceId.Gemini,
                ServiceId.Instagram, ServiceId.Discord, ServiceId.Telegram,
            ),
            ServiceCatalog.defaults.map { it.id },
        )
    }

    @Test
    fun everyServiceHasExactlyOnePrimaryAndSaneEndpoints() {
        ServiceCatalog.all.forEach { service ->
            assertEquals(service.id.token, 1, service.endpoints.count { it.role == EndpointRole.Primary })
            service.endpoints.forEach { endpoint ->
                assertFalse("scheme in host", "://" in endpoint.host)
                assertFalse("path in host", '/' in endpoint.host)
                assertTrue(endpoint.path.startsWith("/"))
                endpoint.geo?.let { geo ->
                    assertEquals("markers must be lowercase", geo.markers.map { it.lowercase() }, geo.markers)
                    assertEquals(ProbeMethod.Get, endpoint.method)
                }
            }
        }
        assertEquals(ServiceCatalog.all.size, ServiceCatalog.all.map { it.id }.toSet().size)
    }

    @Test
    fun geoRulesLiveOnlyOnSecondaryApiEndpoints() {
        val geoHosts = ServiceCatalog.all.flatMap { it.endpoints }.filter { it.geo != null }
        assertTrue(geoHosts.isNotEmpty())
        assertTrue(geoHosts.all { it.role == EndpointRole.Secondary })
    }

    @Test
    fun telegramHasOnePinnedTcpTargetAndNoOtherServiceDoes() {
        val withTcp = ServiceCatalog.all.filter { it.tcp.isNotEmpty() }
        assertEquals(listOf(ServiceId.Telegram), withTcp.map { it.id })
        assertEquals(443, withTcp.single().tcp.single().port)
    }
}
