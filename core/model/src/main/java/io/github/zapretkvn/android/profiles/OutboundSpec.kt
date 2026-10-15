package io.github.zapretkvn.android.profiles

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

/**
 * Одна точка сборки sing-box outbound/endpoint по протоколу. Разборщики ссылок и
 * WireGuard-импорт только заполняют поля; JSON из них получается единственным
 * [toSingBoxJson]. Тег не входит в outbound протоколов-ссылок: его назначает профиль
 * (см. ManagedProfileFactory), поэтому у WireGuard он единственный явный.
 */
sealed interface OutboundSpec {
    /** Раздел профиля, в который попадает результат [toSingBoxJson]. */
    val section: Section

    fun toSingBoxJson(): JsonObject

    enum class Section(val key: String) { Outbounds("outbounds"), Endpoints("endpoints") }

    data class Vless(
        val server: String,
        val serverPort: Int,
        val uuid: String,
        val encryption: String = "none",
        val uri: String? = null,
        val flow: String? = null,
        val tls: TlsSettings = TlsSettings(),
        val transport: TransportSettings? = null,
    ) : OutboundSpec {
        override val section get() = Section.Outbounds

        override fun toSingBoxJson(): JsonObject = buildJsonObject {
            put("type", "vless")
            uri?.let { put("uri", it) }
            put("server", server)
            put("server_port", serverPort)
            put("uuid", uuid)
            put("encryption", encryption)
            flow?.takeIf(String::isNotBlank)?.let { put("flow", it) }
            putTls(tls)
            putTransport(transport)
        }
    }

    data class Vmess(
        val server: String,
        val serverPort: Int,
        val uuid: String,
        val security: String = "auto",
        val alterId: Int = 0,
        val tls: TlsSettings = TlsSettings(),
        val transport: TransportSettings? = null,
    ) : OutboundSpec {
        override val section get() = Section.Outbounds

        override fun toSingBoxJson(): JsonObject = buildJsonObject {
            put("type", "vmess")
            put("server", server)
            put("server_port", serverPort)
            put("uuid", uuid)
            put("security", security)
            if (alterId != 0) put("alter_id", alterId)
            putTls(tls)
            putTransport(transport)
        }
    }

    data class Trojan(
        val server: String,
        val serverPort: Int,
        val password: String,
        val tls: TlsSettings = TlsSettings(enabled = true, serverName = server),
        val transport: TransportSettings? = null,
    ) : OutboundSpec {
        override val section get() = Section.Outbounds

        override fun toSingBoxJson(): JsonObject = buildJsonObject {
            put("type", "trojan")
            put("server", server)
            put("server_port", serverPort)
            put("password", password)
            putTls(tls)
            putTransport(transport)
        }
    }

    data class Shadowsocks(
        val server: String,
        val serverPort: Int,
        val method: String,
        val password: String,
    ) : OutboundSpec {
        override val section get() = Section.Outbounds

        override fun toSingBoxJson(): JsonObject = buildJsonObject {
            put("type", "shadowsocks")
            put("server", server)
            put("server_port", serverPort)
            put("method", method)
            put("password", password)
        }
    }

    data class Hysteria2(
        val server: String,
        val serverPort: Int,
        val password: String,
        val tls: TlsSettings = TlsSettings(enabled = true, serverName = server),
        val obfsPassword: String? = null,
        val obfsType: String? = null,
        val upMbps: Int? = null,
        val downMbps: Int? = null,
        val serverPorts: List<String> = emptyList(),
        val hopInterval: String? = null,
        val certificateSha256: String? = null,
        val obfsMinPacketSize: Int? = null,
        val obfsMaxPacketSize: Int? = null,
        val uri: String? = null,
    ) : OutboundSpec {
        override val section get() = Section.Outbounds

        override fun toSingBoxJson(): JsonObject = buildJsonObject {
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
        }
    }

    data class Tuic(
        val server: String,
        val serverPort: Int,
        val uuid: String,
        val password: String,
        val congestionControl: String? = null,
        val udpRelayMode: String? = null,
        val zeroRttHandshake: Boolean = false,
        val heartbeat: String? = null,
        val tls: TlsSettings = TlsSettings(enabled = true, serverName = server),
    ) : OutboundSpec {
        override val section get() = Section.Outbounds

        override fun toSingBoxJson(): JsonObject = buildJsonObject {
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
        }
    }

    /** WireGuard и AmneziaWG: в sing-box это endpoint, а не outbound. */
    data class WireGuard(
        val tag: String,
        val mtu: Int,
        val addresses: List<String>,
        val privateKey: String,
        val listenPort: Int?,
        val peers: List<Peer>,
        val amnezia: Map<String, JsonElement>?,
    ) : OutboundSpec {
        data class Peer(
            val address: String?,
            val port: Int?,
            val publicKey: String,
            val preSharedKey: String?,
            val allowedIPs: List<String>,
            val persistentKeepalive: JsonPrimitive?,
        ) {
            fun toSingBoxJson(): JsonObject = buildJsonObject {
                address?.let { put("address", it) }
                port?.let { put("port", it) }
                put("public_key", publicKey)
                preSharedKey?.let { put("pre_shared_key", it) }
                put("allowed_ips", JsonArray(allowedIPs.map(::JsonPrimitive)))
                persistentKeepalive?.let { put("persistent_keepalive_interval", it) }
            }
        }

        override val section get() = Section.Endpoints

        override fun toSingBoxJson(): JsonObject = buildJsonObject {
            put("type", "wireguard")
            put("tag", tag)
            put("mtu", mtu)
            put("address", JsonArray(addresses.map(::JsonPrimitive)))
            put("private_key", privateKey)
            listenPort?.let { put("listen_port", it) }
            put("peers", JsonArray(peers.map(Peer::toSingBoxJson)))
            amnezia?.let { put("amnezia", JsonObject(it)) }
        }
    }
}

/**
 * QUIC-протоколы не могут исполнить uTLS и REALITY: рукопожатие идёт внутри
 * quic-go, а не поверх net.Conn. Ядро принимает такой конфиг на проверке, но
 * затем рвёт каждое соединение с "unsupported usage for uTLS", поэтому поля
 * не доходят до QUIC-аутбаундов физически, а не только по договорённости.
 */
private fun JsonObjectBuilder.putTls(
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

private fun JsonObjectBuilder.putTransport(transport: TransportSettings?) {
    transport?.let { put("transport", it.toJson()) }
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
