package io.github.zapretkvn.android.diagnostics

/** Export-only privacy pass. UI errors may still name the app the user must fix. */
object DiagnosticReportRedactor {
    fun redact(text: String): String = SecretRedactor.redact(text)
        // Адрес известного сервера — «<сервер ep-…>», остальные хосты — маска.
        .let(ServerAddressRedactor::redact)
        .replace(IPV6, SecretRedactor.MASK)
        .replace(IPV4, SecretRedactor.MASK)
        .replace(HOST_OR_PACKAGE, SecretRedactor.MASK)

    private val IPV6 = Regex(
        "(?i)(?<![0-9a-f:])(?:(?:[0-9a-f]{0,4}:){1,7}:[0-9a-f]{0,4}|" +
            "(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{1,4})(?![0-9a-f:])",
    )
    private val IPV4 = Regex("(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)")
    private val HOST_OR_PACKAGE = Regex(
        "(?i)\\b(?:[a-z][a-z0-9_-]*\\.)+[a-z][a-z0-9_-]*\\b",
    )
}
