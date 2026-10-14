package io.github.zapretkvn.android.ui.servers

import io.github.zapretkvn.android.profiles.ProfileMetadata
import io.github.zapretkvn.android.profiles.ProfileServerGroup
import io.github.zapretkvn.android.profiles.ProfileServerOption
import io.github.zapretkvn.android.profiles.ProfileServerSummary
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Страна сервера: флаг в имени → GeoIP адреса → сосед на том же адресе → слова в имени. */
class ServerCountryPresentationTest {
    private val now = 1_000_000_000L

    private fun offline(
        options: List<ProfileServerOption>,
        geo: Map<String, String>,
        hide: Boolean = false,
    ): Map<String, ServerRowUi> {
        val summary = ProfileServerSummary(listOf(ProfileServerGroup("zapret-proxy", options.first().tag, options)))
        return offlineServerGroups(summary, emptyMap(), hide, now, geo).single().rows.associateBy { it.tag }
    }

    private fun option(tag: String, endpoint: String?, type: String = "vless") =
        ProfileServerOption(tag, type, endpoint)

    @Test
    fun `server with no flag in its name gets the country of its address`() {
        val rows = offline(
            listOf(option("srv-17", "95.216.1.1:443"), option("srv-18", "Edge.Example.com:443")),
            geo = mapOf("95.216.1.1" to "FI", "edge.example.com" to "DE"),
        )
        assertEquals("🇫🇮", rows.getValue("srv-17").flag)
        assertEquals("FI", rows.getValue("srv-17").countryCode)
        assertEquals("Финляндия", rows.getValue("srv-17").countryName)
        assertEquals("🇩🇪", rows.getValue("srv-18").flag)
    }

    @Test
    fun `precedence is name flag then address then sibling then words in the name`() {
        val rows = offline(
            listOf(
                option("🇳🇱 Amsterdam", "185.12.34.56:443"), // флаг в имени
                option("hy2 node", "185.12.34.56:8443", "hysteria2"), // сосед по адресу: NL, но адрес главнее
                option("Finland fast", "203.0.113.50:443"), // слово в имени: FI, но адрес главнее
                option("Frankfurt 3", "203.0.113.51:443"), // адреса в таблице нет: слово в имени
                option("Hysteria 2", "185.12.99.1:443", "hysteria2"), // ничего не известно
            ),
            geo = mapOf("185.12.34.56" to "DE", "203.0.113.50" to "SE"),
        )
        assertEquals("NL", rows.getValue("🇳🇱 Amsterdam").countryCode)
        assertEquals("DE", rows.getValue("hy2 node").countryCode)
        assertEquals("SE", rows.getValue("Finland fast").countryCode)
        assertEquals("DE", rows.getValue("Frankfurt 3").countryCode)
        assertNull(rows.getValue("Hysteria 2").countryCode)
    }

    @Test
    fun `sibling on the same address still works when the table has no answer`() {
        val rows = offline(
            listOf(option("VLESS 🇳🇱 Amsterdam-2", "185.12.34.56:443"), option("Hysteria 2", "185.12.34.56:8443", "hysteria2")),
            geo = emptyMap(),
        )
        assertEquals("NL", rows.getValue("Hysteria 2").countryCode)
    }

    @Test
    fun `hidden addresses still give a flag and the visible text stays masked`() {
        // «Ports.nl», «de1.example.com» выглядят как адрес: надпись скрывается, страна остаётся.
        val rows = offline(
            listOf(
                option("🇩🇪 de1.example.com", "46.4.1.1:443"),
                option("Ports.nl", "95.216.1.1:443"),
                option("plain name", "[2a01:4f8::1]:443"),
            ),
            geo = mapOf("46.4.1.1" to "DE", "95.216.1.1" to "FI", "2a01:4f8::1" to "DE"),
            hide = true,
        )
        val flagged = rows.getValue("🇩🇪 de1.example.com")
        assertEquals("********", flagged.title)
        assertEquals("🇩🇪", flagged.flag)
        assertEquals("DE", flagged.countryCode)

        val masked = rows.getValue("Ports.nl")
        assertEquals("********", masked.title)
        assertEquals("🇫🇮", masked.flag)
        assertEquals("FI", masked.countryCode)
        assertFalse(masked.searchText.contains("ports"))
        assertFalse(masked.a11y.contains("Ports"))
        assertTrue(masked.a11y.contains("Финляндия"))

        val plain = rows.getValue("plain name")
        assertEquals("plain name", plain.title)
        assertEquals("🇩🇪", plain.flag)
        rows.values.forEach { assertNull("address must not be shown", it.endpoint) }
        rows.values.forEach {
            assertFalse(it.a11y.contains("46.4.1.1") || it.searchText.contains("46.4.1.1"))
        }
    }

    @Test
    fun `live groups use the same precedence`() {
        val group = RuntimeSelectorGroup(
            tag = "proxy",
            type = "selector",
            selected = "a",
            selectable = true,
            items = listOf(
                RuntimeOutboundItem("a", "vless", "95.216.1.1:443"),
                RuntimeOutboundItem("🇳🇱 b", "vless", "95.216.1.1:8443"),
            ),
        )
        val rows = liveServerGroups(listOf(group), true, now, mapOf("95.216.1.1" to "FI")).single().rows.associateBy { it.tag }
        assertEquals("FI", rows.getValue("a").countryCode)
        assertEquals("NL", rows.getValue("🇳🇱 b").countryCode)
    }

    @Test
    fun `most servers without flags are no longer all in Others`() {
        // Профиль из жизни: 30 серверов, у 28 в имени нет ни флага, ни страны.
        val geo = HashMap<String, String>()
        val options = (1..30).map { number ->
            val ip = "203.0.113.$number"
            val country = when {
                number <= 14 -> "DE"
                number <= 22 -> "NL"
                number <= 27 -> "FI"
                else -> null
            }
            if (country != null) geo[ip] = country
            option(if (number == 1) "🇩🇪 Berlin" else "srv-$number", "$ip:443")
        }
        val profile = ProfileMetadata("p", "Подписка", ProfileSource.Url, now, now)
        val withoutGeo = buildServersUi(
            listOf(profile), "p", mapOf("p" to ProfileServerSummary(listOf(ProfileServerGroup("g", "srv-2", options)))),
            emptyMap(), emptyList(), false, true, now,
        ) as ServersUi.Content
        assertEquals(1, withoutGeo.groups.single().rows.count { it.countryCode != null })

        val withGeo = buildServersUi(
            listOf(profile), "p", mapOf("p" to ProfileServerSummary(listOf(ProfileServerGroup("g", "srv-2", options)))),
            emptyMap(), emptyList(), false, true, now, geo,
        ) as ServersUi.Content
        val rows = withGeo.groups.single().rows
        assertEquals(27, rows.count { it.countryCode != null })
        val sections = countrySections("g", rows)!!
        assertEquals(listOf("DE", "NL", "FI", null), sections.map { it.code })
        assertEquals(3, sections.last().rows.size)
        assertEquals(OTHER_SECTION_TITLE, sections.last().title)
        assertEquals("30 серверов · 3 страны", withGeo.header.counts)
        assertEquals("🇩🇪 🇳🇱 🇫🇮", withGeo.header.flags)
        val chips = serverChips(withGeo.groups)
        assertNotNull(chips.primary.firstOrNull { it.chip == ServerChip.Country("NL") })
    }

    @Test
    fun `profile cards show flags from addresses`() {
        val summary = ProfileServerSummary(
            listOf(
                ProfileServerGroup(
                    "g", "a",
                    listOf(option("a", "95.216.1.1:443"), option("b", "46.4.1.1:443"), option("c", "46.4.1.2:443")),
                ),
            ),
        )
        val profile = ProfileMetadata("p", "Подписка", ProfileSource.Url, now, now)
        val geo = mapOf("95.216.1.1" to "FI", "46.4.1.1" to "DE", "46.4.1.2" to "DE")
        val card = profileSections(listOf(profile), mapOf("p" to summary), "p", emptySet(), now, geo)
            .single().cards.single()
        assertEquals("3 сервера · 2 страны", card.counts)
        assertEquals("🇫🇮 🇩🇪", card.flags)
        val bare = profileSections(listOf(profile), mapOf("p" to summary), "p", emptySet(), now).single().cards.single()
        assertEquals("3 сервера", bare.counts)
    }
}
