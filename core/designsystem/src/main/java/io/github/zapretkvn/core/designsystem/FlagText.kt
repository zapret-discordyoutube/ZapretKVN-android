package io.github.zapretkvn.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Флаги стран рисуются собственным шрифтом (только флаги Noto Color Emoji, ~0,9 МБ),
 * а не шрифтом эмодзи прошивки. Так флаг выглядит одинаково везде, а на оболочках,
 * где в системном шрифте нет флагов (они превращаются в буквы «RU») или шрифт
 * подменён, он не ломается. Жирность принудительно обычная и без синтеза: иначе
 * при полужирном заголовке системе приходится «утолщать» картинку и флаг мылится.
 */

internal val FlagFontFamily: FontFamily = FontFamily(Font(R.font.noto_flags, FontWeight.Normal))

private val FlagSpan = SpanStyle(
    fontFamily = FlagFontFamily,
    fontWeight = FontWeight.Normal,
    fontSynthesis = FontSynthesis.None,
)

private fun Int.isRegionalIndicator(): Boolean = this in 0x1F1E6..0x1F1FF

/** Границы пар «региональных индикаторов» (флагов) в тексте: полуинтервалы [start, end). */
internal fun flagRanges(text: String): List<IntRange> {
    var ranges: MutableList<IntRange>? = null
    var index = 0
    while (index < text.length) {
        val first = text.codePointAt(index)
        val firstSize = Character.charCount(first)
        if (first.isRegionalIndicator() && index + firstSize < text.length) {
            val second = text.codePointAt(index + firstSize)
            if (second.isRegionalIndicator()) {
                val end = index + firstSize + Character.charCount(second)
                if (ranges == null) ranges = mutableListOf()
                ranges += index until end
                index = end
                continue
            }
        }
        index += firstSize
    }
    return ranges.orEmpty()
}

/** Текст, где каждый флаг набран флаговым шрифтом; остальное не затрагивается. */
internal fun withFlagFont(text: String): AnnotatedString {
    val ranges = flagRanges(text)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(FlagSpan, it.first, it.last + 1) }
    }
}

/** Текст со строкой, в которой могут быть флаги: стиль и параметры как у [Text]. */
@Composable
fun FlagAwareText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val annotated = remember(text) { withFlagFont(text) }
    Text(annotated, modifier = modifier, style = style, color = color, maxLines = maxLines)
}

/**
 * Флаг в квадрате фиксированного размера: по центру, без лишних полей шрифта и без
 * зависимости от масштаба шрифта системы (иначе в больших шрифтах флаг вылезает из ряда).
 */
@Composable
fun FlagBadge(flag: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val density = LocalDensity.current
    val annotated = remember(flag) { withFlagFont(flag) }
    // Ширина флага в шрифте ≈ 1,25 em: кегль подбираем так, чтобы флаг влез в квадрат.
    val fontSize = with(density) { (size.toPx() * 0.78f).toSp() }
    val lineHeight = with(density) { size.toSp() }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            annotated,
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}

/** Ряд флагов из строки «🇷🇺 🇳🇱 🇫🇮» (в [ServerStats.flagStrip] флаги разделены пробелами). */
fun splitFlagStrip(strip: String): List<String> =
    strip.split(' ').filter(String::isNotBlank)

val FlagSmall = 22.dp
