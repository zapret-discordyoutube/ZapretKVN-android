package io.github.zapretkvn.android.ui.aboutus

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.VectorPath
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
                "https://t.me/vpndpi",
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

    @Test
    fun everyServiceGetsItsOwnLogo() {
        AboutUsLinks.all.forEach { link ->
            val expected = when {
                link.url.startsWith("https://t.me/") -> AboutUsIcon.Telegram
                link.url.startsWith("https://gist.github.com/") -> AboutUsIcon.GitHub
                link.url.startsWith("https://docs.google.com/") -> AboutUsIcon.GoogleDocs
                link.url.endsWith("?s=git") -> AboutUsIcon.Git
                link.url.endsWith("?s=wiki") -> AboutUsIcon.Book
                else -> error("no logo rule for ${link.url}")
            }
            assertEquals(link.url, expected, link.icon)
        }
    }

    @Test
    fun everyLogoBuildsAsA24VectorWithRealPaths() {
        AboutUsIcon.entries.forEach { icon ->
            val vector = icon.vector
            assertEquals(icon.name, 24f, vector.defaultWidth.value)
            assertEquals(icon.name, 24f, vector.defaultHeight.value)
            assertEquals(icon.name, 24f, vector.viewportWidth)
            val paths = vector.root.filterIsInstance<VectorPath>()
            assertEquals(icon.name, 1, paths.size)
            assertTrue(icon.name, paths.single().pathData.size > 5)
        }
    }

    @Test
    fun brandLogosKeepTheirColorAndMonochromeOnesFollowTheTheme() {
        assertEquals(Color(0xFF26A5E4), AboutUsIcon.Telegram.brandColor)
        assertEquals(Color(0xFF4285F4), AboutUsIcon.GoogleDocs.brandColor)
        assertEquals(Color(0xFFF05032), AboutUsIcon.Git.brandColor)
        // Чёрный GitHub и книга не имеют фирменного цвета: тема красит их сама.
        assertEquals(null, AboutUsIcon.GitHub.brandColor)
        assertEquals(null, AboutUsIcon.Book.brandColor)
    }

    @Test
    fun noEmojiInLinkTexts() {
        val emoji = Regex("[\\p{So}\\p{Cs}\\x{FE0F}\\x{200D}]")
        AboutUsLinks.all.forEach { link ->
            assertFalse(link.title, emoji.containsMatchIn(link.title))
            assertFalse(link.subtitle, emoji.containsMatchIn(link.subtitle))
        }
    }
}
