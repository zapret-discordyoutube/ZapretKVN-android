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

    @Test
    fun `host key is lowercase without port brackets zone or trailing dot`() {
        assertEquals("a.example", endpointHostKey("A.Example.:443"))
        assertEquals("2001:db8::7", endpointHostKey("[2001:DB8::7]:443"))
        assertEquals("fe80::1", endpointHostKey("[fe80::1%wlan0]:443"))
        assertNull(endpointHostKey("  "))
        assertNull(endpointHostKey(null))
    }

    @Test
    fun `name words win over geoip, geoip wins over sibling, flag wins over all`() {
        val servers = listOf(
            "VLESS 🇳🇱 Amsterdam-2" to "185.12.34.56:443",
            // Тот же адрес, что у соседа с флагом NL, но таблица говорит DE: таблица главнее соседа.
            "Hysteria 2" to "185.12.34.56:8443",
            // В имени слово «Finland», по адресу — SE: адрес — это вход (фронт, CDN), имя главнее.
            "Finland fast" to "Edge.Example.com:443",
            // Флаг в имени остаётся как есть и в подсказки не попадает.
            "VLESS 🇫🇮 Helsinki" to "edge.example.com:8443",
            // Таблицы по адресу нет: работают прежние догадки по имени.
            "Frankfurt 3" to "203.0.113.9:443",
        )
        val geo = mapOf("185.12.34.56" to "DE", "edge.example.com" to "SE")
        val result = inferCountries(servers, geo)
        assertEquals("DE", result["Hysteria 2"])
        assertEquals("FI", result["Finland fast"])
        assertNull(result["VLESS 🇫🇮 Helsinki"])
        assertNull(result["VLESS 🇳🇱 Amsterdam-2"])
        assertEquals("DE", result["Frankfurt 3"])
    }

    @Test
    fun `server behind a front abroad keeps the country from its name`() {
        // 16.10.2026: латвийский узел за входом в Финляндии показывался в группе Финляндии.
        val servers = listOf(
            "Riga · VLESS WS · TLS" to "front.example.net:443",
            "Hiprig · VLESS WS · TLS" to "front.example.net:8443",
        )
        val result = inferCountries(servers, mapOf("front.example.net" to "FI"))
        assertEquals("LV", result["Riga · VLESS WS · TLS"])
        // Имя без страны: остаётся только адрес входа — тут приложение знать не может.
        assertEquals("FI", result["Hiprig · VLESS WS · TLS"])
    }

    @Test
    fun `protocol and size abbreviations are not countries`() {
        assertNull(CountryGuess.fromName("Hiprig · SS"))
        assertNull(CountryGuess.fromName("Traffic 100 GB left"))
        assertNull(CountryGuess.fromName("Node ID 7"))
        assertEquals("GB", CountryGuess.fromName("GB London"))
    }

    @Test
    fun `without geoip behaviour is unchanged`() {
        val result = inferCountries(listOf("Hysteria 2" to "185.12.34.56:8443", "x" to null))
        assertNull(result["Hysteria 2"])
        assertNull(result["x"])
    }
}
