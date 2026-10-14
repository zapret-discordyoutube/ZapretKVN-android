package io.github.zapretkvn.android.ui.routing

private val RuleValueSeparator = Regex("[,\\n]")

/**
 * Разбор поля «значения правила»: запятая и перевод строки разделяют значения,
 * пробелы по краям и пустые куски отбрасываются. Порядок сохраняется.
 */
internal fun parseRuleValues(text: String): List<String> =
    text.split(RuleValueSeparator).map(String::trim).filter(String::isNotEmpty)
