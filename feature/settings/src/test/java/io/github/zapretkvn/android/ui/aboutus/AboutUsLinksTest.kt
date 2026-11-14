package io.github.zapretkvn.android.ui.aboutus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutUsLinksTest {
    private val botLinks get() = AboutUsLinks.all.filter { it.url.startsWith("https://t.me/zapretvpns_bot") }

    @Test
    fun botLinkCarriesAdLabel() {
        val link = botLinks.single()
        assertEquals("https://t.me/zapretvpns_bot?start=kvnand_community", link.url)
        assertEquals(BOT_COMMUNITY_URL, link.url)
    }

    @Test
    fun adLabelFollowsSourceRules() {
        val label = botLinks.single().url.substringAfter("?start=")
        assertTrue(Regex("[A-Za-z0-9_-]{1,64}").matches(label))
        assertTrue(label.startsWith("kvnand_"))
        assertFalse(label.startsWith("ref_"))
    }

    @Test
    fun botStandsFirstThenChannelsThenResources() {
        assertEquals(AboutUsLinks.bot, AboutUsLinks.all.first())
        assertEquals(AboutUsLinks.channels + AboutUsLinks.resources, AboutUsLinks.all.drop(1))
        assertEquals(
            listOf(
                "https://t.me/bypassblock",
                "https://t.me/vpndiscordyooutube",
                "https://t.me/runetvpnyoutubediscord",
                "https://t.me/zastogram",
            ),
            AboutUsLinks.channels.map { it.url },
        )
    }

    @Test
    fun channelsAreTelegramAndEveryLinkIsUniqueHttps() {
        AboutUsLinks.channels.forEach { assertTrue(it.url, it.url.startsWith("https://t.me/")) }
        AboutUsLinks.all.forEach { assertTrue(it.url, it.url.startsWith("https://")) }
        assertEquals(AboutUsLinks.all.size, AboutUsLinks.all.map { it.url }.toSet().size)
    }

    @Test
    fun resourcesCoverWikiGitAndBothBackupLists() {
        val urls = AboutUsLinks.resources.map { it.url }
        assertTrue(urls.any { it.endsWith("?s=wiki") })
        assertTrue(urls.any { it.endsWith("?s=git") })
        assertTrue(urls.any { it.startsWith("https://docs.google.com/document/d/") })
        assertTrue(urls.any { it.startsWith("https://gist.github.com/") })
    }
}
