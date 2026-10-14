package io.github.zapretkvn.android.ui.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagTextTest {
    @Test
    fun findsFlagPairsOnly() {
        val text = "VLESS 🇷🇺 Tirana 🇳🇱"
        val ranges = flagRanges(text)
        assertEquals(2, ranges.size)
        assertEquals("🇷🇺", text.substring(ranges[0].first, ranges[0].last + 1))
        assertEquals("🇳🇱", text.substring(ranges[1].first, ranges[1].last + 1))
    }

    @Test
    fun loneRegionalIndicatorAndPlainTextAreNotFlags() {
        assertTrue(flagRanges("us-vdsryzen-8").isEmpty())
        assertTrue(flagRanges("🇷 alone").isEmpty())
        assertTrue(flagRanges("").isEmpty())
    }

    @Test
    fun flagStripSplitsBySpaces() {
        assertEquals(listOf("🇷🇺", "🇳🇱"), splitFlagStrip("🇷🇺 🇳🇱"))
    }
}
