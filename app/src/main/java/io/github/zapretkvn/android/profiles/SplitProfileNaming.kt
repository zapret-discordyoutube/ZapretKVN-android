package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.diagnostics.SecretRedactor

/**
 * Имена профилей, когда подписка раскладывается по одному профилю на сервер:
 * берётся имя сервера из ссылки, повторы нумеруются, пустое имя заменяется базовым.
 */
object SplitProfileNaming {
    fun names(displayNames: List<String>, baseName: String): List<String> {
        val fallback = SecretRedactor.redactInline(baseName).trim().ifBlank { "Профиль" }
        val used = mutableMapOf<String, Int>()
        return displayNames.mapIndexed { index, displayName ->
            val candidate = SecretRedactor.redactInline(displayName)
                .trim()
                .ifBlank { "$fallback ${index + 1}" }
            val seen = used.getOrDefault(candidate.lowercase(), 0) + 1
            used[candidate.lowercase()] = seen
            if (seen == 1) candidate else "$candidate ($seen)"
        }
    }
}
