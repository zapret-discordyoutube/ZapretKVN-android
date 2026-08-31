package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ManagedServer(
    val displayName: String,
    val identityKey: String,
    /**
     * Identity used only to reconcile members of a refreshed split subscription.
     * It may deliberately outlive a transport/publication change, while [identityKey]
     * continues to distinguish the exact runtime outbound and its stable tag.
     */
    val refreshIdentityKey: String = identityKey,
    val outbound: JsonObject,
    /** Параметры ссылки, которые протокол выполнить не может и которые названы в preview. */
    val importWarnings: List<String> = emptyList(),
)

data class TlsSettings(
    val enabled: Boolean = false,
    val serverName: String? = null,
    val insecure: Boolean = false,
    val utlsFingerprint: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val alpn: List<String> = emptyList(),
    val echConfigPem: String? = null,
)

data class TransportSettings(
    val type: String,
    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null,
    val mode: String? = null,
    val xhttpOptions: JsonObject? = null,
)

object ProtocolOutboundBuilders {
    fun vless(
        displayName: String,
        server: String,
        serverPort: Int,
        uuid: String,
        encryption: String = "none",
        flow: String? = null,
        tls: TlsSettings = TlsSettings(),
        transport: TransportSettings? = null,
    ): ManagedServer = ManagedServer(
        displayName = displayName,
        identityKey = identity("vless", server, serverPort, transport),
        outbound = buildJsonObject {
            put("type", "vless")
            put("server", server)
            put("server_port", serverPort)
            put("uuid", uuid)
            put("encryption", encryption)
            flow?.takeIf(String::isNotBlank)?.let { put("flow", it) }
            putTls(tls)
            transport?.let { put("transport", it.toJson()) }
        },
    )

    fun vmess(
        displayName: String,
        server: String,
        serverPort: Int,
        uuid: String,
        security: String = "auto",
        alterId: Int = 0,
        tls: TlsSettings = TlsSettings(),
        transport: TransportSettings? = null,
    ): ManagedServer = ManagedServer(
        displayName = displayName,
        identityKey = identity("vmess", server, serverPort, transport),
        outbound = buildJsonObject {
            put("type", "vmess")
            put("server", server)
            put("server_port", serverPort)
            put("uuid", uuid)
            put("security", security)
            if (alterId != 0) put("alter_id", alterId)
            putTls(tls)
            transport?.let { put("transport", it.toJson()) }
        },
    )

    fun trojan(
        displayName: String,
        server: String,
        serverPort: Int,
        password: String,
        tls: TlsSettings = TlsSettings(enabled = true, serverName = server),
        transport: TransportSettings? = null,
    ): ManagedServer = ManagedServer(
        displayName = displayName,
        identityKey = identity("trojan", server, serverPort, transport),
        outbound = buildJsonObject {
            put("type", "trojan")
            put("server", server)
            put("server_port", serverPort)
            put("password", password)
            putTls(tls)
            transport?.let { put("transport", it.toJson()) }
        },
    )

    fun shadowsocks(
        displayName: String,
        server: String,
        serverPort: Int,
        method: String,
        password: String,
    ): ManagedServer = ManagedServer(
        displayName = displayName,
        identityKey = identity("shadowsocks", server, serverPort, null),
        outbound = buildJsonObject {
            put("type", "shadowsocks")
            put("server", server)
            put("server_port", serverPort)
            put("method", method)
            put("password", password)
        },
    )

    fun hysteria2(
        displayName: String,
        server: String,
        serverPort: Int,
        password: String,
        tls: TlsSettings = TlsSettings(enabled = true, serverName = server),
        obfsPassword: String? = null,
        obfsType: String? = null,
        upMbps: Int? = null,
        downMbps: Int? = null,
        serverPorts: List<String> = emptyList(),
        hopInterval: String? = null,
        certificateSha256: String? = null,
        obfsMinPacketSize: Int? = null,
        obfsMaxPacketSize: Int? = null,
        uri: String? = null,
    ): ManagedServer = ManagedServer(
        displayName = displayName,
        identityKey = identity("hysteria2", server, serverPort, null) +
            uri?.takeIf(String::isNotBlank)?.let { "|uri-sha256:${uriFingerprint(it)}" }.orEmpty(),
        refreshIdentityKey = uri?.takeIf(String::isNotBlank)
            ?.let { hysteria2CredentialIdentity(password) }
            ?: identity("hysteria2", server, serverPort, null),
        outbound = buildJsonObject {
            put("type", "hysteria2")
            put("server", server)
            put("server_port", serverPort)
            put("password", password)
            uri?.takeIf(String::isNotBlank)?.let { put("uri", it) }
            obfsPassword?.takeIf(String::isNotEmpty)?.let { value ->
                put(
                    "obfs",
                    buildJsonObject {
                        put("type", obfsType?.takeIf(String::isNotBlank) ?: "salamander")
                        put("password", value)
                        obfsMinPacketSize?.takeIf { it > 0 }?.let { put("min_packet_size", it) }
                        obfsMaxPacketSize?.takeIf { it > 0 }?.let { put("max_packet_size", it) }
                    },
                )
            }
            upMbps?.takeIf { it > 0 }?.let { put("up_mbps", it) }
            downMbps?.takeIf { it > 0 }?.let { put("down_mbps", it) }
            if (serverPorts.isNotEmpty()) {
                put("server_ports", JsonArray(serverPorts.map(::JsonPrimitive)))
            }
            hopInterval?.takeIf(String::isNotBlank)?.let { put("hop_interval", it) }
            certificateSha256?.takeIf(String::isNotBlank)?.let { put("certificate_sha256", it) }
            putTls(tls, allowTcpOnlyTlsFeatures = false)
        },
    )

    fun tuic(
        displayName: String,
        server: String,
        serverPort: Int,
        uuid: String,
        password: String,
        congestionControl: String? = null,
        udpRelayMode: String? = null,
        zeroRttHandshake: Boolean = false,
        heartbeat: String? = null,
        tls: TlsSettings = TlsSettings(enabled = true, serverName = server),
    ): ManagedServer = ManagedServer(
        displayName = displayName,
        identityKey = identity("tuic", server, serverPort, null),
        outbound = buildJsonObject {
            put("type", "tuic")
            put("server", server)
            put("server_port", serverPort)
            put("uuid", uuid)
            put("password", password)
            congestionControl?.takeIf(String::isNotBlank)?.let { put("congestion_control", it) }
            udpRelayMode?.takeIf(String::isNotBlank)?.let { put("udp_relay_mode", it) }
            if (zeroRttHandshake) put("zero_rtt_handshake", true)
            heartbeat?.takeIf(String::isNotBlank)?.let { put("heartbeat", it) }
            putTls(tls, allowTcpOnlyTlsFeatures = false)
        },
    )

    private fun identity(
        type: String,
        server: String,
        port: Int,
        transport: TransportSettings?,
    ): String = listOf(
        type,
        server.lowercase(),
        port.toString(),
        transport?.type.orEmpty(),
        transport?.path.orEmpty(),
        transport?.serviceName.orEmpty(),
        transport?.takeIf { it.type == "xhttp" }?.host.orEmpty(),
        transport?.mode.orEmpty(),
        transport?.xhttpOptions?.toString().orEmpty(),
    ).joinToString("|")

    /**
     * QUIC-протоколы не могут исполнить uTLS и REALITY: рукопожатие идёт внутри
     * quic-go, а не поверх net.Conn. Ядро принимает такой конфиг на проверке, но
     * затем рвёт каждое соединение с "unsupported usage for uTLS", поэтому поля
     * не доходят до QUIC-аутбаундов физически, а не только по договорённости.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putTls(
        settings: TlsSettings,
        allowTcpOnlyTlsFeatures: Boolean = true,
    ) {
        if (!settings.enabled) return
        put(
            "tls",
            buildJsonObject {
                put("enabled", true)
                settings.serverName?.takeIf(String::isNotBlank)?.let { put("server_name", it) }
                if (settings.insecure) put("insecure", true)
                settings.utlsFingerprint
                    ?.takeIf { allowTcpOnlyTlsFeatures && it.isNotBlank() }
                    ?.let { fingerprint ->
                    put(
                        "utls",
                        buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", fingerprint)
                        },
                    )
                }
                settings.realityPublicKey
                    ?.takeIf { allowTcpOnlyTlsFeatures && it.isNotBlank() }
                    ?.let { publicKey ->
                    put(
                        "reality",
                        buildJsonObject {
                            put("enabled", true)
                            put("public_key", publicKey)
                            settings.realityShortId
                                ?.takeIf(String::isNotBlank)
                                ?.let { put("short_id", it) }
                        },
                    )
                }
                if (settings.alpn.isNotEmpty()) {
                    put("alpn", JsonArray(settings.alpn.map(::JsonPrimitive)))
                }
                settings.echConfigPem?.takeIf(String::isNotBlank)?.let { pem ->
                    put(
                        "ech",
                        buildJsonObject {
                            put("enabled", true)
                            put("config", pem)
                        },
                    )
                }
            },
        )
    }

    private fun TransportSettings.toJson(): JsonObject = buildJsonObject {
        put("type", type)
        when (type) {
            "ws", "http", "httpupgrade" -> {
                path?.takeIf(String::isNotBlank)?.let { put("path", it) }
                host?.takeIf(String::isNotBlank)?.let { value ->
                    if (type == "http") {
                        put("host", buildJsonArray { add(JsonPrimitive(value)) })
                    } else {
                        put("headers", buildJsonObject { put("Host", value) })
                    }
                }
            }
            "grpc" -> serviceName?.takeIf(String::isNotBlank)?.let { put("service_name", it) }
            "xhttp" -> {
                put("x_padding_bytes", "100-1000")
                mode?.takeIf(String::isNotBlank)?.let { put("mode", it) }
                path?.takeIf(String::isNotBlank)?.let { put("path", it) }
                host?.takeIf(String::isNotBlank)?.let { put("host", it) }
                xhttpOptions?.forEach { (key, value) -> put(key, value) }
            }
        }
    }

    /**
     * The raw Hysteria2 URI is retained in the outbound for round-tripping, but must never be
     * copied into a profile tag, split-member key, or diagnostic identity. A SHA-256 digest keeps
     * those identities deterministic while distinguishing every transport URI variant (including
     * pin, unknown parameters, percent encoding, and IPv6 spelling). The display-only fragment is
     * excluded, and the two official scheme aliases intentionally share one identity.
     */
    private fun uriFingerprint(uri: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(authoritativeUri(uri).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Raw credential values never enter persistent member identities or diagnostics. */
    internal fun hysteria2CredentialIdentity(password: String): String =
        "hysteria2|credential-sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun authoritativeUri(uri: String): String {
        val text = uri.trim()
        val separator = text.indexOf(':')
        val scheme = text.substring(0, separator.coerceAtLeast(0))
        val remainder = if (separator > 0) text.substring(separator) else text
        val withoutFragment = remainder.substringBefore('#')
        return if (scheme.equals("hy2", ignoreCase = true) ||
            scheme.equals("hysteria2", ignoreCase = true)
        ) {
            "hysteria2:$withoutFragment"
        } else {
            withoutFragment
        }
    }
}

object ManagedProfileFactory {
    data class TaggedServer(val tag: String, val outbound: JsonObject)

    fun single(server: ManagedServer): String = subscription(listOf(server))

    fun subscription(servers: List<ManagedServer>): String {
        require(servers.isNotEmpty()) { "Подписка не содержит серверов." }
        val taggedServers = taggedServers(servers)
        val tags = taggedServers.map(TaggedServer::tag)
        val serverOutbounds = taggedServers.map(TaggedServer::outbound)
        val selector = buildJsonObject {
            put("type", "selector")
            put("tag", ConfigAnalyzer.MANAGED_SELECTOR_TAG)
            put("outbounds", JsonArray(tags.map(::JsonPrimitive)))
            put("default", tags.first())
            put("interrupt_exist_connections", true)
        }
        val direct = buildJsonObject {
            put("type", "direct")
            put("tag", "direct")
        }
        return JsonConfig.format(
            buildJsonObject {
                put(
                    "inbounds",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "tun")
                                put("tag", "tun-in")
                                put(
                                    "address",
                                    buildJsonArray {
                                        add(JsonPrimitive("172.19.0.1/30"))
                                        add(JsonPrimitive("fdfe:dcba:9876::1/126"))
                                    },
                                )
                                put("auto_route", true)
                            },
                        )
                    },
                )
                put("outbounds", JsonArray(serverOutbounds + selector + direct))
                put(
                    "route",
                    buildJsonObject {
                        put("auto_detect_interface", true)
                        put("final", ConfigAnalyzer.MANAGED_SELECTOR_TAG)
                    },
                )
            },
        )
    }

    fun stableTags(servers: List<ManagedServer>): List<String> {
        val bases = servers.map { slug(it.displayName) }
        val counts = bases.groupingBy { it }.eachCount()
        val used = mutableSetOf<String>()
        return servers.mapIndexed { index, server ->
            val base = bases[index]
            val requiresSuffix = counts.getValue(base) > 1 || base in used
            var candidate = if (requiresSuffix) "$base-${shortHash(server.identityKey)}" else base
            var collision = 2
            while (!used.add(candidate)) {
                candidate = "$base-${shortHash("${server.identityKey}|$index|$collision")}"
                collision++
            }
            candidate
        }
    }

    fun taggedServers(servers: List<ManagedServer>): List<TaggedServer> =
        servers.zip(stableTags(servers)).map { (server, tag) ->
            TaggedServer(tag, server.outbound.withTag(tag))
        }

    /**
     * Stable identities for members of a split subscription. Runtime identity and refresh identity
     * are deliberately separate: an imported Hysteria2 URI keeps its exact publication fingerprint
     * for tags/config, while the secret-safe credential identity lets pin, SNI, endpoint and obfs
     * publication changes update the existing logical profile atomically.
     */
    fun stableMemberKeys(servers: List<ManagedServer>): List<String> {
        val occurrences = mutableMapOf<String, Int>()
        return servers.map { server ->
            val identity = server.refreshIdentityKey
            val occurrence = occurrences.getOrDefault(identity, 0).also {
                occurrences[identity] = it + 1
            }
            if (occurrence == 0) {
                fullHash(identity)
            } else {
                fullHash(
                    "$identity|duplicate|" +
                        "${SecretRedactor.redactInline(server.displayName)}|$occurrence",
                )
            }
        }
    }

    /**
     * One-time compatibility alias for split profiles created before Hysteria2 gained a distinct
     * logical refresh identity. The returned value is a double SHA-256 identity and contains no
     * recoverable credential material.
     */
    fun migratedHysteria2MemberKey(rawJson: String): String? = runCatching {
        val root = JsonConfig.parse(rawJson) as? JsonObject ?: return@runCatching null
        val hysteriaOutbounds = (root["outbounds"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.content == "hysteria2" }
        val password = hysteriaOutbounds.singleOrNull()
            ?.get("password")
            ?.let { it as? JsonPrimitive }
            ?.content
            ?.takeIf(String::isNotEmpty)
            ?: return@runCatching null
        fullHash(ProtocolOutboundBuilders.hysteria2CredentialIdentity(password))
    }.getOrNull()

    private fun JsonObject.withTag(tag: String): JsonObject {
        val result = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        this["type"]?.let { result["type"] = it }
        result["tag"] = JsonPrimitive(tag)
        forEach { (key, value) -> if (key != "type" && key != "tag") result[key] = value }
        return JsonObject(result)
    }

    /**
     * Тег виден пользователю в списке серверов, поэтому буквы любого алфавита
     * сохраняются; sing-box принимает произвольные UTF-8 теги.
     */
    private fun slug(name: String): String {
        val value = SecretRedactor.redactInline(name)
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(36)
        return value.ifBlank { "server" }
    }

    private fun shortHash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun fullHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

}
