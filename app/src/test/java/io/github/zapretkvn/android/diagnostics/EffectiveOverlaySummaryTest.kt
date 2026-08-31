package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveOverlaySummaryTest {
    @Test
    fun `summary contains managed structure but no endpoints matches or credentials`() {
        val runtime = """
            {
              "inbounds":[{"type":"tun","address":["172.19.0.1/30","fd00::1/126"],"auto_route":true}],
              "dns":{"servers":[
                {"tag":"user-secret-dns","type":"https","server":"private.example"},
                {"tag":"zapret-doh-1","type":"https","server":"203.0.113.9","detour":"zapret-proxy"},
                {"tag":"zapret-bootstrap-lkg","type":"hosts","predefined":{"vpn.example":["198.51.100.4"]}}
              ],"rules":[{"server":"zapret-doh-1","strategy":"ipv4_only","domain_suffix":["secret.example"]}]},
              "route":{"rules":[
                {"domain_suffix":["private.example"],"outbound":"direct"},
                {"protocol":"dns","action":"hijack-dns"},
                {"package_name":["io.github.zapretkvn.android.debug"],"network":"tcp","port":443,"action":"sniff","sniffer":["tls"],"timeout":"2s"},
                {"domain":["cp.cloudflare.com","connectivitycheck.gstatic.com","dns.opendns.com"],"action":"route","outbound":"server"},
                {"rule_set":["zapret-ru"],"outbound":"zapret-proxy"}
              ],"rule_set":[{"tag":"zapret-ru","type":"local","format":"binary","path":"/secret/geo.srs"}]},
              "endpoints":[{
                "type":"wireguard","tag":"zapret-proxy","mtu":1280,
                "address":["10.8.0.2/32"],"private_key":"top-secret",
                "detour":"zapret-wireguard-direct",
                "peers":[{"public_key":"top-secret","allowed_ips":["0.0.0.0/0"]}]
              }],
              "outbounds":[{"type":"vless","tag":"server","server":"vpn.example","uuid":"123e4567-e89b-12d3-a456-426614174000","password":"top-secret"}]
            }
        """.trimIndent()

        val text = EffectiveOverlaySummary.create(runtime, DnsMode.Automatic)
        val summary = JsonConfig.parse(text) as JsonObject

        assertFalse("private.example" in text)
        assertFalse("secret.example" in text)
        assertFalse("203.0.113.9" in text)
        assertFalse("198.51.100.4" in text)
        assertFalse("123e4567-e89b-12d3-a456-426614174000" in text)
        assertFalse("top-secret" in text)
        assertTrue(((summary["tun"] as JsonObject)["ipv4"] as JsonPrimitive).boolean)
        assertTrue(((summary["tun"] as JsonObject)["ipv6"] as JsonPrimitive).boolean)
        val vpnHiding = summary["vpn_hiding"] as JsonObject
        assertEquals("0", (vpnHiding["non_tun_inbound_count"] as JsonPrimitive).content)
        assertFalse((vpnHiding["local_control_endpoint_present"] as JsonPrimitive).boolean)
        assertEquals(2, (summary["dns_servers"] as JsonArray).size)
        assertEquals("3", (summary["dns_total_server_count"] as JsonPrimitive).content)
        assertEquals("1", (summary["dns_profile_server_count"] as JsonPrimitive).content)
        assertFalse((summary["dns_android_fallback_active"] as JsonPrimitive).boolean)
        assertEquals(
            "wireguard",
            ((summary["proxy_transport_types"] as JsonArray).single() as JsonPrimitive).content,
        )
        assertEquals("1", (summary["wireguard_endpoint_count"] as JsonPrimitive).content)
        assertEquals("1", (summary["wireguard_android_engine_count"] as JsonPrimitive).content)
        assertEquals(
            "1280",
            ((summary["wireguard_mtu_values"] as JsonArray).single() as JsonPrimitive).content,
        )
        assertEquals("1", (summary["wireguard_peer_count"] as JsonPrimitive).content)
        assertEquals("1", (summary["wireguard_client_bind_detour_count"] as JsonPrimitive).content)
        assertEquals("0", (summary["wireguard_custom_detour_count"] as JsonPrimitive).content)
        assertTrue((summary["wireguard_allowed_ipv4_default"] as JsonPrimitive).boolean)
        assertFalse((summary["wireguard_allowed_ipv6_default"] as JsonPrimitive).boolean)
        assertEquals("1", (summary["wireguard_local_ipv4_count"] as JsonPrimitive).content)
        assertEquals("0", (summary["wireguard_local_ipv6_count"] as JsonPrimitive).content)
        assertTrue((summary["proxy_ipv4_only"] as JsonPrimitive).boolean)
        assertEquals("3", (summary["route_rule_count"] as JsonPrimitive).content)
        assertEquals("1", (summary["health_probe_route_count"] as JsonPrimitive).content)
        assertEquals("1", (summary["health_probe_tls_sniff_count"] as JsonPrimitive).content)
        assertEquals("1", (summary["bootstrap_address_count"] as JsonPrimitive).content)
        assertTrue((summary["bootstrap_hosts_overlay"] as JsonPrimitive).boolean)
        assertFalse("bootstrap_lkg" in summary)
    }

    @Test
    fun `inline and json redaction remove supported credential shapes`() {
        val source = """
            {"uuid":"123e4567-e89b-12d3-a456-426614174000","password":"pw","url":"https://user:pw@example.test/x?token=abc","message":"Authorization: Bearer header-secret; Bearer standalone-secret","ending":"Authorization: Bearer end-secret","assignment":"token=last-secret"}
        """.trimIndent()

        val redacted = SecretRedactor.redact(source)

        assertFalse("123e4567-e89b-12d3-a456-426614174000" in redacted)
        assertFalse("\"pw\"" in redacted)
        assertFalse("user:pw" in redacted)
        assertFalse("token=abc" in redacted)
        assertFalse("header-secret" in redacted)
        assertFalse("standalone-secret" in redacted)
        assertFalse("end-secret" in redacted)
        assertFalse("last-secret" in redacted)
        assertTrue(SecretRedactor.MASK in redacted)
        assertTrue(JsonConfig.parse(redacted) is JsonObject)
    }

    @Test
    fun `plain diagnostic text is not reformatted as a json scalar`() {
        assertEquals("test_override", SecretRedactor.redact("test_override"))
        assertEquals("test_override", DiagnosticReportRedactor.redact("test_override"))
    }

    @Test
    fun `report redaction also removes package host and IP metadata from log text`() {
        val source = """
            {"created":"2026-07-22T10:23:45.123Z","message":"com.example.hidden failed at vpn.private.example, 203.0.113.7 and 2001:db8::7"}
        """.trimIndent()

        val redacted = DiagnosticReportRedactor.redact(source)

        assertFalse("com.example.hidden" in redacted)
        assertFalse("vpn.private.example" in redacted)
        assertFalse("203.0.113.7" in redacted)
        assertFalse("2001:db8::7" in redacted)
        assertTrue("2026-07-22T10:23:45.123Z" in redacted)
    }
}
