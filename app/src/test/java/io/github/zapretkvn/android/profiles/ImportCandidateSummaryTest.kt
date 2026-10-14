package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCandidateSummaryTest {
    private val raw = """{"outbounds":[
        {"type":"vless","tag":"a","server":"a.example","server_port":443},
        {"type":"selector","tag":"sel","outbounds":["a"]},
        {"type":"direct","tag":"direct"}]}"""

    @Test
    fun `raw json counts only server outbounds`() {
        val candidate = ImportParser.parse(raw, ProfileSource.Clipboard)

        assertTrue(candidate is ImportCandidate.RawJson)
        assertEquals(1, candidate.serverCount())
        assertEquals(listOf("a"), candidate.serverLabels())
        assertTrue(candidate.toJson().contains("a.example"))
        assertTrue(candidate.importWarnings().isEmpty())
    }

    @Test
    fun `managed links give one label per server and a managed json`() {
        val links = managedServers(vlessLink("one.example", "One", '1'), vlessLink("two.example", "Two", '2'))
        val candidate = ImportParser.parse(
            listOf(vlessLink("one.example", "One", '1'), vlessLink("two.example", "Two", '2'))
                .joinToString("\n"),
            ProfileSource.Clipboard,
        )

        assertEquals(links.size, candidate.serverCount())
        assertEquals(listOf("One", "Two"), candidate.serverLabels())
        assertTrue(candidate.toJson().contains("zapret-proxy"))
    }

    @Test
    fun `labels are limited to eight entries`() {
        val links = (1..12).map { vlessLink("s$it.example", "Server $it", ('0' + it % 10)) }
        val candidate = ImportParser.parse(links.joinToString("\n"), ProfileSource.Clipboard)

        assertEquals(12, candidate.serverCount())
        assertEquals(MAX_PREVIEW_SERVER_LABELS, candidate.serverLabels().size)
    }

    @Test
    fun `plain config without dns issues has no activity warning`() {
        assertNull(importActivityWarnings("""{"outbounds":[{"type":"direct","tag":"direct"}]}"""))
    }

    @Test
    fun `broken raw json degrades to zero servers`() {
        val candidate = ImportCandidate.RawJson("{broken", "x", ProfileSource.Clipboard)

        assertEquals(0, candidate.serverCount())
        assertTrue(candidate.serverLabels().isEmpty())
    }
}
