package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.importer.ImportParser
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedProfileFactoryTest {
    @Test
    fun `single server has managed selector and default in real json`() {
        val raw = ManagedProfileFactory.single(vless("Primary"))
        val selectors = ConfigAnalyzer.selectorGroups(raw)

        assertEquals(1, selectors.size)
        assertEquals("zapret-proxy", selectors.single().tag)
        assertEquals(selectors.single().outbounds.single(), selectors.single().default)
        val root = JsonConfig.parse(raw) as JsonObject
        val tun = (root["inbounds"] as JsonArray).single() as JsonObject
        val route = root["route"] as JsonObject
        val selector = (root["outbounds"] as JsonArray)
            .map { it as JsonObject }
            .first { (it["type"] as? JsonPrimitive)?.contentOrNull == "selector" }
        assertTrue((tun["auto_route"] as JsonPrimitive).boolean)
        assertTrue((route["auto_detect_interface"] as JsonPrimitive).boolean)
        assertTrue((selector["interrupt_exist_connections"] as JsonPrimitive).boolean)
    }

    @Test
    fun `subscription tags are stable unique and contain no credentials`() {
        val secretUuid = "11111111-1111-4111-8111-111111111111"
        val servers = listOf(
            vless("Same name", "one.example", secretUuid),
            vless("Same name", "two.example", "22222222-2222-4222-8222-222222222222"),
        )
        val first = ManagedProfileFactory.stableTags(servers)
        val second = ManagedProfileFactory.stableTags(servers)

        assertEquals(first, second)
        assertEquals(2, first.distinct().size)
        assertTrue(first.all { it.startsWith("same-name-") })
        assertFalse(first.any { secretUuid in it })

        val raw = ManagedProfileFactory.subscription(servers)
        val selector = ConfigAnalyzer.selectorGroups(raw).single()
        assertEquals(first, selector.outbounds)
        assertTrue(selector.default in selector.outbounds)
    }

    @Test
    fun `credential-shaped display name cannot become a server tag`() {
        val uuid = "11111111-1111-4111-8111-111111111111"
        val tags = ManagedProfileFactory.stableTags(listOf(vless(uuid, uuid = uuid)))

        assertEquals(listOf("server"), tags)
        assertFalse(uuid in tags.single())
    }

    @Test
    fun `split member key ignores rotated credentials but changes with endpoint`() {
        val old = vless("Server", "one.example", "11111111-1111-4111-8111-111111111111")
        val rotated = vless("Renamed", "one.example", "22222222-2222-4222-8222-222222222222")
        val moved = vless("Server", "two.example", "11111111-1111-4111-8111-111111111111")

        assertEquals(
            ManagedProfileFactory.stableMemberKeys(listOf(old)),
            ManagedProfileFactory.stableMemberKeys(listOf(rotated)),
        )
        assertFalse(
            ManagedProfileFactory.stableMemberKeys(listOf(old)) ==
                ManagedProfileFactory.stableMemberKeys(listOf(moved)),
        )
    }

    @Test
    fun `duplicate split members get distinct stable keys`() {
        val duplicate = vless("Same", "one.example")
        val keys = ManagedProfileFactory.stableMemberKeys(listOf(duplicate, duplicate))

        assertEquals(2, keys.distinct().size)
        assertEquals(keys, ManagedProfileFactory.stableMemberKeys(listOf(duplicate, duplicate)))
    }

    @Test
    fun `base64 subscription creates one selector with all links`() {
        val links = listOf(
            "vless://11111111-1111-4111-8111-111111111111@one.example:443?security=tls#One",
            "trojan://super-secret@two.example:443?sni=two.example#Two",
        ).joinToString("\n")
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(links.toByteArray())
        val candidate = ImportParser.parse(encoded, ProfileSource.Clipboard)
        candidate as io.github.zapretkvn.android.importer.ImportCandidate.Managed

        assertEquals(2, candidate.servers.size)
        assertEquals(2, ConfigAnalyzer.selectorGroups(candidate.buildJson()).single().outbounds.size)
    }

    @Test
    fun `non-latin display names stay readable in server tags`() {
        val tags = ManagedProfileFactory.stableTags(
            listOf(vless("🇳🇱 Нидерланды #1", "nl.example"), vless("Германия", "de.example")),
        )

        assertEquals(listOf("нидерланды-1", "германия"), tags)
    }

    @Test
    fun `adding managed selector is always explicit`() {
        val raw = """{"outbounds":[{"type":"direct","tag":"server-a"}],"unknown":42}"""
        assertTrue(ConfigAnalyzer.selectorGroups(raw).isEmpty())

        val updated = ConfigAnalyzer.addManagedSelector(raw, listOf("server-a"))
        assertEquals(42, ((JsonConfig.parse(updated) as JsonObject)["unknown"] as JsonPrimitive).content.toInt())
        assertEquals("zapret-proxy", ConfigAnalyzer.selectorGroups(updated).single().tag)
    }

    private fun vless(
        name: String,
        host: String = "vpn.example",
        uuid: String = "11111111-1111-4111-8111-111111111111",
    ) = ProtocolOutboundBuilders.vless(
        displayName = name,
        server = host,
        serverPort = 443,
        uuid = uuid,
        tls = TlsSettings(enabled = true, serverName = host),
    )
}
