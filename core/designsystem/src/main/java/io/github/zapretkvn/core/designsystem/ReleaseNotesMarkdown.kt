package io.github.zapretkvn.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.net.URI

internal sealed interface ReleaseNotesBlock {
    data class Heading(val level: Int, val text: String) : ReleaseNotesBlock
    data class Paragraph(val text: String) : ReleaseNotesBlock
    data class ListItem(val marker: String, val text: String) : ReleaseNotesBlock
    data class Quote(val text: String) : ReleaseNotesBlock
    data class Code(val text: String) : ReleaseNotesBlock
    data object Divider : ReleaseNotesBlock
}

internal data class ReleaseNotesSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

private data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

private val headingPattern = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
private val unorderedListPattern = Regex("^\\s*[-+*]\\s+(.+)$")
private val orderedListPattern = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
private val quotePattern = Regex("^\\s*>\\s?(.*)$")
private val dividerPattern = Regex("^\\s{0,3}(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$")

internal fun parseReleaseNotesBlocks(markdown: String): List<ReleaseNotesBlock> {
    val blocks = mutableListOf<ReleaseNotesBlock>()
    val paragraph = mutableListOf<String>()
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    var lineIndex = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += ReleaseNotesBlock.Paragraph(paragraph.joinToString(" ") { it.trim() })
            paragraph.clear()
        }
    }

    while (lineIndex < lines.size) {
        val line = lines[lineIndex]
        val trimmed = line.trim()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph()
            val fence = trimmed.take(3)
            val code = mutableListOf<String>()
            lineIndex += 1
            while (lineIndex < lines.size && !lines[lineIndex].trim().startsWith(fence)) {
                code += lines[lineIndex]
                lineIndex += 1
            }
            blocks += ReleaseNotesBlock.Code(code.joinToString("\n"))
            if (lineIndex < lines.size) lineIndex += 1
            continue
        }
        if (trimmed.isEmpty()) {
            flushParagraph()
            lineIndex += 1
            continue
        }

        val heading = headingPattern.matchEntire(line)
        val unordered = unorderedListPattern.matchEntire(line)
        val ordered = orderedListPattern.matchEntire(line)
        val quote = quotePattern.matchEntire(line)
        when {
            heading != null -> {
                flushParagraph()
                blocks += ReleaseNotesBlock.Heading(
                    level = heading.groupValues[1].length,
                    text = heading.groupValues[2],
                )
            }
            dividerPattern.matches(line) -> {
                flushParagraph()
                blocks += ReleaseNotesBlock.Divider
            }
            unordered != null -> {
                flushParagraph()
                blocks += ReleaseNotesBlock.ListItem("•", unordered.groupValues[1])
            }
            ordered != null -> {
                flushParagraph()
                blocks += ReleaseNotesBlock.ListItem("${ordered.groupValues[1]}.", ordered.groupValues[2])
            }
            quote != null -> {
                flushParagraph()
                blocks += ReleaseNotesBlock.Quote(quote.groupValues[1])
            }
            else -> paragraph += line
        }
        lineIndex += 1
    }
    flushParagraph()
    return blocks
}

internal fun parseReleaseNotesInline(markdown: String): List<ReleaseNotesSpan> {
    val spans = mutableListOf<ReleaseNotesSpan>()

    fun append(value: String, style: InlineStyle) {
        if (value.isEmpty()) return
        val span = ReleaseNotesSpan(
            text = value,
            bold = style.bold,
            italic = style.italic,
            strikethrough = style.strikethrough,
            code = style.code,
            url = style.url,
        )
        val previous = spans.lastOrNull()
        if (previous != null && previous.copy(text = value) == span) {
            spans[spans.lastIndex] = previous.copy(text = previous.text + value)
        } else {
            spans += span
        }
    }

    fun findClosing(source: String, delimiter: String, fromIndex: Int): Int {
        var index = source.indexOf(delimiter, fromIndex)
        while (index >= 0) {
            var slashCount = 0
            var slashIndex = index - 1
            while (slashIndex >= 0 && source[slashIndex] == '\\') {
                slashCount += 1
                slashIndex -= 1
            }
            if (slashCount % 2 == 0) return index
            index = source.indexOf(delimiter, index + delimiter.length)
        }
        return -1
    }

    fun safeLink(target: String): String? = runCatching {
        URI(target).takeIf { it.scheme.equals("https", ignoreCase = true) || it.scheme.equals("http", ignoreCase = true) }
            ?.toASCIIString()
    }.getOrNull()

    fun findLinkTargetEnd(source: String, fromIndex: Int): Int {
        var depth = 0
        var escaped = false
        for (index in fromIndex until source.length) {
            val character = source[index]
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == '(') {
                depth += 1
            } else if (character == ')' && depth > 0) {
                depth -= 1
            } else if (character == ')') {
                return index
            }
        }
        return -1
    }

    fun parse(source: String, style: InlineStyle) {
        var index = 0
        while (index < source.length) {
            if (source[index] == '\\' && index + 1 < source.length) {
                append(source[index + 1].toString(), style)
                index += 2
                continue
            }

            if (source[index] == '[') {
                val labelEnd = findClosing(source, "]", index + 1)
                if (labelEnd >= 0 && labelEnd + 1 < source.length && source[labelEnd + 1] == '(') {
                    val targetEnd = findLinkTargetEnd(source, labelEnd + 2)
                    if (targetEnd >= 0) {
                        val target = source.substring(labelEnd + 2, targetEnd).trim()
                        parse(source.substring(index + 1, labelEnd), style.copy(url = safeLink(target)))
                        index = targetEnd + 1
                        continue
                    }
                }
            }

            val delimiter = when {
                source.startsWith("***", index) -> "***"
                source.startsWith("___", index) -> "___"
                source.startsWith("**", index) -> "**"
                source.startsWith("__", index) -> "__"
                source.startsWith("~~", index) -> "~~"
                source[index] == '`' -> "`"
                source[index] == '*' -> "*"
                source[index] == '_' -> "_"
                else -> null
            }
            if (delimiter != null) {
                val closing = findClosing(source, delimiter, index + delimiter.length)
                if (closing >= index + delimiter.length + 1) {
                    val nested = source.substring(index + delimiter.length, closing)
                    val nestedStyle = when (delimiter) {
                        "***", "___" -> style.copy(bold = true, italic = true)
                        "**", "__" -> style.copy(bold = true)
                        "~~" -> style.copy(strikethrough = true)
                        "`" -> style.copy(code = true)
                        else -> style.copy(italic = true)
                    }
                    if (delimiter == "`") append(nested, nestedStyle) else parse(nested, nestedStyle)
                    index = closing + delimiter.length
                    continue
                }
            }

            append(source[index].toString(), style)
            index += 1
        }
    }

    parse(markdown, InlineStyle())
    return spans
}

@Composable
fun ReleaseNotesMarkdown(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseReleaseNotesBlocks(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ReleaseNotesBlock.Heading -> MarkdownText(
                    markdown = block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    modifier = Modifier.semantics { heading() },
                )
                is ReleaseNotesBlock.Paragraph -> MarkdownText(
                    markdown = block.text,
                    style = MaterialTheme.typography.bodySmall,
                )
                is ReleaseNotesBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(block.marker, style = MaterialTheme.typography.bodySmall)
                    MarkdownText(
                        markdown = block.text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
                is ReleaseNotesBlock.Quote -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("▌", color = MaterialTheme.colorScheme.outline)
                    MarkdownText(
                        markdown = block.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        modifier = Modifier.weight(1f),
                    )
                }
                is ReleaseNotesBlock.Code -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(8.dp),
                    )
                }
                ReleaseNotesBlock.Divider -> HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MarkdownText(
    markdown: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    val spans = remember(markdown) { parseReleaseNotesInline(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val text = remember(spans, linkColor, codeBackground) {
        buildAnnotatedString {
            spans.forEach { span ->
                val spanStyle = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = if (span.strikethrough) TextDecoration.LineThrough else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
                )
                val appendSpan: () -> Unit = { withStyle(spanStyle) { append(span.text) } }
                if (span.url != null) {
                    withLink(
                        LinkAnnotation.Url(
                            url = span.url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) { appendSpan() }
                } else {
                    appendSpan()
                }
            }
        }
    }
    Text(text = text, style = style, modifier = modifier)
}
