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

    fun of(config: ParsedConfig, tag: String): ProbeTarget? {
        val root = config.root ?: return null
        (root["outbounds"] as? JsonArray)?.firstTagged(tag)?.let { outbound ->
            val type = outbound.string("type")?.lowercase() ?: return null
            val host = outbound.string("server")?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val port = (outbound["server_port"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 } ?: return null
            val tls = outbound["tls"] as? JsonObject
            val udp = type in UDP_TYPES
            return ProbeTarget(
                host = host,
                port = port,
                serverName = tls?.string("server_name")?.trim()?.takeIf(String::isNotEmpty),
                // Hysteria и TUIC тоже с TLS, но поверх QUIC: TCP-ступени TLS для них нет.
                usesTls = !udp && (tls?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true,
                udp = udp,
            )
        }
        val endpoint = (root["endpoints"] as? JsonArray)?.firstTagged(tag) ?: return null
        val peer = (endpoint["peers"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val host = peer.string("address")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val port = (peer["port"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 } ?: return null
        return ProbeTarget(host = host, port = port, serverName = null, usesTls = false, udp = true)
    }

    private fun JsonArray.firstTagged(tag: String): JsonObject? =
        firstOrNull { (it as? JsonObject)?.string("tag") == tag } as? JsonObject
}
