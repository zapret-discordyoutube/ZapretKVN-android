package io.github.zapretkvn.android.config

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VPnBot nodes reject public DoH resolvers and IP-discovery services by network
 * policy (network-policy-v3). A health probe aimed at them measures the policy,
 * not the tunnel, so the managed list must stay on plain connectivity checks.
 */
class ManagedHealthProbeTest {
    // Mirror of blocked_doh_domain_suffixes plus the public-ip-discovery entries.
    private val policyBlockedSuffixes = listOf(
        "cloudflare-dns.com", "dns.adguard-dns.com", "dns.alidns.com", "dns.google",
        "dns.nextdns.io", "dns.quad9.net", "dns.sb", "dns10.quad9.net", "dns11.quad9.net",
        "dns9.quad9.net", "doh.opendns.com", "doh.pub", "family.cloudflare-dns.com",
        "freedns.controld.com", "mozilla.cloudflare-dns.com", "one.one.one.one",
        "security.cloudflare-dns.com",
        "api.ipify.org", "checkip.amazonaws.com", "ifconfig.me", "ip.mail.ru",
        "ipv4-internet.yandex.net", "ipv6-internet.yandex.net",
    )
    private val forbiddenMarkers =
        Regex("(?:^|[.-])(?:doh|dns|ipify|checkip|ifconfig|myip|whatismyip)(?:[.-]|$)")

    @Test
    fun `health endpoints are connectivity checks outside node policy block classes`() {
        assertTrue("one blocked endpoint must not fail the check", ManagedHealthProbe.endpoints.size >= 2)
        ManagedHealthProbe.endpoints.forEach { endpoint ->
            val uri = URI(endpoint.url)
            assertEquals("https", uri.scheme)
            assertEquals(endpoint.host, uri.host)
            assertEquals("/generate_204", uri.path)
            assertFalse(endpoint.host, endpoint.host.all { it.isDigit() || it == '.' || it == ':' })
            policyBlockedSuffixes.forEach { suffix ->
                assertFalse("${endpoint.host} is blocked as $suffix",
                    endpoint.host == suffix || endpoint.host.endsWith(".$suffix"))
            }
            assertFalse(endpoint.host, forbiddenMarkers.containsMatchIn(endpoint.host))
        }
        assertEquals(ManagedHealthProbe.endpoints.map { it.code }.toSet().size, ManagedHealthProbe.endpoints.size)
    }

    @Test
    fun `hosts stay the single routed set and legacy exports keep a shared anchor`() {
        assertEquals(ManagedHealthProbe.endpoints.map { it.host }, ManagedHealthProbe.hosts)
        ManagedHealthProbe.legacyHostSets.forEach { legacy ->
            // RuntimeConfigBuilder strips previously generated rules by any shared host.
            assertTrue(legacy.any(ManagedHealthProbe.hosts::contains))
        }
    }
}
