package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.config.ProxyIpFamily
import io.github.zapretkvn.android.network.probes.selectHealthAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAddressSelectorTest {
    @Test
    fun `IPv4-only WireGuard never selects an IPv6 health address`() {
        val addresses = listOf(
            InetAddress.getByName("2001:db8::10"),
            InetAddress.getByName("192.0.2.10"),
        )

        assertTrue(selectHealthAddress(addresses, ProxyIpFamily.Ipv4Only) is Inet4Address)
        assertTrue(selectHealthAddress(addresses, ProxyIpFamily.Ipv6Only) is Inet6Address)
    }

    @Test
    fun `missing required family is explicit instead of silently crossing families`() {
        val ipv6OnlyAnswer = listOf(InetAddress.getByName("2001:db8::10"))

        assertNull(selectHealthAddress(ipv6OnlyAnswer, ProxyIpFamily.Ipv4Only))
    }
}
