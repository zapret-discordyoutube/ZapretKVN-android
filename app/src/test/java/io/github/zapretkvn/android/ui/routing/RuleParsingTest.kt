package io.github.zapretkvn.android.ui.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleParsingTest {
    @Test
    fun splitsOnNewlinesAndCommas() {
        assertEquals(
            listOf("a.example", "b.example", "10.0.0.0/8"),
            parseRuleValues("a.example\nb.example, 10.0.0.0/8"),
        )
    }

    @Test
    fun trimsAndDropsEmptyPieces() {
        assertEquals(listOf("a.example"), parseRuleValues("  a.example  ,,\n \r\n"))
    }

    @Test
    fun handlesWindowsLineEndings() {
        assertEquals(listOf("a", "b"), parseRuleValues("a\r\nb\r\n"))
    }

    @Test
    fun blankInputGivesNoValues() {
        assertEquals(emptyList<String>(), parseRuleValues(""))
        assertEquals(emptyList<String>(), parseRuleValues(" \n , "))
    }

    @Test
    fun keepsOrderAndDuplicates() {
        assertEquals(listOf("b", "a", "b"), parseRuleValues("b,a,b"))
    }
}
