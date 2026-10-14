package io.github.zapretkvn.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsOverrideTest {
    @Test
    fun `normalizes exact hostname and IPv4 without DNS lookup`() {
        val value = DnsOverride.normalizedOrNull(" NTC.PARTY. ", " 130.255.77.28 ")

        assertNotNull(value)
        assertEquals("ntc.party", value?.hostname)
        assertEquals("130.255.77.28", value?.ipv4Address)
    }

    @Test
    fun `rejects malformed hostname and IPv4`() {
        assertNull(DnsOverride.normalizedOrNull("party", "130.255.77.28"))
        assertNull(DnsOverride.normalizedOrNull("bad host.party", "130.255.77.28"))
        assertNull(DnsOverride.normalizedOrNull("ntc.party", "130.255.77.999"))
        assertNull(DnsOverride.normalizedOrNull("ntc.party", "130.025.77.28"))
    }
}
