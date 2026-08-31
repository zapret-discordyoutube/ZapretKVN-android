package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.config.OutboundDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRuntimeContextTest {
    @Test
    fun `known libbox outbound marker resolves to opaque target context`() {
        val map = DiagnosticRuntimeMap.create(
            profileId = "profile-id",
            profileName = "Hysteria production",
            descriptions = mapOf(
                "hy2-prod" to OutboundDescription(
                    tag = "hy2-prod",
                    type = "hysteria2",
                    serverHost = "vpn.example",
                    endpoint = "vpn.example:8443",
                ),
            ),
            selectedRawTag = "hy2-prod",
        )

        val target = map.resolve("outbound/hysteria2[hy2-prod]: authentication failed")

        assertEquals("Hysteria production", target.profileName)
        assertEquals("hy2-prod", target.outboundTag)
        assertEquals("hysteria2", target.protocol)
        assertTrue(target.profileRef.orEmpty().startsWith("profile-"))
        assertTrue(target.endpoint.orEmpty().startsWith("ep-"))
        assertTrue(target.endpoint.orEmpty().endsWith(":8443"))
        assertFalse(target.endpoint.orEmpty().contains("vpn.example"))
    }

    @Test
    fun `unsafe profile and outbound labels become opaque or absent`() {
        val map = DiagnosticRuntimeMap.create(
            profileId = "profile-id",
            profileName = "hysteria2://secret@vpn.example:443?auth=secret#name",
            descriptions = mapOf(
                "hysteria2://secret@vpn.example:443" to OutboundDescription(
                    tag = "hysteria2://secret@vpn.example:443",
                    type = "hysteria2",
                    serverHost = "vpn.example",
                    endpoint = "vpn.example:443",
                ),
            ),
            selectedRawTag = "hysteria2://secret@vpn.example:443",
        )

        val target = map.resolve("outbound/hysteria2[hysteria2://secret@vpn.example:443]")

        assertNull(target.profileName)
        assertTrue(target.outboundTag.orEmpty().startsWith("tag-"))
        assertNotNull(target.endpoint)
        assertFalse(target.outboundTag.orEmpty().contains("secret"))
        assertFalse(target.endpoint.orEmpty().contains("vpn.example"))
        assertFalse(target.endpoint.orEmpty().contains("secret"))
    }

    @Test
    fun `hysteria uri and transport secrets are removed as a whole`() {
        val uri = "hysteria2://auth-secret@vpn.example:8443,20000-20002" +
            "?obfs=salamander&obfs-password=obfs-secret" +
            "&pinSHA256=deadbeef&ech=ech-secret&vendor=one;two#display-name"

        val redacted = SecretRedactor.redactInline(uri)

        assertEquals(SecretRedactor.MASK, redacted)
        assertFalse("hysteria2://" in redacted)
        assertFalse("auth-secret" in redacted)
        assertFalse("obfs-secret" in redacted)
        assertFalse("deadbeef" in redacted)
        assertFalse("ech-secret" in redacted)
        assertFalse("display-name" in redacted)
        assertFalse("20000-20002" in redacted)
        assertFalse("one;two" in redacted)
    }

    @Test
    fun `json key variants redact pins ech certificates and keys`() {
        val source = """
            {
              "pinSHA256":"pin-secret",
              "certificate_sha256":"certificate-secret",
              "ech":{"config":"ech-secret"},
              "privateKey":"private-secret",
              "public_key":"public-secret",
              "presharedKey":"psk-secret"
            }
        """.trimIndent()

        val redacted = SecretRedactor.redact(source)

        listOf(
            "pin-secret",
            "certificate-secret",
            "ech-secret",
            "private-secret",
            "public-secret",
            "psk-secret",
        ).forEach { assertFalse("secret leaked: $it", it in redacted) }
        assertTrue(SecretRedactor.MASK in redacted)
    }

    @Test
    fun `certificate and key paths are removed from json and inline logs`() {
        val source = """
            {
              "certificate_path":"/storage/emulated/0/private/ca.pem",
              "client_certificate_path":"/storage/emulated/0/private/client.pem",
              "client_key_path":"/storage/emulated/0/private/client.key",
              "ech_config_path":"/storage/emulated/0/private/ech.pem"
            }
        """.trimIndent()
        val inline = "client_key_path=/storage/emulated/0/private/client.key " +
            "ech_config_path: /storage/emulated/0/private/ech.pem"

        val redactedJson = SecretRedactor.redact(source)
        val redactedInline = SecretRedactor.redactInline(inline)

        assertFalse("/storage" in redactedJson)
        assertFalse("/storage" in redactedInline)
        assertTrue(SecretRedactor.MASK in redactedJson)
        assertTrue(SecretRedactor.MASK in redactedInline)
    }
}
