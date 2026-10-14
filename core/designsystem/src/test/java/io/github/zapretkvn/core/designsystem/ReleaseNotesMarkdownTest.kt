package io.github.zapretkvn.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesMarkdownTest {
    @Test
    fun `parses headings lists quotes code and dividers`() {
        val blocks = parseReleaseNotesBlocks(
            """
            ### Новая версия

            Обычный текст
            на двух строках.

            - Первый пункт
            2. Второй пункт
            > Важное замечание
            ****
            ```text
            val answer = 42
            ```
            """.trimIndent(),
        )

        assertEquals(ReleaseNotesBlock.Heading(3, "Новая версия"), blocks[0])
        assertEquals(ReleaseNotesBlock.Paragraph("Обычный текст на двух строках."), blocks[1])
        assertEquals(ReleaseNotesBlock.ListItem("•", "Первый пункт"), blocks[2])
        assertEquals(ReleaseNotesBlock.ListItem("2.", "Второй пункт"), blocks[3])
        assertEquals(ReleaseNotesBlock.Quote("Важное замечание"), blocks[4])
        assertEquals(ReleaseNotesBlock.Divider, blocks[5])
        assertEquals(ReleaseNotesBlock.Code("val answer = 42"), blocks[6])
    }

    @Test
    fun `removes inline markers and retains their styles`() {
        val spans = parseReleaseNotesInline(
            "**Жирный** и *курсив*, ~~старое~~, `код`, ***оба***.",
        )

        assertEquals("Жирный и курсив, старое, код, оба.", spans.joinToString("") { it.text })
        assertTrue(spans.single { it.text == "Жирный" }.bold)
        assertTrue(spans.single { it.text == "курсив" }.italic)
        assertTrue(spans.single { it.text == "старое" }.strikethrough)
        assertTrue(spans.single { it.text == "код" }.code)
        assertTrue(spans.single { it.text == "оба" }.bold)
        assertTrue(spans.single { it.text == "оба" }.italic)
    }

    @Test
    fun `only makes web links clickable`() {
        val spans = parseReleaseNotesInline(
            "[Сайт](https://example.com/release) и [опасная](javascript:alert(1)) ссылка",
        )

        assertEquals("Сайт и опасная ссылка", spans.joinToString("") { it.text })
        assertEquals("https://example.com/release", spans.single { it.text == "Сайт" }.url)
        assertNull(spans.single { it.text.contains("опасная") }.url)
        assertFalse(spans.joinToString("") { it.text }.contains("javascript"))
    }
}
