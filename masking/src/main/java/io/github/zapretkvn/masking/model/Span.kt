package io.github.zapretkvn.masking.model

/**
 * A number or an inclusive range written the way Xray writes it: `"16"` or `"8-16"`.
 *
 * One parser for every range field on both platforms, so a value typed on Windows reads the
 * same on Android. Only non-negative ranges: every masking field that takes a range in the
 * cores we ship (Xray `Int32Range` for xmux/finalmask, amneziawg-go junk sizes) is a count,
 * a size or a duration. Xray's own `ParseRangeString` also accepts negative ends
 * (infra/conf/common.go:355); masking never needs them, so they are refused here.
 */
data class Span(val from: Int, val to: Int) {
    init {
        require(from >= 0) { "range start must not be negative" }
        require(from <= to) { "range start must not exceed its end" }
    }

    val isSingle: Boolean get() = from == to

    /** `"16"` for a single value, `"8-16"` for a range: the form Xray and the UI both read. */
    override fun toString(): String = if (isSingle) "$from" else "$from-$to"

    operator fun contains(value: Int): Boolean = value in from..to

    companion object {
        fun of(value: Int): Span = Span(value, value)

        /** Parses `"16"`, `"8-16"`, `" 8 - 16 "`; null for anything else (never guesses). */
        fun parse(text: String): Span? {
            val parts = text.trim().split('-').map(String::trim)
            if (parts.isEmpty() || parts.size > 2 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            val from = numbers.first()
            val to = numbers.last()
            return if (from <= to) Span(from, to) else null
        }
    }
}
