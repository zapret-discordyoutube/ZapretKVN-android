package io.github.zapretkvn.android.config

import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportParser
import io.github.zapretkvn.android.importer.InsecureTlsImportException
import io.github.zapretkvn.android.importer.ShareLinkParser
import io.github.zapretkvn.android.profiles.ProfileSource
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsVerificationPolicyTest {
    private val pcs = "a".repeat(64)

    private fun outbound(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `verified tls and missing tls are allowed`() {
        assertFalse(TlsVerificationPolicy.violates(outbound("""{"type":"direct"}""")))
        assertFalse(TlsVerificationPolicy.violates(outbound("""{"type":"trojan","tls":{"enabled":true}}""")))
        assertFalse(
            TlsVerificationPolicy.violates(outbound("""{"type":"trojan","tls":{"enabled":true,"insecure":false}}""")),
        )
    }

    @Test
    fun `insecure without pin is a violation for every tls protocol`() {
        listOf("trojan", "vmess", "tuic", "hysteria2", "vless").forEach { type ->
            assertTrue(
                type,
                TlsVerificationPolicy.violates(outbound("""{"type":"$type","tls":{"enabled":true,"insecure":true}}""")),
            )
        }
    }

    @Test
    fun `pinned certificates make insecure acceptable`() {
        assertFalse(
            TlsVerificationPolicy.violates(
                outbound("""{"type":"trojan","tls":{"enabled":true,"insecure":true,"certificate_public_key_sha256":["x"]}}"""),
            ),
        )
        assertFalse(
            TlsVerificationPolicy.violates(
                outbound("""{"type":"hysteria2","certificate_sha256":"ab","tls":{"enabled":true,"insecure":true}}"""),
            ),
        )
        assertFalse(
            TlsVerificationPolicy.violates(
                outbound(
                    """{"type":"hysteria2","uri":"hysteria2://p@h:443?insecure=1&pinSHA256=AB#x",""" +
                        """"tls":{"enabled":true,"insecure":true}}""",
                ),
            ),
        )
        // VLESS с исходной ссылкой идёт в Xray: поле tls ядро не читает (pcs/vcn или обычная проверка).
        assertFalse(
            TlsVerificationPolicy.violates(
                outbound("""{"type":"vless","uri":"vless://u@h:443?security=tls","tls":{"enabled":true,"insecure":true}}"""),
            ),
        )
    }

    @Test
    fun `vless allowInsecure with certificate pin imports and stays verified`() {
        val server = ShareLinkParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@one.example:443" +
                "?type=tcp&security=tls&sni=one.example&allowInsecure=1&pcs=$pcs#One",
        )
        assertFalse(TlsVerificationPolicy.violates(server.outbound))
    }

    @Test
    fun `trojan vmess and tuic that disable verification are refused`() {
        val vmessPayload = Base64.getEncoder().encodeToString(
            (
                """{"v":"2","ps":"VM","add":"vm.example","port":"443","id":"11111111-1111-4111-8111-111111111111",""" +
                    """"net":"tcp","tls":"tls","sni":"vm.example","allowInsecure":true}"""
            ).toByteArray(),
        )
        listOf(
            "trojan://secret@tr.example:443?security=tls&sni=tr.example&allowInsecure=1#TR",
            // Отпечаток pcs для Trojan ядро не применяет: отключение проверки осталось бы без него.
            "trojan://secret@tr.example:443?security=tls&sni=tr.example&allowInsecure=1&pcs=$pcs#TR",
            "vmess://$vmessPayload",
            "tuic://11111111-1111-4111-8111-111111111111:secret@tuic.example:443?sni=tuic.example&insecure=1#TUIC",
        ).forEach { link ->
            assertThrows(link, InsecureTlsImportException::class.java) {
                ImportParser.parse(link, ProfileSource.Link)
            }
        }
    }

    @Test
    fun `subscription skips insecure servers and keeps the rest`() {
        val candidate = ImportParser.parse(
            listOf(
                "trojan://secret@tr.example:443?security=tls&sni=tr.example&allowInsecure=1#Bad",
                "trojan://secret@ok.example:443?security=tls&sni=ok.example#Good",
            ).joinToString("\n"),
            ProfileSource.Subscription,
            "Подписка",
        ) as ImportCandidate.Managed
        assertEquals(1, candidate.servers.size)
        assertTrue(candidate.importWarnings.any { it.contains("отключают проверку сертификата: 1") })
    }

    @Test
    fun `subscription of only insecure servers is refused`() {
        assertThrows(InsecureTlsImportException::class.java) {
            ImportParser.parse(
                listOf(
                    "trojan://secret@a.example:443?security=tls&sni=a.example&allowInsecure=1#A",
                    "trojan://secret@b.example:443?security=tls&sni=b.example&allowInsecure=1#B",
                ).joinToString("\n"),
                ProfileSource.Subscription,
                "Подписка",
            )
        }
    }

    @Test
    fun `stored profile with insecure outbound is not started`() {
        val raw = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"auto_route":true}],
              "outbounds":[
                {"type":"trojan","tag":"server-a","server":"tr.example","server_port":443,"password":"x",
                 "tls":{"enabled":true,"server_name":"tr.example","insecure":true}},
                {"type":"selector","tag":"zapret-proxy","outbounds":["server-a"],"default":"server-a"},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """.trimIndent()
        val result = RuntimeConfigBuilder.build(raw)
        assertTrue(result is RuntimeConfigResult.Invalid)
        assertEquals(TlsVerificationPolicy.FAILURE_CODE, (result as RuntimeConfigResult.Invalid).code)
    }
}
