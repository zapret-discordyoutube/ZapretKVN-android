package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.importer.ImportCandidate
import io.github.zapretkvn.android.importer.ImportedConfigActivityScanner

/** Чистые функции, превращающие кандидата импорта в данные для предпросмотра. */

internal fun ImportCandidate.toJson(): String = when (this) {
    is ImportCandidate.RawJson -> json
    is ImportCandidate.Managed -> buildJson()
    is ImportCandidate.WireGuard -> json
}

internal fun ImportCandidate.serverCount(): Int = when (this) {
    is ImportCandidate.RawJson -> runCatching {
        ConfigAnalyzer.serverOutboundTags(json).size
    }.getOrDefault(0)
    is ImportCandidate.Managed -> servers.size
    is ImportCandidate.WireGuard -> 1
}

internal const val MAX_PREVIEW_SERVER_LABELS = 8

internal fun ImportCandidate.serverLabels(): List<String> = when (this) {
    is ImportCandidate.RawJson -> runCatching {
        ConfigAnalyzer.serverOutboundTags(json)
    }.getOrDefault(emptyList())
    is ImportCandidate.Managed -> servers.map(ManagedServer::displayName)
    is ImportCandidate.WireGuard -> listOfNotNull(
        endpointLabel?.let { "$protocolName · $it" } ?: protocolName,
    )
}.take(MAX_PREVIEW_SERVER_LABELS).map(SecretRedactor::redactInline)

internal fun ImportCandidate.importWarnings(): List<String> = when (this) {
    // Предупреждение показывается рядом с адресами и метками, поэтому проходит
    // ту же редакцию: в тексте могут оказаться значения параметров ссылки.
    is ImportCandidate.Managed -> importWarnings.map(SecretRedactor::redactInline)
    is ImportCandidate.RawJson,
    is ImportCandidate.WireGuard,
    -> emptyList()
}

/** Предупреждения о подозрительной активности и DNS в готовом JSON, одной строкой. */
internal fun importActivityWarnings(json: String): String? = buildList {
    ImportedConfigActivityScanner.warning(ImportedConfigActivityScanner.scan(json))
        ?.let(::add)
    addAll(ConfigAnalyzer.dnsWarnings(json))
}.takeIf(List<String>::isNotEmpty)?.joinToString("\n")
