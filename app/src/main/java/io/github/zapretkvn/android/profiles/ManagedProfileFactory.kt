package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    /** Параметры ссылки, которые ядро не применяет, но которые работе не мешают. */
    val importNotes: List<String> = emptyList(),
)

object ProtocolOutboundBuilders {
    fun vless(
        displayName: String,
        server: String,
        serverPort: Int,
        uuid: String,
        encryption: String = "none",
        uri: String? = null,
        flow: String? = null,
        tls: TlsSettings = TlsSettings(),
        transport: TransportSettings? = null,
    ): ManagedServer = ManagedServer(
        displayName = displayName,
        identityKey = identity("vless", server, serverPort, transport),
        outbound = OutboundSpec.Vless(
            server = server,
            serverPort = serverPort,
            uuid = uuid,
            encryption = encryption,
            uri = uri,
            flow = flow,
            tls = tls,
            transport = transport,
        ).toSingBoxJson(),
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
        outbound = OutboundSpec.Vmess(
            server = server,
            serverPort = serverPort,
            uuid = uuid,
            security = security,
            alterId = alterId,
            tls = tls,
            transport = transport,
        ).toSingBoxJson(),
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
        outbound = OutboundSpec.Trojan(
            server = server,
            serverPort = serverPort,
            password = password,
            tls = tls,
            transport = transport,
        ).toSingBoxJson(),
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
        outbound = OutboundSpec.Shadowsocks(
            server = server,
            serverPort = serverPort,
            method = method,
            password = password,
        ).toSingBoxJson(),
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
        outbound = OutboundSpec.Hysteria2(
            server = server,
            serverPort = serverPort,
            password = password,
            tls = tls,
            obfsPassword = obfsPassword,
            obfsType = obfsType,
            upMbps = upMbps,
            downMbps = downMbps,
            serverPorts = serverPorts,
            hopInterval = hopInterval,
            certificateSha256 = certificateSha256,
            obfsMinPacketSize = obfsMinPacketSize,
            obfsMaxPacketSize = obfsMaxPacketSize,
            uri = uri,
        ).toSingBoxJson(),
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
        outbound = OutboundSpec.Tuic(
            server = server,
            serverPort = serverPort,
            uuid = uuid,
            password = password,
            congestionControl = congestionControl,
            udpRelayMode = udpRelayMode,
            zeroRttHandshake = zeroRttHandshake,
            heartbeat = heartbeat,
            tls = tls,
        ).toSingBoxJson(),
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
        return ProfileSkeleton.render(
            ProfileSkeleton.build(
                outbounds = taggedServers.map(TaggedServer::outbound),
                group = ProfileSkeleton.Group(
                    tag = ConfigAnalyzer.MANAGED_SELECTOR_TAG,
                    members = taggedServers.map(TaggedServer::tag),
                ),
            ),
        )
    }

    /**
     * Тег сервера — его имя из подписки как есть: с флагом, регистром и пробелами
     * («🇱🇻 Hiprig · VLESS WS · TLS»). По тегу экран находит флаг и страну, уведомление
     * показывает сервер, ядро выбирает его в группе.
     *
     * До 0.5.15 тег был слагом (`hiprig-vless-ws-tls`): флаг пропадал, страна угадывалась
     * по адресу входа, и сервер за фронтом в другой стране попадал не в ту группу (16.10.2026).
     * Повтор имени получает « · 2», « · 3» по порядку подписки; выбор при обновлении
     * переносится по старому слагу и по адресу сервера ([ManagedProfileEditor.refreshServers]).
     */
    fun stableTags(servers: List<ManagedServer>): List<String> {
        val used = mutableSetOf<String>()
        return servers.map { server ->
            val base = serverTag(server.displayName)
            var candidate = base
            var occurrence = 2
            while (!used.add(candidate)) candidate = "$base · ${occurrence++}"
            candidate
        }
    }

    /** Имя сервера, пригодное в тег: без секретов, управляющих символов и скобок журнала ядра. */
    fun serverTag(displayName: String): String {
        val redacted = SecretRedactor.redactInline(displayName).replace(SecretRedactor.MASK, " ")
        val cleaned = buildString {
            redacted.codePoints().forEach { point ->
                when {
                    // Ядро пишет «outbound/vless[тег]»: квадратная скобка в теге ломала бы разбор журнала.
                    point == '['.code -> append('(')
                    point == ']'.code -> append(')')
                    Character.isISOControl(point) || point in BIDI_CONTROLS -> append(' ')
                    else -> appendCodePoint(point)
                }
            }
        }.replace(WHITESPACE, " ").trim()
        if (cleaned.none { it.isLetterOrDigit() || it.isSurrogate() }) return FALLBACK_TAG
        return cleaned.takeCodePoints(MAX_TAG_CODE_POINTS).trimEnd()
    }

    private const val FALLBACK_TAG = "Сервер"
    private const val MAX_TAG_CODE_POINTS = 64
    private val WHITESPACE = Regex("\\s+")

    /** Встраивания и переопределения направления текста: ими можно перевернуть имя на экране. */
    private val BIDI_CONTROLS = (0x202A..0x202E) + (0x2066..0x2069)

    private fun String.takeCodePoints(limit: Int): String {
        if (codePointCount(0, length) <= limit) return this
        return substring(0, offsetByCodePoints(0, limit))
    }

    /** Теги до 0.5.15 (слаги): по ним обновление подписки находит прежний выбор. */
    fun legacyStableTags(servers: List<ManagedServer>): List<String> {
        val bases = servers.map { slug(it.displayName) }
        val counts = bases.groupingBy { it }.eachCount()
        val used = mutableSetOf<String>()
        return servers.mapIndexed { index, server ->
            val base = bases[index]
            val requiresSuffix = counts.getValue(base) > 1 || base in used
            val tagIdentity = stableTagIdentity(server)
            var candidate = if (requiresSuffix) "$base-${shortHash(tagIdentity)}" else base
            var collision = 2
            while (!used.add(candidate)) {
                val collisionIdentity = "$tagIdentity|${server.identityKey}|$collision"
                candidate = "$base-${shortHash(collisionIdentity)}"
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
     * are deliberately separate. A unique Hysteria credential survives publication changes; when
     * one credential is shared, endpoint/SNI aliases provide order-independent one-to-one matching.
     * An ambiguous group is rejected or reconciled as explicit remove/add instead of being rebound
     * to whichever member happens to appear first.
     */
    fun stableMemberKeys(servers: List<ManagedServer>): List<String> {
        val occurrences = mutableMapOf<String, Int>()
        val identityCounts = servers.groupingBy(ManagedServer::refreshIdentityKey).eachCount()
        val keys = servers.map { server ->
            val identity = server.refreshIdentityKey
            if (isHysteriaCredentialIdentity(identity) && identityCounts.getValue(identity) > 1) {
                return@map hysteria2EndpointMemberKey(server)
                    ?: fullHash("$identity|exact|${server.identityKey}")
            }
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
        require(keys.size == keys.toSet().size) {
            "Hysteria2 subscription contains indistinguishable duplicate servers."
        }
        return keys
    }

    internal data class Hysteria2ReconciliationAliases(
        val credentialKey: String,
        val aliases: Set<String>,
    )

    internal fun hysteria2ReconciliationAliases(
        server: ManagedServer,
    ): Hysteria2ReconciliationAliases? {
        if (server.outbound.string("type") != "hysteria2") return null
        val password = server.outbound.string("password")?.takeIf(String::isNotEmpty) ?: return null
        return hysteria2Aliases(
            credentialIdentity = ProtocolOutboundBuilders.hysteria2CredentialIdentity(password),
            server = server.outbound.string("server").orEmpty(),
            port = server.outbound.int("server_port"),
            sni = server.outbound.objectValue("tls")?.string("server_name").orEmpty(),
        )
    }

    internal fun migratedHysteria2MemberAliases(
        rawJson: String,
    ): Hysteria2ReconciliationAliases? = runCatching {
        val root = JsonConfig.parse(rawJson) as? JsonObject ?: return@runCatching null
        val outbound = (root["outbounds"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .singleOrNull { it.string("type") == "hysteria2" }
            ?: return@runCatching null
        val password = outbound.string("password")?.takeIf(String::isNotEmpty)
            ?: return@runCatching null
        hysteria2Aliases(
            credentialIdentity = ProtocolOutboundBuilders.hysteria2CredentialIdentity(password),
            server = outbound.string("server").orEmpty(),
            port = outbound.int("server_port"),
            sni = outbound.objectValue("tls")?.string("server_name").orEmpty(),
        )
    }.getOrNull()

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

    private fun hysteria2Aliases(
        credentialIdentity: String,
        server: String,
        port: Int,
        sni: String,
    ): Hysteria2ReconciliationAliases {
        val credentialKey = fullHash(credentialIdentity)
        val endpoint = "${server.trim().lowercase()}|$port|${sni.trim().lowercase()}"
        val aliases = buildSet {
            add(fullHash("$credentialIdentity|endpoint|$endpoint"))
            if (sni.isNotBlank()) {
                add(fullHash("$credentialIdentity|sni|${sni.trim().lowercase()}"))
            }
        }
        return Hysteria2ReconciliationAliases(credentialKey, aliases)
    }

    private fun hysteria2EndpointMemberKey(server: ManagedServer): String? =
        hysteria2ReconciliationAliases(server)?.aliases?.firstOrNull()

    private fun stableTagIdentity(server: ManagedServer): String =
        hysteria2EndpointMemberKey(server) ?: server.identityKey

    private fun isHysteriaCredentialIdentity(value: String): Boolean =
        value.startsWith("hysteria2|credential-sha256:")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

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
