package io.github.zapretkvn.core.uimodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryGuessTest {
    @Test
    fun `country codes count only when they look deliberate`() {
        assertEquals("NL", CountryGuess.fromName("NL Hysteria 1"))
        assertEquals("DE", CountryGuess.fromName("de-fra-2"))
        assertEquals("US", CountryGuess.fromName("us-vdsryzen-8"))
        assertNull(CountryGuess.fromName("hysteria no-tls"))
        assertNull(CountryGuess.fromName("Hysteria 2"))
        assertEquals("FI", CountryGuess.fromName("Hysteria FI"))
    }

    @Test
    fun `country and city names in russian and english`() {
        assertEquals("NL", CountryGuess.fromName("Hysteria · Нидерланды"))
        assertEquals("NL", CountryGuess.fromName("amsterdam-2"))
        assertEquals("DE", CountryGuess.fromName("Frankfurt Fast"))
        assertEquals("US", CountryGuess.fromName("New York premium"))
        assertEquals("RU", CountryGuess.fromName("Москва резерв"))
    }

    @Test
    fun `host labels give a country but plain IPs do not`() {
        assertEquals("NL", CountryGuess.fromHost("nl1.example.com"))
        assertEquals("SE", CountryGuess.fromHost("stockholm.vpn.example"))
        assertNull(CountryGuess.fromHost("185.12.34.56"))
        assertNull(CountryGuess.fromHost("2001:db8::1"))
        assertEquals("DE", CountryGuess.fromHost("de-fra.vpn.net"))
        assertNull(CountryGuess.fromHost("my.server.io"))
        assertNull(CountryGuess.fromHost("vpn.example.com"))
    }

    @Test
    fun `flag from code`() {
        assertEquals("🇳🇱", CountryGuess.flagOf("NL"))
        assertEquals("🇺🇸", CountryGuess.flagOf("us"))
    }

    @Test
    fun `hysteria on the same node inherits the flag of its vless sibling`() {
        val result = inferCountries(
            listOf(
                "VLESS 🇳🇱 Amsterdam-2" to "185.12.34.56:443",
                "Hysteria 2" to "185.12.34.56:8443",
                "VLESS 🇫🇮 Helsinki" to "[2001:db8::7]:443",
                "Hysteria 2 (2)" to "[2001:db8::7]:20000-30000",
                "Hysteria 2 (3)" to "10.0.0.9:443",
            ),
        )
        assertEquals("NL", result["Hysteria 2"])
        assertEquals("FI", result["Hysteria 2 (2)"])
        assertNull(result["Hysteria 2 (3)"])
        assertNull(result["VLESS 🇳🇱 Amsterdam-2"])
    }

    @Test
    fun `endpoint host parsing`() {
        assertEquals("a.example", endpointHost("a.example:443"))
        assertEquals("2001:db8::7", endpointHost("[2001:db8::7]:443"))
        assertEquals("2001:db8::7", endpointHost("2001:db8::7"))
        assertNull(endpointHost(null))
    }
}
