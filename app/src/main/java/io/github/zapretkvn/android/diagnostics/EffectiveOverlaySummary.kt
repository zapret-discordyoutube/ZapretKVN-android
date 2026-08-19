package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A structural view of the effective runtime overlay. It never copies endpoints or match values. */
object EffectiveOverlaySummary {
    fun create(runtimeJson: String, dnsMode: DnsMode): String {
        val root = JsonConfig.parse(runtimeJson) as? JsonObject ?: error("Runtime JSON is not an object.")
        val dns = root["dns"] as? JsonObject
        val route = root["route"] as? JsonObject
        val inbounds = (root["inbounds"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val tun = inbounds.singleOrNull { it.string("type") == "tun" }
        val experimental = root["experimental"] as? JsonObject
        val clashApi = experimental?.get("clash_api") as? JsonObject
        val localControlEndpointPresent =
            clashApi?.keys?.any(CLASH_LISTENER_FIELDS::contains) == true ||
                experimental?.get("v2ray_api") is JsonObject

        val allDnsServers = (dns?.get("servers") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val managedDnsServers = allDnsServers.filter {
            it.string("tag")?.startsWith(MANAGED_PREFIX) == true
        }
        val managedDnsRules = (dns?.get("rules") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .filter(::isManagedDnsRule)
        val managedRouteRules = (route?.get("rules") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .filter { isManagedRouteRule(it) || isHealthProbeSniff(it) }
        val healthProbeTlsSniffCount = (route?.get("rules") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .count(::isHealthProbeSniff)
        val healthProbeRouteCount = (route?.get("rules") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .count(::isHealthProbeRoute)
        val managedRuleSets = (route?.get("rule_set") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .filter { it.string("tag")?.startsWith(MANAGED_PREFIX) == true }
        val bootstrap = managedDnsServers.firstOrNull { it.string("tag") == BOOTSTRAP_TAG }
        val bootstrapAddresses = ((bootstrap?.get("predefined") as? JsonObject)
            ?.values
            ?.firstOrNull() as? JsonArray)
            ?.size ?: 0
        val proxyDetourTags = managedDnsServers.mapNotNull { it.string("detour") }.toSet()
        val proxyTransportTypes = listOf("outbounds", "endpoints")
            .flatMap { key ->
                (root[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            }
            .filter { it.string("tag") in proxyDetourTags }
            .mapNotNull { it.string("type") }
            .map(::safeToken)
            .distinct()
        val wireGuardEndpoints = (root["endpoints"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .filter { it.string("type") == "wireguard" }
        val wireGuardPeers = wireGuardEndpoints.flatMap { endpoint ->
            (endpoint["peers"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        }
        val wireGuardAllowedPrefixes = wireGuardPeers.flatMap { peer ->
            (peer["allowed_ips"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        }

        return JsonConfig.format(
            buildJsonObject {
                put("dns_mode", dnsMode.name)
                put(
                    "proxy_ipv4_only",
                    managedDnsRules.any { it.string("strategy") == "ipv4_only" },
                )
                put(
                    "tun",
                    buildJsonObject {
                        put("present", tun != null)
                        put("auto_route", tun?.boolean("auto_route") == true)
                        put("ipv4", tun.hasAddressFamily(ipv6 = false))
                        put("ipv6", tun.hasAddressFamily(ipv6 = true))
                    },
                )
                put(
                    "vpn_hiding",
                    buildJsonObject {
                        put("non_tun_inbound_count", inbounds.count { it.string("type") != "tun" })
                        put("local_control_endpoint_present", localControlEndpointPresent)
                        put("tun_mtu", tun?.string("mtu")?.toIntOrNull() ?: 0)
                    },
                )
                put(
                    "dns_servers",
                    buildJsonArray {
                        managedDnsServers.forEach { server ->
                            add(
                                buildJsonObject {
                                    put("tag", safeManagedTag(server.string("tag")))
                                    put("type", safeToken(server.string("type")))
                                    put("uses_proxy_detour", server.string("detour") != null)
                                },
                            )
                        }
                    },
                )
                put("dns_total_server_count", allDnsServers.size)
                put("dns_profile_server_count", allDnsServers.size - managedDnsServers.size)
                put(
                    "dns_android_fallback_active",
                    dnsMode == DnsMode.FromJson && dns?.string("final") == ANDROID_DNS_TAG,
                )
                put("dns_rule_count", managedDnsRules.size)
                put(
                    "proxy_transport_types",
                    JsonArray(proxyTransportTypes.map(::JsonPrimitive)),
                )
                put("wireguard_endpoint_count", wireGuardEndpoints.size)
                put(
                    "wireguard_android_engine_count",
                    wireGuardEndpoints.count { it.boolean("system") != true },
                )
                put(
                    "wireguard_mtu_values",
                    JsonArray(
                        wireGuardEndpoints.mapNotNull { endpoint ->
                            endpoint.string("mtu")?.toIntOrNull()
                        }.distinct().map(::JsonPrimitive),
                    ),
                )
                put("wireguard_peer_count", wireGuardPeers.size)
                put(
                    "wireguard_client_bind_detour_count",
                    wireGuardEndpoints.count {
                        it.string("detour")?.startsWith(WIREGUARD_DIRECT_PREFIX) == true
                    },
                )
                put(
                    "wireguard_custom_detour_count",
                    wireGuardEndpoints.count {
                        val detour = it.string("detour")
                        detour != null && !detour.startsWith(WIREGUARD_DIRECT_PREFIX)
                    },
                )
                put(
                    "wireguard_system_endpoint_count",
                    wireGuardEndpoints.count { it.boolean("system") == true },
                )
                put("wireguard_allowed_ipv4_default", "0.0.0.0/0" in wireGuardAllowedPrefixes)
                put("wireguard_allowed_ipv6_default", "::/0" in wireGuardAllowedPrefixes)
                put(
                    "wireguard_local_ipv4_count",
                    wireGuardEndpoints.sumOf { it.addressFamilyCount(ipv6 = false) },
                )
                put(
                    "wireguard_local_ipv6_count",
                    wireGuardEndpoints.sumOf { it.addressFamilyCount(ipv6 = true) },
                )
                put("route_rule_count", managedRouteRules.size)
                put("health_probe_route_count", healthProbeRouteCount)
                put("health_probe_tls_sniff_count", healthProbeTlsSniffCount)
                put(
                    "route_actions",
                    JsonArray(
                        managedRouteRules.mapNotNull { it.string("action") }
                            .map(::safeToken)
                            .distinct()
                            .map(::JsonPrimitive),
                    ),
                )
                put(
                    "rule_sets",
                    buildJsonArray {
                        managedRuleSets.forEach { ruleSet ->
                            add(
                                buildJsonObject {
                                    put("tag", safeManagedTag(ruleSet.string("tag")))
                                    put("type", safeToken(ruleSet.string("type")))
                                    ruleSet.string("format")?.let { put("format", safeToken(it)) }
                                },
                            )
                        }
                    },
                )
                put("bootstrap_hosts_overlay", bootstrap != null)
                put("bootstrap_address_count", bootstrapAddresses)
            },
        )
    }

    private fun isManagedDnsRule(rule: JsonObject): Boolean =
        rule.string("server")?.startsWith(MANAGED_PREFIX) == true ||
            rule.string("action") == "reject" && rule.keys.any(::managedKey)

    private fun isManagedRouteRule(rule: JsonObject): Boolean =
        rule.string("action") == "hijack-dns" ||
            rule.string("outbound")?.startsWith(MANAGED_PREFIX) == true ||
            rule.values.any(::containsManagedTag)

    private fun isHealthProbeRoute(rule: JsonObject): Boolean =
        rule.string("action") == "route" &&
            (rule["domain"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.containsAll(HEALTH_PROBE_HOSTS) == true

    private fun isHealthProbeSniff(rule: JsonObject): Boolean =
        rule.string("action") == "sniff" &&
            rule.string("network") == "tcp" &&
            (rule["port"] as? JsonPrimitive)?.contentOrNull == "443" &&
            (rule["package_name"] as? JsonArray)?.isNotEmpty() == true &&
            (rule["sniffer"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.contains("tls") == true

    private fun containsManagedTag(element: JsonElement): Boolean = when (element) {
        is JsonPrimitive -> element.contentOrNull?.startsWith(MANAGED_PREFIX) == true
        is JsonArray -> element.any(::containsManagedTag)
        is JsonObject -> element.values.any(::containsManagedTag)
    }

    private fun managedKey(value: String): Boolean = value.startsWith(MANAGED_PREFIX)

    private fun JsonObject?.hasAddressFamily(ipv6: Boolean): Boolean =
        ((this?.get("address") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }).orEmpty()
            .any { address -> (':' in address) == ipv6 }

    private fun JsonObject.addressFamilyCount(ipv6: Boolean): Int =
        ((this["address"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }).orEmpty()
            .count { address -> (':' in address) == ipv6 }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun safeManagedTag(value: String?): String =
        value?.takeIf { it.startsWith(MANAGED_PREFIX) }?.take(80) ?: "zapret-unknown"

    private fun safeToken(value: String?): String =
        value?.takeIf { TOKEN.matches(it) }?.take(40) ?: "unknown"

    private val CLASH_LISTENER_FIELDS = setOf(
        "external_controller",
        "external_controller_tls",
        "external_ui",
        "external_ui_download_url",
    )

    private val TOKEN = Regex("[a-zA-Z0-9_-]+")
    private const val MANAGED_PREFIX = "zapret-"
    private const val BOOTSTRAP_TAG = "zapret-bootstrap-lkg"
    private const val ANDROID_DNS_TAG = "zapret-android-dns"
    private const val WIREGUARD_DIRECT_PREFIX = "zapret-wireguard-direct"
    private val HEALTH_PROBE_HOSTS = setOf(
        "cp.cloudflare.com",
        "connectivitycheck.gstatic.com",
        "dns.opendns.com",
    )
}
