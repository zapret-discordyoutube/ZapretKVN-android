package io.github.zapretkvn.android.importer

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
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
import java.math.BigDecimal
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
private const val HYSTERIA2_MAX_PACKET_SIZE = 2048
private val HYSTERIA2_SUPPORTED_OBFS_TYPES = setOf("none", "plain", "salamander", "gecko")
private val HYSTERIA2_PASSWORD_OBFS_TYPES = setOf("salamander", "gecko")
private val HYSTERIA2_DURATION = Regex(
    "[-+]?(?:(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:ns|us|µs|μs|ms|s|m|h))+",
)
private val HYSTERIA2_DURATION_PART = Regex(
    "(\\d+(?:\\.\\d*)?|\\.\\d+)(ns|us|µs|μs|ms|s|m|h)",
)

object ImportParser {
    fun parse(
        input: String,
        source: ProfileSource,
        suggestedName: String = "Импортированный профиль",
    ): ImportCandidate {
        val text = input.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw ImportException("Источник импорта пуст.")
        if (URI_SCHEME_PATTERN.find(text)?.range?.first == 0 &&
            source in setOf(ProfileSource.Link, ProfileSource.Qr) &&
            text.any { it.isWhitespace() || it.isISOControl() }
        ) {
            throw ImportException("Ссылка содержит управляющий символ или пробел.")
        }
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
        val warnings = classifyParameters(query, VLESS_QUERY_KEYS, "VLESS") +
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
        val warnings = classifyParameters(query, TROJAN_QUERY_KEYS, "Trojan") +
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
        val warnings = classifyFields(data, VMESS_FIELDS, "VMess")
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
        val warnings = classifyParameters(shadowQuery, SHADOWSOCKS_QUERY_KEYS, "Shadowsocks") +
            rejectForeignTransport(shadowQuery, "Shadowsocks")
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
        val uri = parseHysteria2Uri(link)
        val host = uri.host
        val query = uri.query
        rejectImpossibleTlsOptOut(query, "Hysteria2")
        val warnings = classifyPreservedHysteria2Parameters(query)
        val password = decodeHysteria2Component(uri.rawUserInfo, "authentication")
            .takeIf(String::isNotEmpty)
            ?: query["auth"]?.takeIf(String::isNotEmpty)
            ?: throw ImportException("В Hysteria2 отсутствует пароль.")
        val obfsType = query["obfs"]?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        if (obfsType != null && obfsType !in HYSTERIA2_SUPPORTED_OBFS_TYPES) {
            throw ImportException(
                "Hysteria2 obfs '$obfsType' не поддерживается официальным встроенным ядром.",
            )
        }
        val obfsPassword = query["obfspassword"]
        if (obfsType in HYSTERIA2_PASSWORD_OBFS_TYPES && obfsPassword.isNullOrEmpty()) {
            // Без пароля Salamander/Gecko не согласуется: сервер отвергал бы каждый пакет.
            throw ImportException("Hysteria2 obfs '$obfsType' требует obfs-password.")
        }
        val certificatePin = query["pinsha256"]
            ?.takeIf(String::isNotEmpty)
            ?.let(::normalizeCertificatePin)
        val insecure = query.boolean("insecure")
        if (insecure && certificatePin == null) {
            throw ImportException("Hysteria2 запрещает insecure без точного pinSHA256.")
        }
        val hopInterval = normalizeHysteria2HopInterval(query["hopinterval"])
        val minPacketSize = parseHysteria2PacketSize(query["minpacketsize"])
        val maxPacketSize = parseHysteria2PacketSize(query["maxpacketsize"])
        if (minPacketSize != null && maxPacketSize != null && minPacketSize > maxPacketSize) {
            throw ImportException("Hysteria2 содержит некорректный диапазон размера пакета.")
        }
        if (maxPacketSize != null && maxPacketSize > HYSTERIA2_MAX_PACKET_SIZE) {
            throw ImportException("Hysteria2 maxPacketSize превышает 2048 байт.")
        }
        if ((minPacketSize != null || maxPacketSize != null) && obfsType != "gecko") {
            throw ImportException("Hysteria2 min/max packet size допустимы только для Gecko.")
        }
        return ProtocolOutboundBuilders.hysteria2(
            displayName = decodeHysteria2Component(uri.rawFragment, "display name")
                .ifBlank { "Hysteria2 ${index + 1}" },
            server = host,
            serverPort = uri.firstPort,
            password = password,
            uri = uri.rawUri,
            tls = TlsSettings(
                enabled = true,
                serverName = query["sni"]?.takeIf(String::isNotEmpty) ?: host,
                insecure = insecure,
                echConfigPem = query["ech"]?.takeIf(String::isNotEmpty)?.let(::echConfigPem),
            ),
            obfsPassword = obfsPassword.takeIf { obfsType in HYSTERIA2_PASSWORD_OBFS_TYPES },
            obfsType = obfsType.takeIf { it in HYSTERIA2_PASSWORD_OBFS_TYPES },
            upMbps = parseHysteria2Mbps(query["up"], "up"),
            downMbps = parseHysteria2Mbps(query["down"], "down"),
            serverPorts = uri.serverPorts,
            hopInterval = hopInterval,
            certificateSha256 = certificatePin,
            obfsMinPacketSize = minPacketSize,
            obfsMaxPacketSize = maxPacketSize,
        ).withWarnings(warnings + insecureWarning(query))
    }

    private data class Hysteria2Uri(
        val rawUri: String,
        val rawUserInfo: String,
        val host: String,
        val firstPort: Int,
        val serverPorts: List<String>,
        val query: Map<String, String>,
        val rawFragment: String,
    )

    /** java.net.URI rejects the official port-union authority (443,2000-3000). */
    private fun parseHysteria2Uri(link: String): Hysteria2Uri {
        if (link.any { it.isWhitespace() || it.isISOControl() }) {
            throw ImportException("Hysteria2 URI содержит управляющий символ или пробел.")
        }
        val body = link.substringAfter("://", "")
        if (body.isBlank()) throw ImportException("Некорректная Hysteria2 ссылка.")
        val rawFragment = body.substringAfter('#', "")
        val beforeFragment = body.substringBefore('#')
        val rawQuery = beforeFragment.substringAfter('?', "")
        if (';' in rawQuery) throw ImportException("Hysteria2 URI содержит некорректный query separator.")
        val authority = beforeFragment.substringBefore('?').substringBefore('/').trim()
        val at = authority.lastIndexOf('@')
        val rawUserInfo = if (at >= 0) authority.substring(0, at) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val (host, portUnion) = if (hostPort.startsWith('[')) {
            val close = hostPort.indexOf(']')
            if (close <= 1) throw ImportException("В Hysteria2 отсутствует корректный IPv6-сервер.")
            val remainder = hostPort.substring(close + 1)
            if (remainder == ":" || remainder.isNotEmpty() && !remainder.startsWith(':')) {
                throw ImportException("Hysteria2 содержит некорректный IPv6-порт.")
            }
            hostPort.substring(1, close) to remainder.removePrefix(":").ifBlank { "443" }
        } else {
            val colon = hostPort.indexOf(':')
            if (colon < 0) {
                hostPort to "443"
            } else {
                if (colon == 0 || colon == hostPort.lastIndex) {
                    throw ImportException("В Hysteria2 отсутствует корректный сервер или порт.")
                }
                hostPort.substring(0, colon) to hostPort.substring(colon + 1)
            }
        }
        val decodedHost = decodeHysteria2Component(host, "server")
        if (decodedHost.isBlank()) throw ImportException("В Hysteria2 отсутствует сервер.")
        val ports = parseHysteria2Ports(portUnion)
        val parsedQuery = linkedMapOf<String, String>()
        rawQuery.split('&').filter(String::isNotBlank).forEach { part ->
            val key = canonicalKey(decodeHysteria2QueryComponent(part.substringBefore('='), "query key"))
            val value = decodeHysteria2QueryComponent(part.substringAfter('=', ""), "query value")
            if (key in HYSTERIA2_QUERY_KEYS && key in parsedQuery) {
                throw ImportException("Hysteria2 URI неоднозначно повторяет параметр '$key'.")
            }
            parsedQuery[key] = value
        }
        return Hysteria2Uri(
            rawUri = link,
            rawUserInfo = rawUserInfo,
            host = decodedHost,
            firstPort = ports.firstPort,
            serverPorts = ports.serverPorts,
            query = parsedQuery,
            rawFragment = rawFragment,
        )
    }

    private fun decodeHysteria2Component(value: String, field: String): String =
        decodeHysteria2(value, field, query = false)

    private fun decodeHysteria2QueryComponent(value: String, field: String): String =
        decodeHysteria2(value, field, query = true)

    private fun decodeHysteria2(value: String, field: String, query: Boolean): String =
        try {
            URLDecoder.decode(
                if (query) value else value.replace("+", "%2B"),
                StandardCharsets.UTF_8.name(),
            )
        } catch (error: IllegalArgumentException) {
            throw ImportException("Hysteria2 URI содержит некорректную кодировку в поле $field.", error)
        }.also { decoded ->
            if (decoded.any { it.isISOControl() }) {
                throw ImportException("Hysteria2 URI содержит управляющий символ в поле $field.")
            }
        }

    private data class Hysteria2Ports(val firstPort: Int, val serverPorts: List<String>)

    private fun parseHysteria2Ports(value: String): Hysteria2Ports {
        val normalized = value.ifBlank { "443" }.split(',').map { rawPart ->
            val part = rawPart.trim()
            if (part.isEmpty()) throw ImportException("Hysteria2 содержит пустой UDP-порт.")
            val separator = if (':' in part) ':' else '-'
            if (separator == ':' && part.count { it == ':' } != 1) {
                throw ImportException("IPv6-адрес Hysteria2 должен быть заключён в квадратные скобки.")
            }
            val bounds = part.split(separator).map(String::trim)
            if (bounds.size !in 1..2) {
                throw ImportException("Hysteria2 содержит некорректный диапазон портов '$part'.")
            }
            val start = bounds[0].toIntOrNull()?.takeIf { it in 1..65535 }
            val end = if (bounds.size == 2) {
                bounds[1].toIntOrNull()?.takeIf { it in 1..65535 }
            } else {
                start
            }
            if (start == null || end == null || start > end) {
                throw ImportException("Hysteria2 содержит некорректный диапазон портов '$part'.")
            }
            if (start == end) start.toString() else "$start-$end"
        }
        val firstPort = normalized.first().substringBefore('-').toInt()
        return Hysteria2Ports(
            firstPort,
            normalized.takeIf { it.size > 1 || '-' in it.single() }.orEmpty(),
        )
    }

    private fun parseHysteria2Mbps(value: String?, field: String): Int? {
        if (value == null || value.isEmpty()) return null
        if (value.isBlank()) throw ImportException("Hysteria2 содержит некорректную скорость $field.")
        val fields = value.trim().split(Regex("\\s+"))
        if (fields.size !in 1..2) throw ImportException("Hysteria2 содержит некорректную скорость $field.")
        val amount = fields[0].toLongOrNull()?.takeIf { it >= 0 }
            ?: throw ImportException("Hysteria2 содержит некорректную скорость $field.")
        val (factor, divisor) = when (fields.getOrElse(1) { "mbps" }.lowercase()) {
            "bps", "b/s" -> 1L to 1_000_000L
            "kbps", "kb/s" -> 1L to 1_000L
            "mbps", "mb/s" -> 1L to 1L
            "gbps", "gb/s" -> 1_000L to 1L
            "tbps", "tb/s" -> 1_000_000L to 1L
            else -> throw ImportException("Hysteria2 содержит неизвестную единицу скорости $field.")
        }
        if (amount > Int.MAX_VALUE.toLong() / factor) {
            throw ImportException("Hysteria2 содержит слишком большую скорость $field.")
        }
        return (amount * factor / divisor).toInt()
    }

    private fun normalizeHysteria2HopInterval(value: String?): String? {
        val interval = value?.takeIf(String::isNotEmpty) ?: return null
        interval.toLongOrNull()?.let { seconds ->
            if (seconds !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw ImportException("Hysteria2 hopInterval не помещается в общий Android ABI-контракт.")
            }
            return if (seconds == 0L) null else "${seconds}s"
        }
        if (!HYSTERIA2_DURATION.matches(interval)) {
            throw ImportException("Hysteria2 содержит некорректный hopInterval.")
        }
        val unsigned = interval.removePrefix("-").removePrefix("+")
        val nanoseconds = HYSTERIA2_DURATION_PART.findAll(unsigned).fold(BigDecimal.ZERO) { total, match ->
            val amount = match.groupValues[1].toBigDecimal()
            val multiplier = when (match.groupValues[2]) {
                "ns" -> BigDecimal.ONE
                "us", "µs", "μs" -> BigDecimal("1000")
                "ms" -> BigDecimal("1000000")
                "s" -> BigDecimal("1000000000")
                "m" -> BigDecimal("60000000000")
                "h" -> BigDecimal("3600000000000")
                else -> error("Duration regex and unit table diverged")
            }
            total + amount * multiplier
        }
        if (nanoseconds > BigDecimal(Long.MAX_VALUE)) {
            throw ImportException("Hysteria2 содержит слишком большой hopInterval.")
        }
        return interval.takeIf { nanoseconds >= BigDecimal.ONE }
    }

    private fun parseHysteria2PacketSize(value: String?): Int? {
        val text = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val size = text.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw ImportException("Hysteria2 содержит некорректный размер пакета.")
        return size.takeIf { it != 0 }
    }

    private fun normalizeCertificatePin(value: String): String {
        val normalized = value.trim().lowercase().replace(":", "").replace("-", "")
        if (normalized.length != 64 || normalized.any { it !in "0123456789abcdef" }) {
            throw ImportException("Hysteria2 pinSHA256 должен содержать ровно 32 байта SHA-256.")
        }
        return normalized
    }

    private fun echConfigPem(value: String): String {
        val compact = value.filterNot(Char::isWhitespace)
        val decoded = runCatching { Base64.getUrlDecoder().decode(compact) }
            .recoverCatching { Base64.getDecoder().decode(compact) }
            .getOrElse { throw ImportException("Hysteria2 ECH содержит некорректный base64.", it) }
        if (decoded.isEmpty()) throw ImportException("Hysteria2 ECH пуст.")
        val encoded = Base64.getEncoder().encodeToString(decoded)
        return "-----BEGIN ECH CONFIGS-----\n$encoded\n-----END ECH CONFIGS-----"
    }

    private fun parseTuic(link: String, index: Int): ManagedServer {
        val uri = URI(link)
        val host = requireHost(uri)
        val query = query(uri)
        rejectImpossibleTlsOptOut(query, "TUIC")
        val warnings = classifyParameters(query, TUIC_QUERY_KEYS, "TUIC") +
            rejectCertificatePinning(query, "TUIC")
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
        val security = query["security"]?.lowercase()?.takeIf(String::isNotBlank)
        if (security != null && security !in KNOWN_SECURITY_MODES) {
            // Неизвестный режим иначе прочитался бы как «без TLS», и ссылка,
            // обещавшая шифрование, молча дала бы открытый канал.
            throw ImportException("Режим security '$security' не поддерживается.")
        }
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
     * Исполнимое переносится в конфигурацию, всё остальное теряется с поимённым
     * предупреждением в preview. Отказ остаётся за отдельными проверками: они
     * названы поимённо и срабатывают на параметр, который меняет шифрование,
     * доверие или вид трафика на проводе.
     *
     * Отказ по незнакомому имени пробовался и не работает: панели постоянно
     * добавляют собственные параметры (`spx`, `fm`, `vcn`, `pcs`), каждый из них
     * ломал импорт рабочей ссылки, а перечислить их заранее нельзя. Ни один
     * эталонный клиент так не делает, и спецификация Hysteria2 прямо разрешает
     * чужие параметры, требуя лишь не полагаться на них.
     */
    private fun classifyParameters(
        query: Map<String, String>,
        executable: Set<String>,
        protocol: String,
    ): List<String> {
        val dropped = query.keys.filterNot(executable::contains).sorted()
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
     * Hysteria2 is different from the translating importers above: the exact
     * share URI is retained in the outbound and parsed again by the embedded
     * official core. Unknown parameters therefore remain available to newer
     * cores even when the current core does not interpret them.
     */
    private fun classifyPreservedHysteria2Parameters(
        query: Map<String, String>,
    ): List<String> {
        val unknown = query.keys.filterNot(HYSTERIA2_QUERY_KEYS::contains).sorted()
        return if (unknown.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "Hysteria2: параметры сохранены в исходной URI; текущее ядро может их игнорировать — " +
                    "${unknown.joinToString()}.",
            )
        }
    }

    /**
     * Пиннинг сертификата перевести нельзя: hysteria2 закрепляет весь сертификат,
     * а sing-box — только публичный ключ.
     *
     * Когда пин стоит рядом с `insecure`, проверка сертификата отключена самой
     * ссылкой, и пин уже ничего не добавляет: отказ отнял бы у пользователя
     * рабочий сервер, ничего не защитив. Пин без `insecure` — другое дело: там он
     * единственная аутентификация сервера, и потерять его молча нельзя.
     */
    private fun rejectCertificatePinning(
        query: Map<String, String>,
        protocol: String,
    ): List<String> {
        if (query["pinsha256"].isNullOrBlank()) return emptyList()
        if (query.boolean("insecure")) {
            return listOf(
                "$protocol: закрепление сертификата (pinSHA256) не перенесено, " +
                    "проверка сертификата отключена самой ссылкой.",
            )
        }
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
        protocol: String,
    ): List<String> {
        val keys = data.keys.map { it.lowercase() }
        val dropped = keys.filterNot(executable::contains).sorted()
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
        this[key]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

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

    private val KNOWN_SECURITY_MODES = setOf("none", "tls", "reality")
    private val TRANSPORT_QUERY_KEYS = setOf(
        "type", "path", "host", "servicename", "mode", "extra",
    )
    private val TLS_QUERY_KEYS = setOf(
        "security", "sni", "insecure", "fp", "pbk", "sid", "alpn",
    )

    private val VLESS_QUERY_KEYS = TRANSPORT_QUERY_KEYS + TLS_QUERY_KEYS +
        setOf("flow", "encryption", "headertype")
    private val TROJAN_QUERY_KEYS = TRANSPORT_QUERY_KEYS + TLS_QUERY_KEYS + setOf("headertype")
    private val SHADOWSOCKS_QUERY_KEYS = setOf("type")
    private val HYSTERIA2_QUERY_KEYS = setOf(
        "auth", "sni", "insecure", "obfs", "obfspassword", "up", "down",
        "pinsha256", "ech", "hopinterval", "minpacketsize", "maxpacketsize",
    )

    private val TUIC_QUERY_KEYS = setOf(
        "sni", "insecure", "alpn", "congestioncontrol", "udprelaymode",
        "zerortthandshake", "heartbeat",
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

    fun readSingleQrImage(uri: Uri): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Не удалось открыть выбранное изображение.")
        boundsStream.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw ImportException("Выбранный файл не является изображением.")
        }
        var sample = 1
        while (options.outWidth / sample > MAX_QR_IMAGE_SIDE || options.outHeight / sample > MAX_QR_IMAGE_SIDE) {
            sample *= 2
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw ImportException("Не удалось декодировать изображение.")
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val binary = BinaryBitmap(
                HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)),
            )
            val values = runCatching { QRCodeMultiReader().decodeMultiple(binary).map { it.text.trim() } }
                .getOrDefault(emptyList())
                .filter(String::isNotBlank)
            when (values.size) {
                1 -> values.single()
                0 -> throw ImportException("На изображении не найден корректный QR-код.")
                else -> throw ImportException("На изображении должен быть ровно один QR-код.")
            }
        } finally {
            bitmap.recycle()
        }
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
        const val MAX_QR_IMAGE_SIDE = 2048
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
