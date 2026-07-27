package io.github.zapretkvn.android.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlyingNetworkReadinessTest {
    @Test
    fun `network without a resolved interface is not usable`() {
        assertFalse(UnderlyingNetworkReadiness.usable(hasNetwork = false, interfaceIndex = 12))
        assertFalse(UnderlyingNetworkReadiness.usable(hasNetwork = true, interfaceIndex = -1))
        assertTrue(UnderlyingNetworkReadiness.usable(hasNetwork = true, interfaceIndex = 12))
    }

    @Test
    fun `wifi that android has not finished configuring is usable but not settled`() {
        val settled = UnderlyingNetworkReadiness.settled(
            hasNetwork = true,
            interfaceIndex = 12,
            validated = false,
            captivePortal = false,
            dnsServerCount = 0,
        )
        assertFalse(settled)
        assertTrue(UnderlyingNetworkReadiness.usable(hasNetwork = true, interfaceIndex = 12))
    }

    @Test
    fun `validated network with resolvers is settled`() {
        assertTrue(
            UnderlyingNetworkReadiness.settled(
                hasNetwork = true,
                interfaceIndex = 12,
                validated = true,
                captivePortal = false,
                dnsServerCount = 2,
            ),
        )
    }

    @Test
    fun `captive portal counts as settled because android will not validate it`() {
        assertTrue(
            UnderlyingNetworkReadiness.settled(
                hasNetwork = true,
                interfaceIndex = 12,
                validated = false,
                captivePortal = true,
                dnsServerCount = 1,
            ),
        )
    }
}
