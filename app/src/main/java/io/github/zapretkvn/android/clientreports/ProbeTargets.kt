package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.config.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Сервер профиля по тегу как цель [BlockProbe]: адрес, порт, имя TLS, есть ли TLS, UDP ли он. */
internal object ProbeTargets {
    val UDP_TYPES = setOf("hysteria2", "hysteria", "tuic", "wireguard", "awg", "amneziawg")
    private val QUIC_TYPES = setOf("hysteria2", "hysteria", "tuic")

    fun of(config: ParsedConfig, tag: String): ProbeTarget? {
        val root = config.root ?: return null
        (root["outbounds"] as? JsonArray)?.firstTagged(tag)?.let { outbound ->
            val type = outbound.string("type")?.lowercase() ?: return null
            val host = outbound.string("server")?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val port = (outbound["server_port"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 } ?: return null
            val tls = outbound["tls"] as? JsonObject
            val udp = type in UDP_TYPES
            val tlsOn = (tls?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true
            val reality = ((tls?.get("reality") as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true
            return ProbeTarget(
                host = host,
                port = port,
                serverName = tls?.string("server_name")?.trim()?.takeIf(String::isNotEmpty),
                // Hysteria и TUIC тоже с TLS, но поверх QUIC: TCP-ступени TLS для них нет.
                usesTls = !udp && tlsOn,
                udp = udp,
                protocol = type,
                transport = (outbound["transport"] as? JsonObject)?.string("type")?.lowercase()
                    ?: if (type in QUIC_TYPES) "quic" else if (udp) "udp" else "tcp",
                security = when {
                    reality -> "reality"
                    tlsOn || type in QUIC_TYPES -> "tls"
                    else -> "none"
                },
            )
        }
        val endpoint = (root["endpoints"] as? JsonArray)?.firstTagged(tag) ?: return null
        val peer = (endpoint["peers"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val host = peer.string("address")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val port = (peer["port"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 } ?: return null
        val type = endpoint.string("type")?.lowercase().orEmpty()
        return ProbeTarget(host = host, port = port, serverName = null, usesTls = false, udp = true, protocol = type, transport = "udp", security = "none")
    }

    private fun JsonArray.firstTagged(tag: String): JsonObject? =
        firstOrNull { (it as? JsonObject)?.string("tag") == tag } as? JsonObject
}
