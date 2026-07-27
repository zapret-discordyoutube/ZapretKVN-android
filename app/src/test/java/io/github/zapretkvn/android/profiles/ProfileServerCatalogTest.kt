package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileServerCatalogTest {
    @Test
    fun `subscription exposes every server with endpoint and current selection`() {
        val summary = ProfileServerCatalog.summarize(subscriptionJson())
        val group = summary.groups.single()

        assertEquals(ConfigAnalyzer.MANAGED_SELECTOR_TAG, group.tag)
        assertEquals(3, summary.serverCount)
        assertTrue(summary.switchable)
        assertEquals(group.options.first().tag, group.selected)
        assertEquals(group.selected, summary.selectedLabel)
        assertEquals(
            listOf("one.example:443", "two.example:8443", "three.example:443"),
            group.options.map { it.endpoint },
        )
        assertTrue(group.options.all { it.type == "vless" })
        assertTrue(group.options.first().subtitle.startsWith("VLESS · "))
    }

    @Test
    fun `selecting a server in the stored json moves the selection`() {
        val json = subscriptionJson()
        val second = ProfileServerCatalog.summarize(json).groups.single().options[1].tag

        val updated = ConfigAnalyzer.selectServer(json, ConfigAnalyzer.MANAGED_SELECTOR_TAG, second)
        val summary = ProfileServerCatalog.summarize(updated)

        assertEquals(second, summary.groups.single().selected)
        assertEquals(second, summary.selectedLabel)
        assertEquals(3, summary.serverCount)
    }

    @Test
    fun `selector without default falls back to the first member`() {
        val json = """
            {
              "outbounds": [
                {"type": "vless", "tag": "a", "server": "a.example", "server_port": 443},
                {"type": "vless", "tag": "b", "server": "b.example", "server_port": 443},
                {"type": "selector", "tag": "manual", "outbounds": ["a", "b"]}
              ],
              "route": {"final": "manual"}
            }
        """.trimIndent()

        val group = ProfileServerCatalog.summarize(json).groups.single()

        assertEquals("manual", group.tag)
        assertEquals("a", group.selected)
    }

    @Test
    fun `profile without selector offers nothing to switch`() {
        val summary = ProfileServerCatalog.summarize(
            """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}""",
        )

        assertTrue(summary.groups.isEmpty())
        assertFalse(summary.switchable)
        assertEquals(0, summary.serverCount)
        assertNull(summary.selectedLabel)
    }

    @Test
    fun `broken json never crashes the profile list`() {
        val summary = ProfileServerCatalog.summarize("not json at all")

        assertTrue(summary.groups.isEmpty())
        assertFalse(summary.switchable)
    }

    private fun subscriptionJson(): String {
        val links = listOf(
            "vless://11111111-1111-4111-8111-111111111111@one.example:443?security=tls#Server%20One",
            "vless://22222222-2222-4222-8222-222222222222@two.example:8443?security=tls#Server%20Two",
            "vless://33333333-3333-4333-8333-333333333333@three.example:443?security=tls#Server%20Three",
        ).joinToString("\n")
        val candidate = ImportParser.parse(links, ProfileSource.File) as ImportCandidate.Managed
        return candidate.buildJson()
    }
}
