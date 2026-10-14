package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.SelectorGroup

/** Разбор текста редактора: тяжёлый (полный разбор JSON), поэтому вызывается не на главном потоке. */
internal data class EditorStructure(
    val selectors: List<SelectorGroup>,
    val serverTags: List<String>,
)

internal object EditorTextAnalysis {
    /** Невалидный JSON даёт пустой результат: редактор остаётся рабочим. */
    fun structure(text: String): EditorStructure = EditorStructure(
        selectors = runCatching { ConfigAnalyzer.selectorGroups(text) }.getOrDefault(emptyList()),
        serverTags = runCatching { ConfigAnalyzer.serverOutboundTags(text) }.getOrDefault(emptyList()),
    )

    /** Число вхождений [query] в [text] без учёта регистра, включая пересекающиеся. */
    fun countMatches(text: String, query: String): Int {
        if (query.isBlank()) return 0
        var count = 0
        var start = 0
        while (start <= text.length - query.length) {
            val found = text.indexOf(query, startIndex = start, ignoreCase = true)
            if (found < 0) break
            count++
            start = found + 1
        }
        return count
    }
}
