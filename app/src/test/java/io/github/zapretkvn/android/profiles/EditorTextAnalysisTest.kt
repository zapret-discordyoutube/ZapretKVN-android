package io.github.zapretkvn.android.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTextAnalysisTest {
    @Test
    fun `blank query has no matches`() {
        assertEquals(0, EditorTextAnalysis.countMatches("abc abc", ""))
        assertEquals(0, EditorTextAnalysis.countMatches("abc abc", "   "))
    }

    @Test
    fun `matches ignore case and overlap`() {
        assertEquals(2, EditorTextAnalysis.countMatches("Proxy proxy", "PROXY"))
        assertEquals(3, EditorTextAnalysis.countMatches("aaaa", "aa"))
    }

    @Test
    fun `query longer than text has no matches`() {
        assertEquals(0, EditorTextAnalysis.countMatches("ab", "abc"))
    }

    @Test
    fun `structure lists selectors and server outbounds`() {
        val structure = EditorTextAnalysis.structure(TWO_SERVERS)

        assertEquals(listOf("zapret-proxy"), structure.selectors.map { it.tag })
        assertEquals(listOf("a", "b"), structure.selectors.single().outbounds)
        assertEquals(listOf("a", "b"), structure.serverTags)
    }

    @Test
    fun `broken json gives empty structure instead of failing`() {
        val structure = EditorTextAnalysis.structure("{broken")

        assertTrue(structure.selectors.isEmpty())
        assertTrue(structure.serverTags.isEmpty())
    }

    companion object {
        const val TWO_SERVERS = """{"outbounds":[
            {"type":"vless","tag":"a","server":"a.example","server_port":443},
            {"type":"vless","tag":"b","server":"b.example","server_port":443},
            {"type":"selector","tag":"zapret-proxy","outbounds":["a","b"],"default":"a"},
            {"type":"direct","tag":"direct"}]}"""
    }
}
