package io.github.zapretkvn.wireguardimport

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WireGuardImportException(message: String) : Exception(message)

data class WireGuardImportResult(
    val json: String,
    val protocolName: String,
    val endpointLabel: String?,
)

/** Strict, UI-independent converter from WireGuard/AmneziaWG INI to sing-box JSON. */
object WireGuardConfigParser {
    fun looksLikeConfig(input: String): Boolean = input.lineSequence()
        .map(::withoutComment)
        .map(String::trim)
        .any { it.equals("[Interface]", true) || it.equals("[Peer]", true) }

    fun parse(
        input: String,
    ): WireGuardImportResult {
        val document = parseDocument(input)
        val interfaceValues = document.interfaceValues
        val addresses = interfaceValues.list("address", required = true).map {
            parsePrefix(it, "Address")
        }.distinct()
        val privateKey = canonicalKey(
            checkNotNull(interfaceValues.single("privatekey", required = true)),
            "PrivateKey",
        )
        val listenPort = interfaceValues.single("listenport")?.let {
            parseInt(it, "ListenPort", 0..65535).takeIf { port -> port != 0 }
        }
        val mtu = interfaceValues.single("mtu")?.let {
            parseInt(it, "MTU", 0..65535).takeIf { value -> value != 0 }
        } ?: ANDROID_WIREGUARD_MTU
        val dnsServers = interfaceValues.list("dns").map { parseNumericAddress(it, "DNS") }.distinct()
        rejectUnsupportedInterfaceKeys(interfaceValues)

        val amnezia = parseAmnezia(interfaceValues)
        val peers = document.peers.mapIndexed(::parsePeer)
        if (peers.isEmpty()) throw WireGuardImportException("В конфигурации отсутствует секция [Peer].")
        if (peers.none { it.address != null }) {
            throw WireGuardImportException("Ни у одного [Peer] не указан Endpoint.")
        }

        val endpointTag = if (amnezia != null) "amneziawg-out" else "wireguard-out"
        val allowedPrefixes = peers.flatMap(WireGuardPeer::allowedIPs).distinct()
        val json = buildProfileJson(
            endpointTag = endpointTag,
            addresses = addresses,
            privateKey = privateKey,
            listenPort = listenPort,
            mtu = mtu,
            peers = peers,
            amnezia = amnezia,
            dnsServers = dnsServers,
            allowedPrefixes = allowedPrefixes,
        )
        val awg3 = amnezia?.keys?.any { it in AWG3_JSON_KEYS } == true
        val awg2 = amnezia?.keys?.any { it in AWG2_KEYS } == true
        val protocol = when {
            awg3 -> "AmneziaWG 3.0"
            awg2 -> "AmneziaWG 2.0"
            amnezia != null -> "AmneziaWG"
            else -> "WireGuard"
        }
        val endpointLabel = peers.firstNotNullOfOrNull { peer ->
            peer.address?.let { formatEndpoint(it, checkNotNull(peer.port)) }
        }
        return WireGuardImportResult(
            json = json,
            protocolName = protocol,
            endpointLabel = endpointLabel,
        )
    }

    private fun parseDocument(input: String): WireGuardDocument {
        var section: String? = null
        var seenInterface = false
        var interfaceCount = 0
        val interfaceValues = LinkedHashMap<String, MutableList<IniValue>>()
        val peers = mutableListOf<LinkedHashMap<String, MutableList<IniValue>>>()
        var peerValues: LinkedHashMap<String, MutableList<IniValue>>? = null

        input.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = withoutComment(rawLine).trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith('[')) {
                when {
                    line.equals("[Interface]", true) -> {
                        interfaceCount++
                        if (interfaceCount > 1) {
                            throw WireGuardImportException("Допускается только одна секция [Interface].")
                        }
                        seenInterface = true
                        section = "interface"
                        peerValues = null
                    }
                    line.equals("[Peer]", true) -> {
                        if (!seenInterface) {
                            throw WireGuardImportException("Секция [Peer] не может идти до [Interface].")
                        }
                        section = "peer"
                        peerValues = LinkedHashMap<String, MutableList<IniValue>>().also(peers::add)
                    }
                    else -> throw WireGuardImportException("Неизвестная секция '$line' в строке $lineNumber.")
                }
                return@forEachIndexed
            }
            val current = when (section) {
                "interface" -> interfaceValues
                "peer" -> checkNotNull(peerValues)
                else -> throw WireGuardImportException("Параметр до [Interface] в строке $lineNumber.")
            }
            val separator = line.indexOf('=')
            if (separator <= 0) throw WireGuardImportException("Некорректная строка $lineNumber в .conf.")
            val originalKey = line.substring(0, separator).trim()
            if (!INI_KEY.matches(originalKey)) {
                throw WireGuardImportException("Некорректное имя параметра в строке $lineNumber.")
            }
            val value = line.substring(separator + 1).trim()
            current.getOrPut(originalKey.lowercase()) { mutableListOf() }
                .add(IniValue(value, originalKey, lineNumber))
        }
        if (!seenInterface) throw WireGuardImportException("В конфигурации отсутствует секция [Interface].")
        return WireGuardDocument(ValueMap(interfaceValues), peers.map(::ValueMap))
    }

    private fun parsePeer(index: Int, values: ValueMap): WireGuardPeer {
        rejectUnsupportedPeerKeys(values, index)
        val publicKey = canonicalKey(
            checkNotNull(values.single("publickey", required = true)),
            "PublicKey",
        )
        val preSharedKey = values.single("presharedkey")?.let { canonicalKey(it, "PresharedKey") }
        val allowedIPs = values.list("allowedips", required = true).map {
            parsePrefix(it, "AllowedIPs", requireNetworkAddress = true)
        }.distinct()
        val endpoint = values.single("endpoint")?.let(::parseEndpoint)
        val keepalive = values.single("persistentkeepalive")?.let {
            parseInt(it, "PersistentKeepalive", 0..65535).takeIf { value -> value != 0 }
        }
        return WireGuardPeer(
            address = endpoint?.first,
            port = endpoint?.second,
            publicKey = publicKey,
            preSharedKey = preSharedKey,
            allowedIPs = allowedIPs,
            persistentKeepalive = keepalive,
        )
    }

    private fun parseAmnezia(values: ValueMap): Map<String, JsonElement>? {
        val result = linkedMapOf<String, JsonElement>()
        val integers = linkedMapOf<String, Int>()
        AWG_INTEGER_KEYS.forEach { key ->
            values.single(key)?.let { raw ->
                val value = parseInt(raw, raw.originalKey, 0..MAX_AWG_INTEGER)
                integers[key] = value
                if (value != 0) result[key] = JsonPrimitive(value)
            }
        }
        val jMin = integers["jmin"] ?: 0
        val jMax = integers["jmax"] ?: 0
        if (jMin > jMax) {
            throw WireGuardImportException("Jmin не может быть больше Jmax.")
        }
        AWG_HEADER_KEYS.forEach { key ->
            values.single(key)?.value?.takeIf(String::isNotBlank)?.let { value ->
                result[key] = parseHeaderRange(value, key.uppercase())
            }
        }
        AWG2_KEYS.forEach { key ->
            values.single(key)?.value?.takeIf(String::isNotBlank)?.let { value ->
                if (value.length >= MAX_AWG_STRING_LENGTH) {
                    throw WireGuardImportException("${key.uppercase()} длиннее допустимого формата AWG 2.0.")
                }
                validateObfuscationChain(value, key.uppercase())
                result[key] = JsonPrimitive(value)
            }
        }
        values.single(AWG3_HEADER_PROTECTION_KEY)?.let { raw ->
            result["header_protection_key"] =
                JsonPrimitive(canonicalKey(raw, "HeaderProtectionKey"))
        }
        AWG3_RANGE_KEYS.forEach { (iniKey, jsonKey) ->
            values.single(iniKey)?.value?.takeIf(String::isNotBlank)?.let { value ->
                result[jsonKey] = parseHeaderRange(value, checkNotNull(AWG3_CONF_NAMES[iniKey]))
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun buildProfileJson(
        endpointTag: String,
        addresses: List<String>,
        privateKey: String,
        listenPort: Int?,
        mtu: Int,
        peers: List<WireGuardPeer>,
        amnezia: Map<String, JsonElement>?,
        dnsServers: List<String>,
        allowedPrefixes: List<String>,
    ): String = JSON.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put(
                "dns",
                buildJsonObject {
                    put(
                        "servers",
                        buildJsonArray {
                            add(buildJsonObject {
                                put("type", "local")
                                put("tag", BOOTSTRAP_DNS_TAG)
                            })
                            dnsServers.forEachIndexed { index, server ->
                                add(buildJsonObject {
                                    put("type", "udp")
                                    put("tag", "$IMPORTED_DNS_TAG_PREFIX${index + 1}")
                                    put("server", server)
                                    if (allowedPrefixes.any { prefixContains(it, server) }) {
                                        put("detour", endpointTag)
                                    }
                                })
                            }
                        },
                    )
                    put("final", dnsServers.indices.firstOrNull()?.let { "$IMPORTED_DNS_TAG_PREFIX${it + 1}" }
                        ?: BOOTSTRAP_DNS_TAG)
                },
            )
            put(
                "endpoints",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "wireguard")
                        put("tag", endpointTag)
                        put("mtu", mtu)
                        put("address", JsonArray(addresses.map(::JsonPrimitive)))
                        put("private_key", privateKey)
                        listenPort?.let { put("listen_port", it) }
                        put("peers", JsonArray(peers.map { it.toJson() }))
                        amnezia?.let { put("amnezia", JsonObject(it)) }
                    })
                },
            )
            put(
                "inbounds",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        put(
                            "address",
                            JsonArray(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126").map(::JsonPrimitive)),
                        )
                        put("auto_route", true)
                    })
                },
            )
            put(
                "outbounds",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    })
                },
            )
            put(
                "route",
                buildJsonObject {
                    put("auto_detect_interface", true)
                    put("default_domain_resolver", BOOTSTRAP_DNS_TAG)
                    put(
                        "rules",
                        buildJsonArray {
                            add(buildJsonObject {
                                put("port", 53)
                                put("action", "hijack-dns")
                            })
                            add(buildJsonObject {
                                put("ip_cidr", JsonArray(allowedPrefixes.map(::JsonPrimitive)))
                                put("action", "route")
                                put("outbound", endpointTag)
                            })
                        },
                    )
                    put("final", "direct")
                },
            )
        },
    ) + "\n"

    private fun WireGuardPeer.toJson(): JsonObject = buildJsonObject {
        address?.let { put("address", it) }
        port?.let { put("port", it) }
        put("public_key", publicKey)
        preSharedKey?.let { put("pre_shared_key", it) }
        put("allowed_ips", JsonArray(allowedIPs.map(::JsonPrimitive)))
        persistentKeepalive?.let { put("persistent_keepalive_interval", it) }
    }

    private fun rejectUnsupportedInterfaceKeys(values: ValueMap) {
        val unknown = values.keys - SUPPORTED_INTERFACE_KEYS
        if (unknown.isNotEmpty()) {
            val key = values.first(unknown.first()).originalKey
            val hint = if (key.equals("IncludedApplications", true) || key.equals("ExcludedApplications", true)) {
                " Настройте приложения в разделе приложения Zapret KVN."
            } else {
                ""
            }
            throw WireGuardImportException("Параметр [Interface] '$key' не поддерживается.$hint")
        }
    }

    private fun rejectUnsupportedPeerKeys(values: ValueMap, index: Int) {
        val unknown = values.keys - SUPPORTED_PEER_KEYS
        if (unknown.isNotEmpty()) {
            val key = values.first(unknown.first()).originalKey
            throw WireGuardImportException("Параметр [Peer] №${index + 1} '$key' не поддерживается.")
        }
    }

    private fun canonicalKey(raw: IniValue, name: String): String {
        val bytes = try {
            Base64.getDecoder().decode(raw.value)
        } catch (_: IllegalArgumentException) {
            throw WireGuardImportException("$name должен быть ключом WireGuard в Base64.")
        }
        if (bytes.size != 32) throw WireGuardImportException("$name должен содержать ровно 32 байта.")
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun parseInt(raw: IniValue, name: String, range: IntRange): Int {
        val value = raw.value.toIntOrNull()
            ?: throw WireGuardImportException("$name должен быть целым числом (строка ${raw.lineNumber}).")
        if (value !in range) throw WireGuardImportException("Недопустимое значение $name в строке ${raw.lineNumber}.")
        return value
    }

    private fun parseHeaderRange(value: String, name: String): JsonPrimitive {
        val parts = value.split('-')
        if (parts.size !in 1..2) throw WireGuardImportException("$name должен быть числом или диапазоном A-B.")
        val bounds = parts.map { it.toULongOrNull()?.takeIf { number -> number <= UInt.MAX_VALUE.toULong() } }
        if (bounds.any { it == null }) throw WireGuardImportException("$name выходит за диапазон UInt32.")
        if (parts.size == 2 && checkNotNull(bounds[0]) > checkNotNull(bounds[1])) {
            throw WireGuardImportException("В $name начало диапазона больше конца.")
        }
        return if (parts.size == 1) JsonPrimitive(checkNotNull(bounds.single()).toLong()) else JsonPrimitive(value)
    }

    private fun validateObfuscationChain(value: String, name: String) {
        var cursor = 0
        var tokenCount = 0
        var fixedSize = 0
        while (cursor < value.length) {
            if (value[cursor] != '<') throw WireGuardImportException("$name содержит текст вне AWG 2.0 тегов.")
            val end = value.indexOf('>', cursor + 1)
            if (end < 0) throw WireGuardImportException("В $name отсутствует закрывающий символ '>'.")
            val parts = value.substring(cursor + 1, end).trim().split(WHITESPACE).filter(String::isNotEmpty)
            if (parts.isEmpty() || parts.size > 2) throw WireGuardImportException("Некорректный тег в $name.")
            val tag = parts[0]
            val argument = parts.getOrNull(1)
            when (tag) {
                "b" -> {
                    val hex = argument?.removePrefix("0x").orEmpty()
                    if (hex.isEmpty() || hex.length % 2 != 0 || !HEX.matches(hex)) {
                        throw WireGuardImportException("Тег <b> в $name должен содержать байты в hex.")
                    }
                    fixedSize += hex.length / 2
                }
                "r", "rc", "rd", "dz" -> {
                    val size = argument?.toIntOrNull()
                    if (size == null || size < 0 || size > MAX_OBFUSCATION_SIZE) {
                        throw WireGuardImportException("Недопустимый размер в теге <$tag> поля $name.")
                    }
                    fixedSize += size
                }
                "c", "t", "d", "ds" -> {
                    if (argument != null) {
                        throw WireGuardImportException("Тег <$tag> поля $name не принимает аргумент.")
                    }
                    if (tag == "c" || tag == "t") fixedSize += 4
                }
                else -> throw WireGuardImportException("Неизвестный AWG 2.0 тег <$tag> в $name.")
            }
            if (fixedSize > MAX_OBFUSCATION_SIZE) {
                throw WireGuardImportException("Цепочка $name создаёт слишком большой пакет.")
            }
            tokenCount++
            cursor = end + 1
        }
        if (tokenCount == 0) throw WireGuardImportException("$name не содержит AWG 2.0 тегов.")
    }

    private fun parsePrefix(
        value: String,
        name: String,
        requireNetworkAddress: Boolean = false,
    ): String {
        val slash = value.lastIndexOf('/')
        val addressText = if (slash >= 0) value.substring(0, slash).trim() else value.trim()
        val address = parseAddress(addressText, name)
        val maxPrefix = if (address.bytes.size == 4) 32 else 128
        val prefix = if (slash >= 0) value.substring(slash + 1).trim().toIntOrNull() else maxPrefix
        if (prefix == null || prefix !in 0..maxPrefix) throw WireGuardImportException("Некорректная маска в $name: '$value'.")
        if (requireNetworkAddress && !isNetworkAddress(address.bytes, prefix)) {
            throw WireGuardImportException("$name должен содержать адрес сети без host-битов: '$value'.")
        }
        return "${address.canonical}/$prefix"
    }

    private fun isNetworkAddress(bytes: ByteArray, prefixLength: Int): Boolean {
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        if (remainingBits != 0) {
            val hostMask = (1 shl (8 - remainingBits)) - 1
            if ((bytes[fullBytes].toInt() and 0xFF and hostMask) != 0) return false
        }
        val firstHostByte = fullBytes + if (remainingBits == 0) 0 else 1
        return (firstHostByte until bytes.size).all { bytes[it].toInt() == 0 }
    }

    private fun parseNumericAddress(value: String, name: String): String = parseAddress(value.trim(), name).canonical

    private fun parseAddress(value: String, name: String): ParsedAddress {
        parseIpv4(value)?.let { return ParsedAddress(it.joinToString("."), it.map(Int::toByte).toByteArray()) }
        if (':' !in value || '%' in value) throw WireGuardImportException("$name содержит нечисловой IP-адрес '$value'.")
        val address = try {
            InetAddress.getByName(value)
        } catch (_: Exception) {
            throw WireGuardImportException("Некорректный IPv6-адрес в $name.")
        }
        if (address !is Inet6Address) throw WireGuardImportException("Некорректный IP-адрес в $name.")
        return ParsedAddress(checkNotNull(address.hostAddress).substringBefore('%'), address.address)
    }

    private fun parseIpv4(value: String): List<Int>? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
    }

    private fun parseEndpoint(raw: IniValue): Pair<String, Int> {
        val value = raw.value
        val (host, portText) = if (value.startsWith('[')) {
            val end = value.indexOf(']')
            if (end <= 1 || end + 1 >= value.length || value[end + 1] != ':') {
                throw WireGuardImportException("Некорректный IPv6 Endpoint в строке ${raw.lineNumber}.")
            }
            value.substring(1, end) to value.substring(end + 2)
        } else {
            if (value.count { it == ':' } != 1) {
                throw WireGuardImportException("IPv6 Endpoint должен быть записан как [адрес]:порт.")
            }
            value.substringBeforeLast(':') to value.substringAfterLast(':')
        }
        if (host.isBlank() || host.any(Char::isWhitespace) || host.any { it in "/?#[]" }) {
            throw WireGuardImportException("Некорректный адрес Endpoint в строке ${raw.lineNumber}.")
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw WireGuardImportException("Некорректный порт Endpoint в строке ${raw.lineNumber}.")
        val normalizedHost = when {
            parseIpv4(host) != null -> parseNumericAddress(host, "Endpoint")
            ':' in host -> parseNumericAddress(host, "Endpoint")
            HOSTNAME.matches(host) -> host
            else -> throw WireGuardImportException("Некорректный адрес Endpoint в строке ${raw.lineNumber}.")
        }
        return normalizedHost to port
    }

    private fun prefixContains(prefix: String, address: String): Boolean {
        val parsedPrefix = runCatching { parsePrefixForMatch(prefix) }.getOrNull() ?: return false
        val parsedAddress = runCatching { parseAddress(address, "DNS").bytes }.getOrNull() ?: return false
        if (parsedPrefix.bytes.size != parsedAddress.size) return false
        val fullBytes = parsedPrefix.length / 8
        val remainingBits = parsedPrefix.length % 8
        for (index in 0 until fullBytes) {
            if (parsedPrefix.bytes[index] != parsedAddress[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (parsedPrefix.bytes[fullBytes].toInt() and mask) ==
            (parsedAddress[fullBytes].toInt() and mask)
    }

    private fun parsePrefixForMatch(value: String): ParsedPrefix {
        val slash = value.lastIndexOf('/')
        val address = parseAddress(value.substring(0, slash), "AllowedIPs")
        return ParsedPrefix(address.bytes, value.substring(slash + 1).toInt())
    }

    private fun formatEndpoint(host: String, port: Int): String =
        if (':' in host) "[$host]:$port" else "$host:$port"

    private fun withoutComment(line: String): String = line.substringBefore('#')

    private data class WireGuardDocument(
        val interfaceValues: ValueMap,
        val peers: List<ValueMap>,
    )

    private data class WireGuardPeer(
        val address: String?,
        val port: Int?,
        val publicKey: String,
        val preSharedKey: String?,
        val allowedIPs: List<String>,
        val persistentKeepalive: Int?,
    )

    private data class IniValue(val value: String, val originalKey: String, val lineNumber: Int)

    private class ValueMap(private val values: Map<String, List<IniValue>>) {
        val keys: Set<String> get() = values.keys

        fun first(key: String): IniValue = checkNotNull(values[key]?.firstOrNull())

        fun single(key: String, required: Boolean = false): IniValue? {
            val entries = values[key].orEmpty()
            if (entries.isEmpty()) {
                if (required) throw WireGuardImportException("В конфигурации отсутствует ${displayKey(key)}.")
                return null
            }
            if (entries.size > 1) throw WireGuardImportException("Параметр ${entries.first().originalKey} указан несколько раз.")
            if (entries.single().value.isBlank() && required) {
                throw WireGuardImportException("Параметр ${entries.single().originalKey} пуст.")
            }
            return entries.single()
        }

        fun list(key: String, required: Boolean = false): List<String> {
            val entries = values[key].orEmpty()
            val result = entries.flatMap { entry ->
                entry.value.split(LIST_SEPARATOR).map(String::trim).filter(String::isNotBlank)
            }
            if (required && result.isEmpty()) throw WireGuardImportException("В конфигурации отсутствует ${displayKey(key)}.")
            return result
        }

        private fun displayKey(key: String): String = when (key) {
            "privatekey" -> "PrivateKey"
            "publickey" -> "PublicKey"
            "address" -> "Address"
            "allowedips" -> "AllowedIPs"
            else -> key
        }
    }

    private data class ParsedAddress(val canonical: String, val bytes: ByteArray)
    private data class ParsedPrefix(val bytes: ByteArray, val length: Int)

    private val INI_KEY = Regex("[A-Za-z0-9_]+")
    private val LIST_SEPARATOR = Regex("\\s*,\\s*")
    private val WHITESPACE = Regex("\\s+")
    private val HEX = Regex("[0-9a-fA-F]+")
    private val HOSTNAME = Regex("(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(?:\\.(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*\\.?")
    private val AWG_INTEGER_KEYS = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
    private val AWG_HEADER_KEYS = setOf("h1", "h2", "h3", "h4")
    private val AWG2_KEYS = setOf("i1", "i2", "i3", "i4", "i5")

    // AWG 3.0: ini-ключ (нижний регистр) -> поле объекта amnezia sing-box
    // extended 2.6.x. HeaderProtectionKey обязан совпадать с сервером, поэтому
    // молчаливый пропуск этих строк означал мёртвый туннель без ошибки.
    private const val AWG3_HEADER_PROTECTION_KEY = "headerprotectionkey"
    private val AWG3_RANGE_KEYS = linkedMapOf(
        "contentpaddingaddition" to "content_padding_addition",
        "rekeyaftertime" to "rekey_after_time",
        "rekeytimeout" to "rekey_timeout",
        "rejectaftertime" to "reject_after_time",
        "keepalivetimeout" to "keepalive_timeout",
        "maxhandshakeattempts" to "max_handshake_attempts",
    )
    private val AWG3_CONF_NAMES = mapOf(
        "contentpaddingaddition" to "ContentPaddingAddition",
        "rekeyaftertime" to "RekeyAfterTime",
        "rekeytimeout" to "RekeyTimeout",
        "rejectaftertime" to "RejectAfterTime",
        "keepalivetimeout" to "KeepaliveTimeout",
        "maxhandshakeattempts" to "MaxHandshakeAttempts",
    )
    private val AWG3_JSON_KEYS = setOf("header_protection_key") + AWG3_RANGE_KEYS.values
    private val SUPPORTED_INTERFACE_KEYS = setOf(
        "address",
        "dns",
        "listenport",
        "mtu",
        "privatekey",
        AWG3_HEADER_PROTECTION_KEY,
    ) + AWG_INTEGER_KEYS + AWG_HEADER_KEYS + AWG2_KEYS + AWG3_RANGE_KEYS.keys
    private val SUPPORTED_PEER_KEYS = setOf(
        "allowedips",
        "endpoint",
        "persistentkeepalive",
        "presharedkey",
        "publickey",
    )

    private const val BOOTSTRAP_DNS_TAG = "wireguard-bootstrap-dns"
    private const val IMPORTED_DNS_TAG_PREFIX = "wireguard-dns-"
    // Official Amnezia uses this conservative default for WireGuard/AWG on Android.
    // It is the inner WireGuard MTU, independent from the outer Android TUN MTU.
    private const val ANDROID_WIREGUARD_MTU = 1280
    private const val MAX_AWG_INTEGER = 65_535
    private const val MAX_AWG_STRING_LENGTH = 5 * 1024
    private const val MAX_OBFUSCATION_SIZE = 65_535

    private val JSON = Json {
        explicitNulls = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}
