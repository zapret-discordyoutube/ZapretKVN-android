package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Единственное место, где собирается каркас sing-box профиля: tun-inbound, группа-selector,
 * direct, раздел route (в том числе hijack-dns) и dns-секция. Профиль ссылок/подписок
 * (ManagedProfileFactory) и профиль WireGuard/AmneziaWG (wireguard-import) отличаются только
 * аргументами [build]: набором серверов, группой, правилами маршрута и dns.
 */
object ProfileSkeleton {
    const val TUN_INBOUND_TAG = "tun-in"
    const val DIRECT_TAG = "direct"
    private val TUN_ADDRESSES = listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")

    /** Группа-selector, через которую идёт трафик профиля; первый участник выбран по умолчанию. */
    data class Group(val tag: String, val members: List<String>) {
        init {
            require(members.isNotEmpty()) { "Группа без участников." }
        }
    }

    /**
     * @param outbounds готовые outbound'ы с тегами (результат [OutboundSpec.toSingBoxJson] плюс тег)
     * @param endpoints готовые endpoint'ы (WireGuard), идут отдельным разделом
     * @param group selector поверх [outbounds]; без него итоговый маршрут — [final]
     * @param dns готовая dns-секция (см. [dns]) или null
     * @param hijackDns добавить правило `port 53 → hijack-dns` первым в route.rules
     * @param routeRules остальные правила route после hijack-dns
     * @param defaultDomainResolver `route.default_domain_resolver`
     * @param final `route.final`; по умолчанию тег [group], иначе [DIRECT_TAG]
     */
    fun build(
        outbounds: List<JsonObject> = emptyList(),
        endpoints: List<JsonObject> = emptyList(),
        group: Group? = null,
        dns: JsonObject? = null,
        hijackDns: Boolean = false,
        routeRules: List<JsonObject> = emptyList(),
        defaultDomainResolver: String? = null,
        final: String? = null,
    ): JsonObject = buildJsonObject {
        dns?.let { put("dns", it) }
        if (endpoints.isNotEmpty()) put("endpoints", JsonArray(endpoints))
        put("inbounds", buildJsonArray { add(tunInbound()) })
        put("outbounds", JsonArray(outbounds + listOfNotNull(group?.let(::selector)) + directOutbound()))
        put(
            "route",
            buildJsonObject {
                put("auto_detect_interface", true)
                defaultDomainResolver?.let { put("default_domain_resolver", it) }
                val rules = listOfNotNull(if (hijackDns) hijackDnsRule() else null) + routeRules
                if (rules.isNotEmpty()) put("rules", JsonArray(rules))
                put("final", final ?: group?.tag ?: DIRECT_TAG)
            },
        )
    }

    /** Текст профиля в формате, в котором профили хранятся и отдаются в разбор. */
    fun render(profile: JsonObject): String = JsonConfig.format(profile)

    fun tunInbound(): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", TUN_INBOUND_TAG)
        put("address", JsonArray(TUN_ADDRESSES.map(::JsonPrimitive)))
        put("auto_route", true)
    }

    fun directOutbound(): JsonObject = buildJsonObject {
        put("type", "direct")
        put("tag", DIRECT_TAG)
    }

    fun selector(group: Group): JsonObject = buildJsonObject {
        put("type", "selector")
        put("tag", group.tag)
        put("outbounds", JsonArray(group.members.map(::JsonPrimitive)))
        put("default", group.members.first())
        put("interrupt_exist_connections", true)
    }

    fun hijackDnsRule(): JsonObject = buildJsonObject {
        put("port", 53)
        put("action", "hijack-dns")
    }

    fun routeByCidr(cidrs: List<String>, outbound: String): JsonObject = buildJsonObject {
        put("ip_cidr", JsonArray(cidrs.map(::JsonPrimitive)))
        put("action", "route")
        put("outbound", outbound)
    }

    fun dnsLocalServer(tag: String): JsonObject = buildJsonObject {
        put("type", "local")
        put("tag", tag)
    }

    fun dnsUdpServer(tag: String, server: String, detour: String? = null): JsonObject = buildJsonObject {
        put("type", "udp")
        put("tag", tag)
        put("server", server)
        detour?.let { put("detour", it) }
    }

    fun dns(servers: List<JsonObject>, final: String): JsonObject = buildJsonObject {
        put("servers", JsonArray(servers))
        put("final", final)
    }
}
