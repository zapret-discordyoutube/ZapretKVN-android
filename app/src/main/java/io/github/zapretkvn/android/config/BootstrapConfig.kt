package io.github.zapretkvn.android.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class ProxyBootstrapTarget(
    val outboundTag: String,
    val outboundType: String,
    val hostname: String,
    val port: Int,
    val requiresDns: Boolean,
    val tcpPreflightSupported: Boolean,
    val staleAddressAllowed: Boolean,
)

data class BootstrapHostOverlay(
    val outboundTag: String,
    val hostname: String,
    val addresses: List<String>,
)

enum class ProxyIpFamily {
    Unspecified,
    Ipv4Only,
    Ipv6Only,
    DualStack,
}

object BootstrapConfig {
    fun selectedProxyTag(raw: String): String? = selectedProxyTag(JsonConfig.parse(raw) as? JsonObject ?: return null)

    /**
     * Returns an exact address-family capability only for the selected userspace
     * WireGuard endpoint. Other outbound types keep their resolver-driven behavior.
     */
    fun selectedProxyIpFamily(raw: String): ProxyIpFamily {
        val root = JsonConfig.parse(raw) as? JsonObject ?: return ProxyIpFamily.Unspecified
        val target = target(raw) ?: return ProxyIpFamily.Unspecified
        if (target.outboundType != "wireguard") return ProxyIpFamily.Unspecified
        val endpoint = (root["endpoints"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("tag") == target.outboundTag }
            ?: return ProxyIpFamily.Unspecified
        val addresses = stringArray(endpoint["address"]) +
            stringArray(endpoint["inet4_address"]) +
            stringArray(endpoint["inet6_address"])
        val hasIpv4 = addresses.any { value -> '.' in value.substringBefore('/') }
        val hasIpv6 = addresses.any { value -> ':' in value.substringBefore('/') }
        return when {
            hasIpv4 && hasIpv6 -> ProxyIpFamily.DualStack
            hasIpv4 -> ProxyIpFamily.Ipv4Only
            hasIpv6 -> ProxyIpFamily.Ipv6Only
            else -> ProxyIpFamily.Unspecified
        }
    }

    /**
     * Доменные имена всех VPN-серверов конфигурации: outbound-ы и пиры
     * WireGuard-endpoint-ов. Литеральные адреса и служебные outbound-ы без
     * `server` не входят. По этому набору локальный DNS-транспорт отличает
     * имена серверов группы от обычных прямых доменов.
     */
    fun serverHostnames(raw: String): Set<String> {
        // Вызывается перед запуском ядра: некорректный JSON не должен срывать
        // сессию — без набора имён транспорт просто работает по-прежнему.
        val root = runCatching { JsonConfig.parse(raw) as? JsonObject }.getOrNull() ?: return emptySet()
        val names = LinkedHashSet<String>()
        fun add(value: String?) {
            val host = value?.trim()?.trimEnd('.')?.lowercase()?.takeIf(String::isNotEmpty) ?: return
            if (!looksNumeric(host)) names += host
        }
        (root["outbounds"] as? JsonArray)?.forEach { add((it as? JsonObject)?.string("server")) }
        (root["endpoints"] as? JsonArray)?.forEach { endpoint ->
            ((endpoint as? JsonObject)?.get("peers") as? JsonArray)?.forEach { add((it as? JsonObject)?.string("address")) }
        }
        return names
    }

    fun target(raw: String): ProxyBootstrapTarget? {
        val root = JsonConfig.parse(raw) as? JsonObject ?: return null
        val outbounds = (root["outbounds"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val byTag = outbounds.mapNotNull { item -> item.string("tag")?.let { it to item } }.toMap()
        val endpoints = (root["endpoints"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val endpointsByTag = endpoints.mapNotNull { item -> item.string("tag")?.let { it to item } }.toMap()
        var tag = selectedProxyTag(root) ?: return null
        val visited = mutableSetOf<String>()
        while (visited.add(tag)) {
            val endpoint = endpointsByTag[tag]
            if (endpoint != null) return endpointTarget(tag, endpoint)
            val outbound = byTag[tag] ?: return null
            val type = outbound.string("type") ?: return null
            if (type == "selector" || type == "urltest") {
                val members = (outbound["outbounds"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                tag = outbound.string("default")?.takeIf(members::contains) ?: members.firstOrNull() ?: return null
                continue
            }
            val hostname = outbound.string("server")?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val requiresDns = !looksNumeric(hostname)
            val port = (outbound["server_port"] as? JsonPrimitive)?.intOrNull ?: return null
            if (port !in 1..65535) return null
            val tls = outbound["tls"] as? JsonObject
            val authenticatedTls = tls?.let {
                it.boolean("enabled") == true && it.boolean("insecure") != true
            } == true
            return ProxyBootstrapTarget(
                outboundTag = tag,
                outboundType = type,
                hostname = hostname,
                port = port,
                requiresDns = requiresDns,
                tcpPreflightSupported = type !in UDP_TRANSPORTS,
                staleAddressAllowed = requiresDns && authenticatedTls,
            )
        }
        return null
    }

    private fun selectedProxyTag(root: JsonObject): String? {
        val outbounds = (root["outbounds"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val endpoints = (root["endpoints"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        if (outbounds.any { it.string("tag") == ConfigAnalyzer.MANAGED_SELECTOR_TAG }) {
            return ConfigAnalyzer.MANAGED_SELECTOR_TAG
        }
        val final = (root["route"] as? JsonObject)?.string("final") ?: return null
        val finalType = (outbounds + endpoints).firstOrNull { it.string("tag") == final }?.string("type")
        if (finalType !in NON_PROXY_TYPES) return final

        val endpointTags = endpoints.mapNotNull { it.string("tag") }.toSet()
        val routedEndpointTags = (((root["route"] as? JsonObject)?.get("rules") as? JsonArray).orEmpty())
            .mapNotNull { it as? JsonObject }
            .filter { it.string("action") == "route" && it.string("outbound") in endpointTags }
            .filter { rule ->
                val prefixes = stringArray(rule["ip_cidr"])
                "0.0.0.0/0" in prefixes || "::/0" in prefixes
            }
            .mapNotNull { it.string("outbound") }
            .distinct()
        return routedEndpointTags.singleOrNull()
    }

    private fun endpointTarget(tag: String, endpoint: JsonObject): ProxyBootstrapTarget? {
        val type = endpoint.string("type") ?: return null
        if (type != "wireguard") return null
        val peer = (endpoint["peers"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("address")?.isNotBlank() == true }
            ?: return null
        val hostname = peer.string("address")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val port = (peer["port"] as? JsonPrimitive)?.intOrNull ?: return null
        if (port !in 1..65535) return null
        return ProxyBootstrapTarget(
            outboundTag = tag,
            outboundType = type,
            hostname = hostname,
            port = port,
            requiresDns = !looksNumeric(hostname),
            tcpPreflightSupported = false,
            staleAddressAllowed = false,
        )
    }

    private fun stringArray(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun looksNumeric(value: String): Boolean = ':' in value || IPV4.matches(value)

    private val IPV4 = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
    private val UDP_TRANSPORTS = setOf("hysteria", "hysteria2", "tuic", "wireguard")
    private val NON_PROXY_TYPES = setOf("direct", "block", "dns", null)
}
