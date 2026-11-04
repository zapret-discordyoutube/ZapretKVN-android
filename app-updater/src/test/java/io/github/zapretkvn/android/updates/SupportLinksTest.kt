package io.github.zapretkvn.android.updates

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportLinksTest {
    @After
    fun reset() = ForgejoHosts.forgetForTests()

    private class FakeHttp(private val answers: Map<String, String>) : UpdateHttpClient {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun open(url: String, start: Long, endInclusive: Long?): UpdateResponse = error("not used")
        override fun readText(url: String, maxBytes: Int): String {
            asked += url
            return answers[url] ?: throw UpdateException("недоступно: $url")
        }
    }

    private fun mirrors(host: String) = "https://$host/_sites/mirrors.json" to
        """{"schema":1,"families":{"git":{"preferred":"$host","names":[{"name":"$host"}]}}}"""

    @Test
    fun `the first name that returns a mirror list is the support page`() {
        val http = FakeHttp(mapOf(mirrors("git3.zapret.moe")))
        assertEquals(
            "https://git3.zapret.moe/zapretkvn/ZapretKVN-android/issues/new",
            SupportLinks.resolve(http, deadlineMillis = 2_000),
        )
    }

    @Test
    fun `a captive portal answer that is not a mirror list is not trusted`() {
        val http = FakeHttp(
            ForgejoHosts.all().associate { "https://$it/_sites/mirrors.json" to "<html>login</html>" },
        )
        assertEquals(SupportLinks.GITHUB_URL, SupportLinks.resolve(http, deadlineMillis = 1_500))
    }

    @Test
    fun `when no known name answers a fresh name from an entry page is tried`() {
        val entry = ForgejoHosts.ENTRY_LISTS.first()
        val http = FakeHttp(
            mapOf(
                entry to """{"schema":1,"families":{"git":{"hosts":["cwdnvtsqdc.zapret.moe"]}}}""",
                mirrors("cwdnvtsqdc.zapret.moe"),
            ),
        )
        assertEquals(
            "https://cwdnvtsqdc.zapret.moe/zapretkvn/ZapretKVN-android/issues/new",
            SupportLinks.resolve(http, deadlineMillis = 1_500),
        )
    }

    @Test
    fun `when nothing opens the page is on github`() {
        val http = FakeHttp(emptyMap())
        val url = SupportLinks.resolve(http, deadlineMillis = 1_500)
        assertEquals("https://github.com/zapret-discordyoutube/ZapretKVN-android/issues/new", url)
        assertTrue(http.asked.any { it.contains("/_sites/mirrors.json") })
    }

    @Test
    fun `mirror list detection is strict`() {
        assertTrue(SupportLinks.looksLikeMirrorList("""{"families":{}}"""))
        assertFalse(SupportLinks.looksLikeMirrorList("""{"other":1}"""))
        assertFalse(SupportLinks.looksLikeMirrorList("not json"))
        assertNull(SupportLinks.race(FakeHttp(emptyMap()), emptyList(), 100))
    }
}
