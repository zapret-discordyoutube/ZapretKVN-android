package io.github.zapretkvn.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityLinksTest {
    private val botLinks get() = CommunityLinks.all.filter { it.url.startsWith("https://t.me/zapretvpns_bot") }

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
}
