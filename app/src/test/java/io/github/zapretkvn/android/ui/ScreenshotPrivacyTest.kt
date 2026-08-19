package io.github.zapretkvn.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPrivacyTest {
    @Test
    fun `server endpoint is replaced with stars only when privacy is enabled`() {
        assertEquals(
            ScreenshotPrivacy.MASK,
            ScreenshotPrivacy.serverEndpoint("vpn.example:443", hidden = true),
        )
        assertEquals(
            "vpn.example:443",
            ScreenshotPrivacy.serverEndpoint("vpn.example:443", hidden = false),
        )
        assertEquals(null, ScreenshotPrivacy.serverEndpoint(null, hidden = true))
        assertEquals(
            ScreenshotPrivacy.MASK,
            ScreenshotPrivacy.serverLabel("WireGuard · vpn.example:51820", hidden = true),
        )
        assertEquals("Frankfurt #2", ScreenshotPrivacy.serverLabel("Frankfurt #2", hidden = true))
    }

    @Test
    fun `json view masks nested server addresses and urls but keeps structure`() {
        val redacted = ScreenshotPrivacy.redactServerAddressesInJson(
            """
                {
                  "outbounds": [{
                    "type": "vless",
                    "tag": "Friendly server",
                    "server": "vpn.example",
                    "server_port": 443,
                    "tls": {"server_name": "sni.example"},
                    "transport": {"host": ["cdn.example"]}
                  }],
                  "route": {"rule_set": [{"url": "https://rules.example/list.srs"}]}
                }
            """.trimIndent(),
        )

        assertTrue(redacted.contains(ScreenshotPrivacy.MASK))
        assertTrue(redacted.contains("Friendly server"))
        assertTrue(redacted.contains("server_port"))
        assertFalse(redacted.contains("vpn.example"))
        assertFalse(redacted.contains("sni.example"))
        assertFalse(redacted.contains("cdn.example"))
        assertFalse(redacted.contains("rules.example"))
        assertEquals(
            ScreenshotPrivacy.MASK,
            ScreenshotPrivacy.redactServerAddressesInJson("invalid vpn.example"),
        )
        assertTrue(UiSettings().hideServerAddresses)
    }
}
