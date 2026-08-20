package io.github.zapretkvn.android.importer

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ManagedServer
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.profiles.ProtocolOutboundBuilders
import io.github.zapretkvn.android.profiles.TlsSettings
import io.github.zapretkvn.android.profiles.TransportSettings
import io.github.zapretkvn.wireguardimport.WireGuardConfigParser
import io.github.zapretkvn.wireguardimport.WireGuardImportException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

sealed interface ImportCandidate {
    val suggestedName: String
    val source: ProfileSource

    data class RawJson(
        val json: String,
        override val suggestedName: String,
        override val source: ProfileSource,
    ) : ImportCandidate

    data class Managed(
        val servers: List<ManagedServer>,
        override val suggestedName: String,
        override val source: ProfileSource,
        val importWarnings: List<String> = emptyList(),
    ) : ImportCandidate {
        fun buildJson(): String = if (servers.size == 1) {
            ManagedProfileFactory.single(servers.single())
        } else {
            ManagedProfileFactory.subscription(servers)
        }
    }

    data class WireGuard(
        val json: String,
        val protocolName: String,
        val endpointLabel: String?,
        override val suggestedName: String,
        override val source: ProfileSource,
    ) : ImportCandidate
}

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Больше предупреждений пользователь всё равно не прочитает перед подтверждением. */
private const val MAX_IMPORT_WARNINGS = 8

object ImportParser {
    fun parse(
        input: String,
        source: ProfileSource,
        suggestedName: String = "Импортированный профиль",
    ): ImportCandidate {
        val text = input.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw ImportException("Источник импорта пуст.")
        if (WireGuardConfigParser.looksLikeConfig(text)) {
            val wireGuard = try {
                WireGuardConfigParser.parse(text)
            } catch (error: WireGuardImportException) {
                throw ImportException(error.message ?: "Не удалось преобразовать WireGuard .conf.", error)
            }
            return ImportCandidate.WireGuard(
                json = wireGuard.json,
                protocolName = wireGuard.protocolName,
                endpointLabel = wireGuard.endpointLabel,
                suggestedName = suggestedName,
                source = source,
            )
        }
        if (text.startsWith('{') || text.startsWith('[')) {
            val parsed = try {
                JsonConfig.parse(text)
            } catch (error: Exception) {
                throw ImportException("Файл не содержит корректный JSON.", error)
            }
            parseJsonCandidates(parsed, source, suggestedName)?.let { return it }
            if (parsed !is JsonObject) {
                throw ImportException("JSON-массив должен содержать ссылки конфигураций.")
            }
            return ImportCandidate.RawJson(
                json = JsonConfig.format(text),
                suggestedName = suggestedName,
                source = source,
            )
        }
        val direct = extractLinks(text)
        val extracted = if (direct.links.isNotEmpty()) {
            direct
        } else {
            if (direct.unsupportedSchemes.isNotEmpty()) throw noSupportedLinks(direct.unsupportedSchemes)
            val decoded = decodeSubscription(text)
            extractLinks(decoded).also {
                if (it.links.isEmpty()) throw noSupportedLinks(it.unsupportedSchemes)
            }
        }
        val servers = extracted.links.mapIndexed { index, link -> ShareLinkParser.parse(link, index) }
        return ImportCandidate.Managed(
            servers = servers,
            suggestedName = if (servers.size == 1) servers.single().displayName else suggestedName,
            source = if (servers.size == 1) {
                when (source) {
                    ProfileSource.Qr -> ProfileSource.Qr
                    ProfileSource.Url -> ProfileSource.Url
                    else -> ProfileSource.Link
                }
            } else {
                ProfileSource.Subscription
            },
            importWarnings = collectWarnings(servers, extracted.unsupportedSchemes),
        )
    }

    /**
     * Предупреждения серверов доходят до preview вместе с пропущенными схемами.
     * Одинаковые строки от сотни серверов подписки схлопываются: иначе список
     * вытеснит кнопки подтверждения и будет прокручен мимо.
     */
    private fun collectWarnings(
        servers: List<ManagedServer>,
        unsupportedSchemes: List<String> = emptyList(),
    ): List<String> {
        val schemes = unsupportedSchemes.takeIf(List<String>::isNotEmpty)?.let { list ->
            listOf("Пропущены неподдерживаемые схемы: ${list.joinToString { "$it://" }}.")
        }.orEmpty()
        val perServer = servers.flatMap(ManagedServer::importWarnings)
        val counted = perServer.groupingBy { it }.eachCount()
        return schemes + counted.entries
            .sortedByDescending { it.value }
            .map { (warning, count) ->
                if (count > 1) "$warning (серверов: $count)" else warning
            }
            .take(MAX_IMPORT_WARNINGS)
    }

    private fun parseJsonCandidates(
        root: JsonElement,
        source: ProfileSource,
        suggestedName: String,
    ): ImportCandidate.Managed? {
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> JSON_SUBSCRIPTION_KEYS
                .firstNotNullOfOrNull { key -> root[key] as? JsonArray }
                ?: return null
            else -> return null
        }
        if (items.isEmpty()) throw ImportException("JSON-подписка не содержит конфигураций.")
        val links = items.mapIndexed { index, item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> JSON_LINK_KEYS.firstNotNullOfOrNull { key ->
                    (item[key] as? JsonPrimitive)?.contentOrNull
                }
                else -> null
            }?.trim()?.takeIf(String::isNotEmpty)
                ?: throw ImportException(
                    "JSON-подписка: элемент №${index + 1} не содержит строковое поле link/url/uri.",
                )
        }
        val servers = links.mapIndexed { index, link -> ShareLinkParser.parse(link, index) }
        return ImportCandidate.Managed(
            servers = servers,
            suggestedName = if (servers.size == 1) servers.single().displayName else suggestedName,
            source = if (servers.size == 1) source else ProfileSource.Subscription,
            importWarnings = collectWarnings(servers),
        )
    }

    private fun extractLinks(text: String): ExtractedLinks {
        val configSchemeMatches = URI_SCHEME_PATTERN.findAll(text)
            .filter { it.groupValues[1].lowercase() !in NON_CONFIG_SCHEMES }
            .toList()
        val supportedMatches = configSchemeMatches.filter {
            it.groupValues[1].lowercase() in SUPPORTED_SCHEME_NAMES
        }
        val links = supportedMatches.map { match ->
            val nextSchemeStart = configSchemeMatches
                .firstOrNull { it.range.first > match.range.first }
                ?.range
                ?.first
                ?: text.length
            var end = match.range.first
            while (end < nextSchemeStart && !text[end].isLinkBoundary()) {
                end += 1
            }
            text.substring(match.range.first, end)
                .trimEnd(*TRAILING_LINK_WRAPPERS)
                .takeIf(String::isNotEmpty)
                ?: throw ImportException("Обнаружена пустая ссылка.")
        }
        val unsupportedSchemes = configSchemeMatches
            .map { it.groupValues[1].lowercase() }
            .filterNot(SUPPORTED_SCHEME_NAMES::contains)
            .distinct()
        return ExtractedLinks(links, unsupportedSchemes)
    }

    private fun noSupportedLinks(unsupportedSchemes: List<String>): ImportException = ImportException(
        buildString {
            append("В подписке нет поддерживаемых ссылок.")
            if (unsupportedSchemes.isNotEmpty()) {
                append(" Неподдерживаемые схемы: ")
                append(unsupportedSchemes.joinToString { "$it://" })
                append('.')
            }
        },
    )

    private fun decodeSubscription(text: String): String =
        runCatching { decodeBase64(text.filterNot(Char::isWhitespace)) }.getOrElse {
            throw ImportException(SUPPORTED_MESSAGE, it)
        }

    private fun Char.isLinkBoundary(): Boolean =
        isWhitespace() || isISOControl() || this in LINK_BOUNDARIES

    private const val SUPPORTED_MESSAGE =
        "Поддерживаются JSON, WireGuard/AWG .conf, VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и TUIC."
    private val SUPPORTED_SCHEME_NAMES = setOf(
        "vless",
        "vmess",
        "trojan",
        "ss",
        "hysteria2",
        "hy2",
        "tuic",
    )
    private val NON_CONFIG_SCHEMES = setOf("http", "https", "tg")
    private val URI_SCHEME_PATTERN = Regex("(?i)\\b([a-z][a-z0-9+.-]*)://")
    private val LINK_BOUNDARIES = charArrayOf('"', '\'', '`', '<', '>', '\u200B')
    private val TRAILING_LINK_WRAPPERS = charArrayOf(',', ';', '.', ')', ']', '}')
    private val JSON_SUBSCRIPTION_KEYS = listOf("servers", "configs", "proxies", "nodes")
    private val JSON_LINK_KEYS = listOf("link", "url", "uri")

    private data class ExtractedLinks(
        val links: List<String>,
        val unsupportedSchemes: List<String>,
    )
}

object ShareLinkParser {
    fun parse(link: String, index: Int = 0): ManagedServer = try {
        when {
            link.startsWith("vless://", ignoreCase = true) -> parseVless(link, index)
            link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link, index)
            link.startsWith("trojan://", ignoreCase = true) -> parseTrojan(link, index)
            link.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(link, index)
            link.startsWith("hysteria2://", ignoreCase = true) ||
                link.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(link, index)
            link.startsWith("tuic://", ignoreCase = true) -> parseTuic(link, index)
            else -> throw ImportException("Неподдерживаемый тип ссылки.")
        }
    } catch (error: ImportException) {
        throw error
    } catch (error: Exception) {
        throw ImportException("Не удалось разобрать ссылку №${index + 1}.", error)
    }

    private fun parseVless(link: String, index: Int): ManagedServer {
        val uri = URI(link)
        val host = requireHost(uri)
        val query = query(uri)
        val warnings = classifyParameters(query, VLESS_QUERY_KEYS, VLESS_IGNORABLE_KEYS, "VLESS") +
            rejectHeaderObfuscation(query, "VLESS")
        val name = displayName(uri, "VLESS ${index + 1}")
        val uuid = decode(uri.rawUserInfo).takeIf(String::isNotBlank)
            ?: throw ImportException("В VLESS отсутствует UUID.")
        return ProtocolOutboundBuilders.vless(
            displayName = name,
            server = host,
            serverPort = requirePort(uri, 443),
            uuid = uuid,
            encryption = query["encryption"]?.takeIf(String::isNotBlank) ?: "none",
            flow = normalizeVlessFlow(query["flow"]),
            tls = tls(query, host),
            transport = transport(query),
        ).withWarnings(warnings + insecureWarning(query))
    }

    private fun normalizeVlessFlow(flow: String?): String? = when (
        val normalized = flow?.trim()?.lowercase().orEmpty()
    ) {
        "" -> null
        "xtls-rprx-vision",
        "xtls-rprx-vision-udp443",
        -> "xtls-rprx-vision"
        else -> throw ImportException("VLESS flow '$normalized' пока не поддерживается.")
    }

    private fun parseTrojan(link: String, index: Int): ManagedServer {
        val uri = URI(link)
        val host = requireHost(uri)
        val query = query(uri)
        val warnings = classifyParameters(query, TROJAN_QUERY_KEYS, TROJAN_IGNORABLE_KEYS, "Trojan") +
            rejectHeaderObfuscation(query, "Trojan")
        val password = decode(uri.rawUserInfo).takeIf(String::isNotBlank)
            ?: throw ImportException("В Trojan отсутствует пароль.")
        return ProtocolOutboundBuilders.trojan(
            displayName = displayName(uri, "Trojan ${index + 1}"),
            server = host,
            serverPort = requirePort(uri, 443),
            password = password,
            tls = tls(query, host, defaultEnabled = true),
            transport = transport(query),
        ).withWarnings(warnings + insecureWarning(query))
    }

    private fun parseVmess(link: String, index: Int): ManagedServer {
        val encoded = link.substringAfter("vmess://").substringBefore('#').trim()
        val data = JsonConfig.parse(decodeBase64(encoded)) as? JsonObject
            ?: throw ImportException("VMess payload должен быть JSON-объектом.")
        val host = data.text("add") ?: throw ImportException("В VMess отсутствует сервер.")
        val port = data.number("port") ?: 443
        val uuid = data.text("id") ?: throw ImportException("В VMess отсутствует UUID.")
        val warnings = classifyFields(data, VMESS_FIELDS, VMESS_IGNORABLE_FIELDS, "VMess")
        val network = data.text("net").orEmpty()
        val transport = when (network) {
            "", "tcp" -> null
            "ws", "http", "httpupgrade" -> TransportSettings(
                type = network,
                path = data.text("path"),
                host = data.text("host"),
            )
            "grpc" -> TransportSettings(type = network, serviceName = data.text("path"))
            "xhttp" -> TransportSettings(
                type = network,
                path = data.text("path"),
                host = data.text("host"),
                mode = data.text("type")?.takeIf { it in XHTTP_MODES },
                xhttpOptions = XtlsXhttpExtraConverter.convert(
                    data.text("extra") ?: (data["extra"] as? JsonObject)?.toString(),
                ),
            )
            else -> throw ImportException("VMess transport '$network' пока не поддерживается.")
        }
        val tlsEnabled = data.text("tls")?.lowercase() in setOf("tls", "reality")
        return ProtocolOutboundBuilders.vmess(
            displayName = data.text("ps") ?: "VMess ${index + 1}",
            server = host,
            serverPort = port,
            uuid = uuid,
            security = data.text("scy") ?: "auto",
            alterId = data.number("aid") ?: 0,
            tls = TlsSettings(
                enabled = tlsEnabled,
                serverName = data.text("sni") ?: host,
                insecure = data.boolean("allowInsecure"),
                utlsFingerprint = data.text("fp"),
                realityPublicKey = data.text("pbk"),
                realityShortId = data.text("sid"),
                alpn = data.text("alpn").orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank),
            ),
            transport = transport,
        ).withWarnings(warnings)
    }

    private fun parseShadowsocks(link: String, index: Int): ManagedServer {
        val body = link.substringAfter("ss://")
        val fragment = body.substringAfter('#', "")
        val beforeFragment = body.substringBefore('#')
        val rawQuery = beforeFragment.substringAfter('?', "")
        val shadowQuery = rawQuery.split('&').filter(String::isNotBlank).associate { part ->
            canonicalKey(decode(part.substringBefore('='))) to decode(part.substringAfter('=', ""))
        }
        if ("plugin" in shadowQuery) {
            throw ImportException("Shadowsocks plugin в URI пока не поддерживается.")
        }
        val warnings = classifyParameters(
            shadowQuery,
            SHADOWSOCKS_QUERY_KEYS,
            SHADOWSOCKS_IGNORABLE_KEYS,
            "Shadowsocks",
        ) + rejectForeignTransport(shadowQuery, "Shadowsocks")
        val withoutFragment = beforeFragment.substringBefore('?')
        val expanded = if ('@' in withoutFragment) withoutFragment else decodeBase64(withoutFragment)
        val credentialPart = expanded.substringBeforeLast('@')
        val serverPart = expanded.substringAfterLast('@', "")
        if (serverPart.isBlank()) throw ImportException("В Shadowsocks отсутствует сервер.")
        val credentials = decodeCredentials(credentialPart)
        val method = credentials.substringBefore(':')
        val password = credentials.substringAfter(':', "")
        if (method.isBlank() || password.isBlank()) {
            throw ImportException("В Shadowsocks отсутствуют method или password.")
        }
        val serverUri = URI("ss://placeholder@$serverPart")
        return ProtocolOutboundBuilders.shadowsocks(
            displayName = decode(fragment).ifBlank { "Shadowsocks ${index + 1}" },
            server = requireHost(serverUri),
            serverPort = requirePort(serverUri, 8388),
            method = method,
            password = password,
        )
    }

    private fun parseHysteria2(link: String, index: Int): ManagedServer {
        val normalized = if (link.startsWith("hy2://", true)) {
            "hysteria2://" + link.substringAfter("://")
        } else {
            link
        }
        val uri = URI(normalized)
        val host = requireHost(uri)
        val query = query(uri)
        rejectCertificatePinning(query, "Hysteria2")
        rejectImpossibleTlsOptOut(query, "Hysteria2")
        val warnings = classifyParameters(
            query,
            HYSTERIA2_QUERY_KEYS,
            HYSTERIA2_IGNORABLE_KEYS,
            "Hysteria2",
        )
        val password = decode(uri.rawUserInfo).takeIf(String::isNotBlank)
            ?: query["auth"]?.takeIf(String::isNotBlank)
            ?: throw ImportException("В Hysteria2 отсутствует пароль.")
        val obfsType = query["obfs"]?.lowercase()
        if (obfsType != null && obfsType !in setOf("none", "plain", "salamander", "gecko")) {
            throw ImportException("Hysteria2 obfs '$obfsType' пока не поддерживается.")
        }
        val obfsPassword = query["obfspassword"]
        if (obfsType in setOf("salamander", "gecko") && obfsPassword.isNullOrBlank()) {
            // Без пароля salamander не согласуется: сервер молча отвергал бы каждый пакет.
            throw ImportException("Hysteria2 obfs '$obfsType' требует obfs-password.")
        }
        return ProtocolOutboundBuilders.hysteria2(
            displayName = displayName(uri, "Hysteria2 ${index + 1}"),
            server = host,
            serverPort = requirePort(uri, 443),
            password = password,
            tls = TlsSettings(
                enabled = true,
                serverName = query["sni"] ?: query["peer"] ?: host,
                insecure = query.boolean("insecure"),
                alpn = query.csv("alpn"),
            ),
            obfsPassword = obfsPassword.takeIf { obfsType in setOf("salamander", "gecko") },
            obfsType = obfsType.takeIf { it in setOf("salamander", "gecko") },
            upMbps = query.mbps("up"),
            downMbps = query.mbps("down"),
        ).withWarnings(warnings + insecureWarning(query))
    }

    private fun parseTuic(link: String, index: Int): ManagedServer {
        val uri = URI(link)
        val host = requireHost(uri)
        val query = query(uri)
        rejectCertificatePinning(query, "TUIC")
        rejectImpossibleTlsOptOut(query, "TUIC")
        val warnings = classifyParameters(query, TUIC_QUERY_KEYS, TUIC_IGNORABLE_KEYS, "TUIC")
        val credentials = decode(uri.rawUserInfo)
        val uuid = credentials.substringBefore(':').takeIf(String::isNotBlank)
            ?: throw ImportException("В TUIC отсутствует UUID.")
        val password = credentials.substringAfter(':', "").takeIf(String::isNotBlank)
            ?: throw ImportException("В TUIC отсутствует пароль.")
        return ProtocolOutboundBuilders.tuic(
            displayName = displayName(uri, "TUIC ${index + 1}"),
            server = host,
            serverPort = requirePort(uri, 443),
            uuid = uuid,
            password = password,
            congestionControl = query["congestioncontrol"],
            udpRelayMode = query["udprelaymode"],
            zeroRttHandshake = query.boolean("zerortthandshake"),
            heartbeat = query["heartbeat"],
            tls = TlsSettings(
                enabled = true,
                serverName = query["sni"] ?: host,
                insecure = query.boolean("allow_insecure") ||
                    query.boolean("allowInsecure") ||
                    query.boolean("insecure"),
                alpn = query.csv("alpn"),
            ),
        )
    }

    private fun decodeCredentials(value: String): String {
        val decoded = decode(value)
        return if (':' in decoded) decoded else decodeBase64(value)
    }

    private fun tls(
        query: Map<String, String>,
        host: String,
        defaultEnabled: Boolean = false,
    ): TlsSettings {
        val security = query["security"]?.lowercase()
        val enabled = defaultEnabled || security == "tls" || security == "reality"
        return TlsSettings(
            enabled = enabled,
            serverName = query["sni"] ?: if (enabled) host else null,
            insecure = query.boolean("insecure"),
            utlsFingerprint = query["fp"],
            realityPublicKey = query["pbk"],
            realityShortId = query["sid"],
            alpn = query.csv("alpn"),
        )
    }

    private fun transport(
        query: Map<String, String>,
    ): TransportSettings? = when (
        val type = query["type"]?.lowercase().orEmpty()
    ) {
        "", "tcp", "none" -> null
        "ws", "http", "httpupgrade" -> TransportSettings(
            type = type,
            path = query["path"],
            host = query["host"],
        )
        "grpc" -> TransportSettings(
            type = type,
            serviceName = query["servicename"],
        )
        "xhttp" -> TransportSettings(
            type = type,
            path = query["path"],
            host = query["host"],
            mode = query["mode"],
            xhttpOptions = XtlsXhttpExtraConverter.convert(query["extra"]),
        )
        else -> throw ImportException("Transport '$type' пока не поддерживается.")
    }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery
        .orEmpty()
        .split('&')
        .filter(String::isNotBlank)
        .associate { part ->
            val key = canonicalKey(decode(part.substringBefore('=')))
            val value = decode(part.substringAfter('=', ""))
            key to value
        }

    /**
     * Панели пишут одно и то же имя по-разному: `allowInsecure`, `allow_insecure`,
     * `skip-cert-verify`. Классифицировать нужно смысл, а не написание, поэтому имя
     * сводится к нижнему регистру без разделителей и затем к каноническому синониму.
     */
    private fun canonicalKey(raw: String): String {
        val normalized = raw.trim().lowercase().filterNot { it == '-' || it == '_' }
        return KEY_ALIASES[normalized] ?: normalized
    }

    /**
     * Вердикт по параметру определяет его класс, а не знакомство парсера с именем.
     *
     * Исполнимое переносится в конфигурацию; неисполнимое, но безопасное — теряется
     * с поимённым предупреждением в preview; всё остальное по-прежнему отклоняется,
     * потому что неизвестное имя означает неизвестный класс.
     */
    private fun classifyParameters(
        query: Map<String, String>,
        executable: Set<String>,
        ignorable: Set<String>,
        protocol: String,
    ): List<String> {
        val unknown = query.keys.filterNot { it in executable || it in ignorable }.sorted()
        if (unknown.isNotEmpty()) {
            throw ImportException(
                "$protocol содержит неподдерживаемые параметры: ${unknown.joinToString()}.",
            )
        }
        val dropped = query.keys.filter { it in ignorable && it !in executable }.sorted()
        return if (dropped.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "$protocol: параметры не переносятся в sing-box и не применены — " +
                    "${dropped.joinToString()}.",
            )
        }
    }

    /**
     * Пиннинг сертификата усиливает проверку, и перевести его нельзя: hysteria2 пинит
     * весь сертификат, а sing-box — публичный ключ. Молчаливая потеря вернула бы
     * обычную проверку по CA, поэтому такая ссылка отклоняется отдельным сообщением.
     */
    private fun rejectCertificatePinning(query: Map<String, String>, protocol: String) {
        if (query["pinsha256"].isNullOrBlank()) return
        throw ImportException(
            "$protocol закрепляет сертификат (pinSHA256): sing-box закрепляет только " +
                "публичный ключ, поэтому ссылку нельзя перенести без потери проверки.",
        )
    }

    /**
     * VMess-payload — чужой формат, который переводится в sing-box, а не сохраняется
     * как есть, поэтому к его полям применяется то же правило классов, что и к query.
     */
    private fun classifyFields(
        data: JsonObject,
        executable: Set<String>,
        ignorable: Set<String>,
        protocol: String,
    ): List<String> {
        val keys = data.keys.map { it.lowercase() }
        val unknown = keys.filterNot { it in executable || it in ignorable }.sorted()
        if (unknown.isNotEmpty()) {
            throw ImportException(
                "$protocol содержит неподдерживаемые поля: ${unknown.joinToString()}.",
            )
        }
        val dropped = keys.filter { it in ignorable && it !in executable }.sorted()
        return if (dropped.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "$protocol: поля не переносятся в sing-box и не применены — " +
                    "${dropped.joinToString()}.",
            )
        }
    }

    private fun displayName(uri: URI, fallback: String): String =
        decode(uri.rawFragment.orEmpty()).ifBlank { fallback }

    /** Скорость в ссылке пишут и числом, и строкой вида "100 Mbps". */
    private fun Map<String, String>.mbps(key: String): Int? = this[key]
        ?.trim()
        ?.takeWhile(Char::isDigit)
        ?.toIntOrNull()

    /** Ссылка ослабляет проверку сертификата — это нельзя применить беззвучно. */
    private fun insecureWarning(query: Map<String, String>): List<String> =
        if (query.boolean("insecure")) {
            listOf("Ссылка отключает проверку сертификата сервера.")
        } else {
            emptyList()
        }

    /**
     * `headerType=none` — пустая операция, а любой другой тип меняет вид трафика
     * на проводе, и подключение с ним не встанет: sing-box такой обфускации не имеет.
     */
    private fun rejectHeaderObfuscation(query: Map<String, String>, protocol: String): List<String> {
        val header = query["headertype"]?.lowercase()?.takeIf(String::isNotBlank) ?: return emptyList()
        if (header == "none") return emptyList()
        throw ImportException("$protocol с обфускацией заголовком '$header' не поддерживается.")
    }

    /**
     * У QUIC-протоколов TLS обязателен, поэтому `security=tls` — тавтология и шум.
     * А `security=none` описывает соединение, которого не бывает: такую ссылку
     * молча импортировать нельзя — она обещает не то, что произойдёт.
     */
    private fun rejectImpossibleTlsOptOut(query: Map<String, String>, protocol: String) {
        val security = query["security"]?.lowercase()?.takeIf(String::isNotBlank) ?: return
        if (security == "tls") return
        throw ImportException(
            "$protocol работает только поверх TLS, а ссылка объявляет security='$security'.",
        )
    }

    /** У Shadowsocks `type` задаёт транспорт, а не косметику: чужой транспорт не молчим. */
    private fun rejectForeignTransport(query: Map<String, String>, protocol: String): List<String> {
        val type = query["type"]?.lowercase()?.takeIf(String::isNotBlank) ?: return emptyList()
        if (type == "tcp") return emptyList()
        throw ImportException("$protocol transport '$type' пока не поддерживается.")
    }

    private fun ManagedServer.withWarnings(warnings: List<String>): ManagedServer =
        if (warnings.isEmpty()) this else copy(importWarnings = (importWarnings + warnings).distinct())

    private fun Map<String, String>.boolean(key: String): Boolean =
        this[key]?.lowercase() in setOf("1", "true", "yes", "on")

    private fun Map<String, String>.csv(key: String): List<String> =
        this[key].orEmpty().split(',').map(String::trim).filter(String::isNotBlank)

    private fun requireHost(uri: URI): String = uri.host
        ?.takeIf(String::isNotBlank)
        ?.removeSurrounding("[", "]")
        ?: throw ImportException("В ссылке отсутствует сервер.")

    private fun requirePort(uri: URI, defaultPort: Int): Int = when {
        uri.port == -1 -> defaultPort
        uri.port in 1..65535 -> uri.port
        else -> throw ImportException("В ссылке отсутствует корректный порт.")
    }

    /** Генераторы пишут ключи VMess и `allowInsecure`, и `allowinsecure`. */
    private fun JsonObject.text(key: String): String? {
        val entry = this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        return (entry as? JsonPrimitive)?.contentOrNull
    }

    /** Генераторы пишут флаги и строкой, и числом, и булевым литералом. */
    private fun JsonObject.boolean(key: String): Boolean =
        text(key)?.lowercase() in setOf("1", "true", "yes", "on")

    private fun JsonObject.number(key: String): Int? {
        val entry = this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        val primitive = entry as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }

    private val XHTTP_MODES = setOf("auto", "packet-up", "stream-up", "stream-one")

    /** Синонимы приводятся к одному имени, чтобы класс не зависел от написания. */
    private val KEY_ALIASES = mapOf(
        "servername" to "sni",
        "peer" to "sni",
        "allowinsecure" to "insecure",
        "skipcertverify" to "insecure",
        "publickey" to "pbk",
        "shortid" to "sid",
        "fingerprint" to "fp",
        "spiderx" to "spx",
        "upmbps" to "up",
        "upbps" to "up",
        "downmbps" to "down",
        "downbps" to "down",
        "obfspassword" to "obfspassword",
        "congestioncontroller" to "congestioncontrol",
        "heartbeatinterval" to "heartbeat",
        "reducertt" to "zerortthandshake",
        "tcpfastopen" to "tfo",
        "fastopen" to "tfo",
        "mport" to "ports",
        "pinsha256" to "pinsha256",
    )

    private val TRANSPORT_QUERY_KEYS = setOf(
        "type", "path", "host", "servicename", "mode", "extra",
    )
    private val TLS_QUERY_KEYS = setOf(
        "security", "sni", "insecure", "fp", "pbk", "sid", "alpn",
    )

    /**
     * Параметры чужих реализаций, которые sing-box выполнить не может, но их потеря
     * не меняет ни шифрование, ни то, кому клиент доверяет. Список расширяется только
     * по реальному образцу ссылки и вместе с тестом.
     */
    private val TCP_IGNORABLE_KEYS = setOf(
        // spiderX — путь для сканеров REALITY на стороне Xray, у sing-box его нет.
        "spx",
        "packetencoding",
        "authority",
        "mux",
        "remarks",
        "remark",
        "tfo",
    )
    private val VLESS_QUERY_KEYS = TRANSPORT_QUERY_KEYS + TLS_QUERY_KEYS +
        setOf("flow", "encryption", "headertype")
    private val VLESS_IGNORABLE_KEYS = TCP_IGNORABLE_KEYS
    private val TROJAN_QUERY_KEYS = TRANSPORT_QUERY_KEYS + TLS_QUERY_KEYS + setOf("headertype")
    private val TROJAN_IGNORABLE_KEYS = TCP_IGNORABLE_KEYS + setOf("encryption", "plugin")
    private val SHADOWSOCKS_QUERY_KEYS = setOf("type")
    private val SHADOWSOCKS_IGNORABLE_KEYS = setOf("outline", "prefix", "remarks", "remark", "tfo")
    private val HYSTERIA2_QUERY_KEYS = setOf(
        "auth", "sni", "insecure", "alpn", "obfs", "obfspassword", "up", "down",
    )

    /**
     * `security=tls` для Hysteria2 — тавтология: TLS там обязателен. `fp` неисполним:
     * uTLS подменяет ClientHello в TCP-потоке, а рукопожатие QUIC идёт внутри ядра,
     * и sing-box по умолчанию уже имитирует QUIC-профиль Chrome.
     */
    private val HYSTERIA2_IGNORABLE_KEYS = setOf(
        "security", "fp", "ports", "hopinterval", "tfo", "vcn", "pcs", "fm", "udp", "dns",
    )
    private val TUIC_QUERY_KEYS = setOf(
        "sni", "insecure", "alpn", "congestioncontrol", "udprelaymode",
        "zerortthandshake", "heartbeat",
    )
    private val TUIC_IGNORABLE_KEYS = setOf(
        "security", "fp", "tfo", "disablesni", "udpoverstream", "requesttimeout",
        "maxudprelaypacketsize", "token", "version", "ip",
    )
    /** Метки и настройки чужих клиентов: смысла для sing-box не имеют. */
    private val VMESS_IGNORABLE_FIELDS = setOf(
        "spx", "mode", "security", "headertype", "servicename", "remark", "remarks",
        "class", "mux", "protocol", "obfs", "obfsparam", "tag", "skipcertverify", "sockopt",
        "fingerprint", "packetencoding", "tfo",
    )
    private val VMESS_FIELDS = setOf(
        "v", "ps", "add", "port", "id", "aid", "scy", "net", "path", "host", "type",
        "extra", "tls", "sni", "allowinsecure", "fp", "alpn", "pbk", "sid",
    )

}

class AndroidImportReader(private val context: Context) {
    fun readDocument(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Не удалось открыть выбранный файл.")
        return try {
            input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMPORT_BYTES) {
                        throw ImportException("Файл больше 4 МБ.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
        } catch (error: IOException) {
            throw ImportException("Ошибка чтения выбранного файла.", error)
        }
    }

    fun readClipboardAfterUserAction(): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: throw ImportException("Буфер обмена недоступен.")
        val clip = clipboard.primaryClip
            ?: throw ImportException("Буфер обмена пуст.")
        if (clip.itemCount == 0) throw ImportException("Буфер обмена пуст.")
        val text = buildList {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).coerceToText(context)?.toString()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }.joinToString("\n")
        if (text.isBlank()) throw ImportException("В буфере нет текста.")
        if (text.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) {
            throw ImportException("Текст в буфере больше 4 МБ.")
        }
        return text
    }

    fun documentDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(MAX_DISPLAY_NAME_LENGTH)
        }
    }.getOrNull() ?: uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_DISPLAY_NAME_LENGTH)

    private companion object {
        const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
        const val MAX_DISPLAY_NAME_LENGTH = 160
    }
}

internal fun decodeBase64(input: String): String {
    val compact = input.filterNot(Char::isWhitespace)
    val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
    val decoder = if ('-' in padded || '_' in padded) Base64.getUrlDecoder() else Base64.getDecoder()
    return String(decoder.decode(padded), Charsets.UTF_8)
}

private fun decode(value: String): String =
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
