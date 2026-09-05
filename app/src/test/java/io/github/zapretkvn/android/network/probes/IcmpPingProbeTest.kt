package io.github.zapretkvn.android.network.probes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcmpPingProbeTest {
    @Test
    fun `echo reply must match address family sequence and payload`() {
        val ipv4Reply = IcmpEchoPacket.request(ipv6 = false, sequence = 7).also { it[0] = 0 }
        assertTrue(IcmpEchoPacket.isMatchingReply(ipv4Reply, ipv4Reply.size, false, 7))
        assertFalse(IcmpEchoPacket.isMatchingReply(ipv4Reply, ipv4Reply.size, false, 8))
        assertFalse(IcmpEchoPacket.isMatchingReply(ipv4Reply, ipv4Reply.size, true, 7))

        val ipv6Reply = IcmpEchoPacket.request(ipv6 = true, sequence = 9).also { it[0] = 129.toByte() }
        assertTrue(IcmpEchoPacket.isMatchingReply(ipv6Reply, ipv6Reply.size, true, 9))
        ipv6Reply[8] = 0
        assertFalse(IcmpEchoPacket.isMatchingReply(ipv6Reply, ipv6Reply.size, true, 9))
    }
}
