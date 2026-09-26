package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.networkbootstrap.BootstrapFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnErrorExplainerTest {
    private val generic = VpnErrorExplainer.explain("NO-SUCH-CODE")

    @Test
    fun `every code the app can publish has a specific explanation`() {
        val codes = DiagnosticErrorType.entries
            .map { it.supportCode }
            .filterNot { it == DiagnosticErrorType.Unknown.supportCode } +
            BootstrapFailureCode.entries.map { it.value } +
            listOf("NET-102", "NET-110", "DNS-110", "DNS-200", "VPN-120", "VPN-200", "VPN-201") +
            HysteriaFailureCode.entries.map { it.name }
        codes.forEach { code ->
            assertTrue("$code has no explanation", VpnErrorExplainer.isKnown(code))
            val explanation = VpnErrorExplainer.explain(code)
            assertTrue(explanation.title.isNotBlank())
            assertTrue(explanation.hint.isNotBlank())
        }
    }

    @Test
    fun `unknown code falls back to generic advice with diagnostics`() {
        assertFalse(VpnErrorExplainer.isKnown("VPN-000"))
        assertEquals(generic, VpnErrorExplainer.explain("VPN-000"))
        assertTrue("диагностику" in generic.hint)
    }

    @Test
    fun `vpn-200 timeout explains a silent tunnel and mentions UDP only for UDP protocols`() {
        val detail = "verdict=timeout cloudflare:timeout@4003 gstatic:timeout@4001"
        val hysteria = VpnErrorExplainer.explain(
            "VPN-200",
            detail,
            VpnErrorContext(protocol = "hysteria2", hasOtherServers = false),
        )
        assertEquals("Сервер подключился, но интернет через него не идёт", hysteria.title)
        assertTrue("UDP" in hysteria.hint)
        assertTrue("один сервер" in hysteria.hint)

        val vless = VpnErrorExplainer.explain(
            "VPN-200",
            detail,
            VpnErrorContext(protocol = "vless", hasOtherServers = true),
        )
        assertFalse("UDP" in vless.hint)
        assertFalse("VLESS" in vless.hint)
        assertTrue("другой сервер" in vless.hint)
    }

    @Test
    fun `vpn-200 verdicts pick distinct explanations`() {
        val titles = listOf("timeout", "tls", "reset", "dns", "http").map { verdict ->
            VpnErrorExplainer.explain("VPN-200", "verdict=$verdict a:$verdict@1").title
        }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `vpn-200 verdict survives diagnostic truncation and missing detail`() {
        val long = "verdict=tls " + "cloudflare:tls@12 ".repeat(40)
        val truncated = VpnErrorExplainer.explain("VPN-200", long.take(160))
        assertEquals(VpnErrorExplainer.explain("VPN-200", "verdict=tls").title, truncated.title)
        // A pre-verdict build or a missing detail still gets the tunnel explanation.
        val legacy = VpnErrorExplainer.explain("VPN-200", "cloudflare:Read timed out")
        assertNotEquals(generic.title, legacy.title)
    }

    @Test
    fun `code is matched case-insensitively`() {
        assertEquals(VpnErrorExplainer.explain("DNS-110"), VpnErrorExplainer.explain(" dns-110 "))
    }
}
