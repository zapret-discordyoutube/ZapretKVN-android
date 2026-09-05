package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.network.probes.ExternalIpParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalIpParserTest {
    @Test
    fun `accepts numeric IPv4 and compressed IPv6 only`() {
        assertEquals("203.0.113.9", ExternalIpParser.parse(" 203.0.113.9\n"))
        assertEquals("2001:db8::1", ExternalIpParser.parse("2001:db8::1"))
        assertEquals("::1", ExternalIpParser.parse("::1"))
        assertEquals("2001:db8:1:2:3:4:5:6", ExternalIpParser.parse("2001:db8:1:2:3:4:5:6"))
    }

    @Test
    fun `rejects hostnames markup and malformed addresses`() {
        assertNull(ExternalIpParser.parse("api64.ipify.org"))
        assertNull(ExternalIpParser.parse("<html>error</html>"))
        assertNull(ExternalIpParser.parse("999.0.0.1"))
        assertNull(ExternalIpParser.parse("2001:::1"))
        assertNull(ExternalIpParser.parse("1:2:3"))
    }
}
