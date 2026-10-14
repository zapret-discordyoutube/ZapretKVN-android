package io.github.zapretkvn.core.uimodel

/** Presentation-only masking for values that must not appear in screenshots by default. */
object ScreenshotPrivacy {
    const val MASK = "********"

    fun serverEndpoint(value: String?, hidden: Boolean): String? = when {
        value.isNullOrBlank() -> value
        hidden -> MASK
        else -> value
    }

    fun serverLabel(value: String, hidden: Boolean): String =
        if (hidden && value.containsNetworkAddress()) MASK else value

    fun subscriptionSource(value: String, hidden: Boolean): String =
        if (hidden) MASK else value

    private fun String.containsNetworkAddress(): Boolean =
        NETWORK_ADDRESS.containsMatchIn(this) ||
            split(' ', '·', '/', '[', ']').any { token ->
                token.count { it == ':' } >= 2 &&
                    token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' }
            }

    private val NETWORK_ADDRESS = Regex(
        "(?i)(?:[a-z][a-z0-9+.-]*://\\S+|" +
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b|" +
            "\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d{1,5})?\\b)",
    )
}
