package io.github.zapretkvn.android.config

import io.github.zapretkvn.android.hardening.RuntimeHardeningResult
import io.github.zapretkvn.android.hardening.VpnHidingOptions
import io.github.zapretkvn.android.hardening.VpnRuntimeHardening
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

enum class DnsMode {
    Automatic,
    Android,
    Secure,
    FromJson,
}

internal data class ManagedHealthEndpoint(
    val code: String,
    val host: String,
    val url: String,
)

/** One HTTPS request is made on connect; later endpoints are used only after real failures. */
internal object ManagedHealthProbe {
    val endpoints = listOf(
        ManagedHealthEndpoint(
            code = "cloudflare",
            host = "cp.cloudflare.com",
            url = "https://cp.cloudflare.com/generate_204",
        ),
        ManagedHealthEndpoint(
            code = "google",
            host = "connectivitycheck.gstatic.com",
            url = "https://connectivitycheck.gstatic.com/generate_204",
        ),
        ManagedHealthEndpoint(
            code = "opendns",
            host = "dns.opendns.com",
            url = "https://dns.opendns.com/dns-query",
        ),
    )
    val hosts = endpoints.map(ManagedHealthEndpoint::host)
}

data class RuntimeConfigOptions(
    val dnsMode: DnsMode = DnsMode.FromJson,
    val proxyIpv4Only: Boolean = true,
    val dnsOverride: DnsOverride = DnsOverride(),
    val bootstrapHost: BootstrapHostOverlay? = null,
    val vpnHiding: VpnHidingOptions = VpnHidingOptions(),
    val healthCheckPackageName: String? = null,
    val updaterPackageName: String? = null,
    val blockedPackages: Set<String> = emptySet(),
)

sealed interface RuntimeConfigResult {
    data class Ready(val json: String) : RuntimeConfigResult
    data class Invalid(val message: String) : RuntimeConfigResult
}

/**
 * Produces an in-memory service configuration. The stored profile is never rewritten here.
 * Android owns the per-app boundary; sing-box only routes traffic that Android admitted to TUN.
 */
object RuntimeConfigBuilder {
    fun build(
        raw: String,
        enableTrafficStats: Boolean = false,
        options: RuntimeConfigOptions = RuntimeConfigOptions(),
    ): RuntimeConfigResult {
        val storedRoot = try {
            JsonConfig.parse(raw) as? JsonObject
                ?: return invalid("Корень конфигурации должен быть JSON-объектом.")
        } catch (_: Exception) {
            return invalid("JSON содержит синтаксическую ошибку.")
        }
        // Materialize Android endpoint defaults before hardening so the outer
        // TUN can be clamped to the effective userspace WireGuard MTU.
        val compatibleRoot = applyAndroidWireGuardCompatibility(storedRoot)
        val hardenedRoot = when (val hardened = VpnRuntimeHardening.apply(compatibleRoot, options.vpnHiding)) {
            is RuntimeHardeningResult.Ready -> hardened.root
            is RuntimeHardeningResult.Blocked -> return invalid(hardened.message)
        }
        val root = hardenedRoot

        findForbiddenField(root)?.let { field ->
            return invalid("Поле '$field' несовместимо с Android VPN и запрещено в MVP.")
        }

        val inbounds = root["inbounds"] as? JsonArray
            ?: return invalid("Нужен ровно один TUN inbound.")
        val tunEntries = inbounds.mapIndexedNotNull { index, element ->
            (element as? JsonObject)
                ?.takeIf { it.string("type") == "tun" }
                ?.let { index to it }
        }
        if (tunEntries.size != 1) return invalid("Нужен ровно один TUN inbound.")

        val (tunIndex, tun) = tunEntries.single()
        if (tun.boolean("auto_route") != true) {
            return invalid("Для Android VPN требуется tun.auto_route=true.")
        }
        if (!hasDualStack(tun)) {
            return invalid("TUN должен иметь адреса IPv4 и IPv6.")
        }
        if (options.dnsMode in MANAGED_DNS_MODES && !hasManagedDnsIpv4(tun)) {
            return invalid("Для managed DNS нужен IPv4-адрес TUN с префиксом /30 или шире.")
        }
        if (!hasFullRoutes(tun)) {
            return invalid("TUN должен направлять полные IPv4 и IPv6 маршруты.")
        }
        if (hasRouteExclusions(tun)) {
            return invalid("Исключения TUN-маршрутов пока не поддерживаются.")
        }

        val includePresent = (tun["include_package"] as? JsonArray)?.isNotEmpty() == true
        val excludePresent = (tun["exclude_package"] as? JsonArray)?.isNotEmpty() == true
        if (includePresent && excludePresent) {
            return invalid("Нельзя одновременно задавать include_package и exclude_package.")
        }

        val route = root["route"] as? JsonObject
            ?: return invalid("В конфигурации отсутствует route.")
        if (route.boolean("auto_detect_interface") != true) {
            return invalid("Для защиты от VPN-цикла требуется route.auto_detect_interface=true.")
        }

        val runtimeTun = JsonObject(tun.filterKeys { it !in PACKAGE_FIELDS })
        val runtimeInbounds = JsonArray(inbounds.mapIndexed { index, value ->
            if (index == tunIndex) runtimeTun else value
        })
        val storedOutbounds = root["outbounds"] as? JsonArray
        val managed = storedOutbounds?.any { element ->
            val outbound = element as? JsonObject
            outbound?.string("type") == "selector" &&
                outbound.string("tag") == ConfigAnalyzer.MANAGED_SELECTOR_TAG
        } == true
        val selectedProxyTag = BootstrapConfig.selectedProxyTag(raw)
        if (options.dnsMode in PROXY_DNS_MODES && selectedProxyTag == null) {
            return invalid("Для управляемого DNS нужен выбранный proxy/selector outbound.")
        }
        if (options.updaterPackageName != null && selectedProxyTag == null) {
            return invalid("Для временного VPN-маршрута updater нужен выбранный proxy/selector outbound.")
        }
        if (options.updaterPackageName != null && !ANDROID_PACKAGE.matches(options.updaterPackageName)) {
            return invalid("Некорректный Android package временного updater-маршрута.")
        }
        if (options.healthCheckPackageName != null &&
            !ANDROID_PACKAGE.matches(options.healthCheckPackageName)
        ) {
            return invalid("Некорректный Android package health-check.")
        }
        val blockedPackages = options.blockedPackages.toSortedSet()
        if (blockedPackages.any { !ANDROID_PACKAGE.matches(it) }) {
            return invalid("Список заблокированных приложений содержит некорректный Android package.")
        }
        if (options.dnsMode in MANAGED_DNS_MODES && hasFakeIp(root)) {
            return invalid("FakeIP найден в JSON. Для него выберите режим «Из JSON».")
        }
        var runtimeOutbounds = storedOutbounds?.let(::enableManagedInterrupt)
        var runtimeEndpoints = root["endpoints"] as? JsonArray
        options.bootstrapHost?.let { overlay ->
            val outboundMatch = runtimeOutbounds?.hasTaggedEntry(overlay.outboundTag) == true
            val endpointMatch = runtimeEndpoints?.hasTaggedEntry(overlay.outboundTag) == true
            if (outboundMatch == endpointMatch) {
                return invalid("Bootstrap target должен однозначно ссылаться на один outbound или endpoint.")
            }
            if (outboundMatch) {
                runtimeOutbounds = applyBootstrapResolver(checkNotNull(runtimeOutbounds), overlay)
            } else {
                runtimeEndpoints = applyBootstrapResolver(checkNotNull(runtimeEndpoints), overlay)
            }
        }
        val storedLog = root["log"] as? JsonObject
        val runtimeLog = buildJsonObject {
            storedLog?.forEach { (key, value) ->
                if (key != "output" && key != "level") put(key, value)
            }
            put("level", runtimeLogLevel(storedLog?.string("level")))
        }

        val runtimeRoot = root.toMutableMap().apply {
                this["inbounds"] = runtimeInbounds
                this["log"] = runtimeLog
                runtimeOutbounds?.let { this["outbounds"] = it }
                runtimeEndpoints?.let { this["endpoints"] = it }
                if (managed) {
                    sanitizeManagedExperimental(root["experimental"])?.let {
                        this["experimental"] = it
                    } ?: remove("experimental")
                }
                if (enableTrafficStats) {
                    this["experimental"] = runtimeExperimental(this["experimental"])
                }
            }
        val updaterRoot = options.updaterPackageName?.let { packageName ->
            applyUpdaterRoute(
                root = JsonObject(runtimeRoot),
                packageName = packageName,
                proxyTag = checkNotNull(selectedProxyTag),
            )
        } ?: JsonObject(runtimeRoot)
        val dnsOverlay = applyDnsOverlay(
            root = updaterRoot,
            mode = options.dnsMode,
            proxyIpv4Only = options.proxyIpv4Only,
            dnsOverride = options.dnsOverride,
            selectedProxyTag = selectedProxyTag,
            bootstrapHost = options.bootstrapHost,
            healthCheckPackageName = options.healthCheckPackageName,
            blockedPackages = blockedPackages,
        )
        if (dnsOverlay is RuntimeConfigResult.Invalid) return dnsOverlay
        val runtime = (dnsOverlay as RuntimeConfigResult.Ready).json
        return RuntimeConfigResult.Ready(runtime)
    }

    /**
     * sing-box defaults to trace when log.level is absent. A VPN data plane must
     * never inherit that expensive default, including raw and endpoint-only
     * profiles. Preserve levels that are stricter than warn and clamp every
     * verbose level in the runtime copy without rewriting the stored JSON.
     */
    private fun runtimeLogLevel(storedLevel: String?): String = when (storedLevel?.lowercase()) {
        "panic", "fatal", "error" -> storedLevel.lowercase()
        else -> RUNTIME_LOG_LEVEL
    }

    /** Applies the conservative Amnezia MTU to Android's runtime copy only. */
    private fun applyAndroidWireGuardCompatibility(root: JsonObject): JsonObject {
        val endpoints = root["endpoints"] as? JsonArray ?: return root
        var changed = false
        val runtimeEndpoints = JsonArray(endpoints.map { element ->
            val endpoint = element as? JsonObject
            if (endpoint?.string("type") != "wireguard") return@map element
            val runtimeEndpoint = endpoint.toMutableMap()
            if ("mtu" !in endpoint) {
                runtimeEndpoint["mtu"] = JsonPrimitive(ANDROID_WIREGUARD_MTU)
                changed = true
            }
            JsonObject(runtimeEndpoint)
        })
        if (!changed) return root
        return JsonObject(root.toMutableMap().apply {
            this["endpoints"] = runtimeEndpoints
        })
    }

    /** Adds a process-scoped rule only to the temporary in-memory updater session. */
    private fun applyUpdaterRoute(
        root: JsonObject,
        packageName: String,
        proxyTag: String,
    ): JsonObject {
        val route = checkNotNull(root["route"] as? JsonObject).toMutableMap()
        val existingRules = (route["rules"] as? JsonArray).orEmpty()
        route["rules"] = JsonArray(listOf(updaterRouteRule(packageName, proxyTag)) + existingRules)
        return JsonObject(root.toMutableMap().apply { this["route"] = JsonObject(route) })
    }

    private fun updaterRouteRule(packageName: String, proxyTag: String): JsonObject = buildJsonObject {
        put("package_name", JsonArray(listOf(JsonPrimitive(packageName))))
        put("domain", JsonArray(UPDATER_DOMAINS.map(::JsonPrimitive)))
        put(
            "domain_suffix",
            JsonArray(UPDATER_DOMAIN_SUFFIXES.map(::JsonPrimitive)),
        )
        put("action", "route")
        put("outbound", proxyTag)
    }

    private fun applyDnsOverlay(
        root: JsonObject,
        mode: DnsMode,
        proxyIpv4Only: Boolean,
        dnsOverride: DnsOverride,
        selectedProxyTag: String?,
        bootstrapHost: BootstrapHostOverlay?,
        healthCheckPackageName: String?,
        blockedPackages: Set<String>,
    ): RuntimeConfigResult {
        val next = root.toMutableMap()
        var dns = (root["dns"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val profileServers = (dns["servers"] as? JsonArray)?.toList().orEmpty()
        var servers = if (mode in MANAGED_DNS_MODES) {
            profileServers.filterNot {
                (it as? JsonObject)?.string("tag") in GENERATED_DNS_SERVER_TAGS
            }
        } else {
            profileServers
        }
        val fromJsonNeedsAndroidFallback = mode == DnsMode.FromJson && profileServers.isEmpty()

        if (mode in MANAGED_DNS_MODES) {
            val normalizedOverride = if (dnsOverride.enabled) {
                DnsOverride.normalizedOrNull(
                    dnsOverride.hostname,
                    dnsOverride.ipv4Address,
                ) ?: return invalid("DNS-переопределение содержит неверный домен или IPv4-адрес.")
            } else {
                null
            }
            servers = servers + androidDnsServer()
            if (mode != DnsMode.Android) {
                val proxy = checkNotNull(selectedProxyTag)
                servers = servers + secureDnsServers(proxy)
            }
            normalizedOverride?.let { servers = servers + dnsOverrideServer(it) }
            dns["strategy"] = JsonPrimitive("prefer_ipv4")
            dns["cache_capacity"] = JsonPrimitive(4096)
            dns["reverse_mapping"] = JsonPrimitive(true)

            val existingRules = ((dns["rules"] as? JsonArray)?.toList().orEmpty())
                .filterNot(::isGeneratedDnsRule)
            val rejectRules = existingRules.filter(::isRejectRule)
            val otherRules = existingRules.filterNot(::isRejectRule)
            val generated = when (mode) {
                DnsMode.Android -> androidDnsRules(
                    root,
                    selectedProxyTag,
                    proxyIpv4Only,
                )
                DnsMode.Secure -> listOf(
                    catchAllDnsRule(SECURE_DNS_TAG, ipv4Only = proxyIpv4Only),
                )
                DnsMode.Automatic -> automaticDnsRules(
                    root,
                    checkNotNull(selectedProxyTag),
                    proxyIpv4Only,
                )
                DnsMode.FromJson -> emptyList()
            }
            val overrideRules = listOfNotNull(normalizedOverride?.let(::dnsOverrideRule))
            dns["rules"] = JsonArray(
                packageRejectRules(blockedPackages) +
                    rejectRules +
                    overrideRules +
                    generated +
                    otherRules,
            )
            dns["final"] = JsonPrimitive(
                when (mode) {
                    DnsMode.Android -> ANDROID_DNS_TAG
                    DnsMode.Secure -> SECURE_DNS_TAG
                    DnsMode.Automatic -> automaticFinal(root, checkNotNull(selectedProxyTag))
                    DnsMode.FromJson -> error("unreachable")
                },
            )
        } else if (fromJsonNeedsAndroidFallback) {
            // "From JSON" keeps a real profile DNS untouched. A generated/imported profile
            // with no DNS section has nothing to use, so fall back only then to Android DNS.
            servers = servers + androidDnsServer()
            dns["final"] = JsonPrimitive(ANDROID_DNS_TAG)
        }
        if (mode !in MANAGED_DNS_MODES && blockedPackages.isNotEmpty()) {
            val existingRules = (dns["rules"] as? JsonArray)?.toList().orEmpty()
            dns["rules"] = JsonArray(packageRejectRules(blockedPackages) + existingRules)
        }

        bootstrapHost?.let { overlay ->
            servers = servers + buildJsonObject {
                put("type", "hosts")
                put("tag", BOOTSTRAP_DNS_TAG)
                put(
                    "predefined",
                    buildJsonObject {
                        put(
                            overlay.hostname,
                            JsonArray(overlay.addresses.distinct().map(::JsonPrimitive)),
                        )
                    },
                )
            }
        }
        if (
            mode in MANAGED_DNS_MODES ||
            fromJsonNeedsAndroidFallback ||
            bootstrapHost != null ||
            blockedPackages.isNotEmpty()
        ) {
            dns["servers"] = JsonArray(servers)
            next["dns"] = JsonObject(dns)
        }

        if (
            mode in MANAGED_DNS_MODES ||
            fromJsonNeedsAndroidFallback ||
            selectedProxyTag != null ||
            blockedPackages.isNotEmpty()
        ) {
            val route = (root["route"] as? JsonObject)?.toMutableMap()
                ?: return invalid("В конфигурации отсутствует route.")
            val existingRules = (route["rules"] as? JsonArray)?.toList().orEmpty()
            val existing = if (mode in MANAGED_DNS_MODES) {
                existingRules.filterNot(::isGeneratedRouteRule)
            } else {
                existingRules
            }
            val generatedRouteRules = buildList {
                addAll(packageRejectRules(blockedPackages))
                if (mode in MANAGED_DNS_MODES || fromJsonNeedsAndroidFallback) add(hijackDnsRule())
                selectedProxyTag?.let { proxyTag ->
                    healthCheckPackageName?.let { add(healthProbeSniffRule(it)) }
                    add(healthProbeRule(proxyTag, healthCheckPackageName))
                }
            }
            route["rules"] = JsonArray(generatedRouteRules + existing)
            if (mode in MANAGED_DNS_MODES || fromJsonNeedsAndroidFallback) {
                route["default_domain_resolver"] = JsonPrimitive(ANDROID_DNS_TAG)
            }
            next["route"] = JsonObject(route)
        }
        return RuntimeConfigResult.Ready(JsonConfig.format(JsonObject(next)))
    }

    private fun packageRejectRules(packages: Set<String>): List<JsonObject> =
        if (packages.isEmpty()) {
            emptyList()
        } else {
            listOf(
                buildJsonObject {
                    put("package_name", JsonArray(packages.map(::JsonPrimitive)))
                    put("action", "reject")
                },
            )
        }

    private fun androidDnsServer(): JsonObject = buildJsonObject {
        put("type", "local")
        put("tag", ANDROID_DNS_TAG)
    }

    private fun dnsOverrideServer(override: DnsOverride): JsonObject = buildJsonObject {
        put("type", "hosts")
        put("tag", DNS_OVERRIDE_TAG)
        put(
            "predefined",
            buildJsonObject {
                put(
                    override.hostname,
                    JsonArray(listOf(JsonPrimitive(override.ipv4Address))),
                )
            },
        )
    }

    private fun dnsOverrideRule(override: DnsOverride): JsonObject = buildJsonObject {
        put("domain", JsonArray(listOf(JsonPrimitive(override.hostname))))
        put("action", "route")
        put("server", DNS_OVERRIDE_TAG)
    }

    private fun secureDnsServers(proxyTag: String): List<JsonObject> = listOf(
        dohServer(DOH_1_TAG, "9.9.9.9", "dns.quad9.net", proxyTag),
        dohServer(DOH_2_TAG, "8.8.8.8", "dns.google", proxyTag),
        dohServer(DOH_3_TAG, "208.67.222.222", "dns.opendns.com", proxyTag),
        dohServer(DOH_4_TAG, "1.1.1.1", "cloudflare-dns.com", proxyTag),
        dohServer(DOH_5_TAG, "77.88.8.8", "common.dot.dns.yandex.net", proxyTag),
        buildJsonObject {
            put("type", "fallback")
            put("tag", SECURE_DNS_TAG)
            put(
                "servers",
                JsonArray(
                    listOf(DOH_1_TAG, DOH_2_TAG, DOH_3_TAG, DOH_4_TAG, DOH_5_TAG)
                        .map(::JsonPrimitive),
                ),
            )
            // A sequential transport cannot advance when the first DoH hangs until the
            // caller deadline. Parallel is the smallest reliable bounded fallback.
            put("strategy", "parallel")
        },
    )

    private fun dohServer(tag: String, address: String, serverName: String, proxyTag: String) =
        buildJsonObject {
            put("type", "https")
            put("tag", tag)
            put("server", address)
            put(
                "tls",
                buildJsonObject {
                    put("enabled", true)
                    put("server_name", serverName)
                },
            )
            put("detour", proxyTag)
        }

    private fun automaticDnsRules(
        root: JsonObject,
        selectedProxyTag: String,
        proxyIpv4Only: Boolean,
    ): List<JsonElement> {
        val result = mutableListOf<JsonElement>()
        result += buildJsonObject {
            put("domain_regex", JsonArray(listOf(JsonPrimitive("^[^.]+$"))))
            put("action", "route")
            put("server", ANDROID_DNS_TAG)
        }
        result += buildJsonObject {
            put("domain", JsonArray(listOf(JsonPrimitive("home.arpa"))))
            put(
                "domain_suffix",
                JsonArray(listOf(".local", ".lan", ".home.arpa").map(::JsonPrimitive)),
            )
            put("action", "route")
            put("server", ANDROID_DNS_TAG)
        }
        val routeRules = ((root["route"] as? JsonObject)?.get("rules") as? JsonArray).orEmpty()
        routeRules.mapNotNullTo(result) { element ->
            val rule = element as? JsonObject ?: return@mapNotNullTo null
            if (rule.string("action") != "route") return@mapNotNullTo null
            val outbound = rule.string("outbound") ?: return@mapNotNullTo null
            val match = domainMatchForDns(root, rule)
            if (match.isEmpty()) return@mapNotNullTo null
            val server = if (outbound == "direct") ANDROID_DNS_TAG else {
                if (outbound == selectedProxyTag) SECURE_DNS_TAG else return@mapNotNullTo null
            }
            JsonObject(
                match + buildMap {
                    put("action", JsonPrimitive("route"))
                    put("server", JsonPrimitive(server))
                    if (proxyIpv4Only && server == SECURE_DNS_TAG) {
                        put("strategy", JsonPrimitive(IPV4_ONLY_STRATEGY))
                    }
                },
            )
        }
        if (proxyIpv4Only && automaticFinal(root, selectedProxyTag) == SECURE_DNS_TAG) {
            result += catchAllDnsRule(SECURE_DNS_TAG, ipv4Only = true)
        }
        return result
    }

    private fun androidDnsRules(
        root: JsonObject,
        selectedProxyTag: String?,
        proxyIpv4Only: Boolean,
    ): List<JsonElement> {
        if (!proxyIpv4Only || selectedProxyTag == null) {
            return listOf(catchAllDnsRule(ANDROID_DNS_TAG))
        }
        val result = mutableListOf<JsonElement>()
        result += buildJsonObject {
            put(
                "domain",
                JsonArray(ManagedHealthProbe.hosts.map(::JsonPrimitive)),
            )
            put("action", "route")
            put("server", ANDROID_DNS_TAG)
            put("strategy", IPV4_ONLY_STRATEGY)
        }
        val routeRules = ((root["route"] as? JsonObject)?.get("rules") as? JsonArray).orEmpty()
        routeRules.mapNotNullTo(result) { element ->
            val rule = element as? JsonObject ?: return@mapNotNullTo null
            if (rule.string("action") != "route") return@mapNotNullTo null
            val outbound = rule.string("outbound") ?: return@mapNotNullTo null
            if (outbound != selectedProxyTag && outbound != "direct") return@mapNotNullTo null
            val match = domainMatchForDns(root, rule)
            if (match.isEmpty()) return@mapNotNullTo null
            JsonObject(
                match + buildMap {
                    put("action", JsonPrimitive("route"))
                    put("server", JsonPrimitive(ANDROID_DNS_TAG))
                    if (outbound == selectedProxyTag) {
                        put("strategy", JsonPrimitive(IPV4_ONLY_STRATEGY))
                    }
                },
            )
        }
        val proxyFinal = automaticFinal(root, selectedProxyTag) == SECURE_DNS_TAG
        result += catchAllDnsRule(ANDROID_DNS_TAG, ipv4Only = proxyFinal)
        return result
    }

    private fun domainMatchForDns(root: JsonObject, rule: JsonObject): Map<String, JsonElement> {
        val match = rule.filterKeys(DOMAIN_MATCH_FIELDS::contains).toMutableMap()
        if (rule["ip_version"] != null) {
            match.remove("rule_set")
        } else {
            val domainTags = stringArray(match["rule_set"]).filter { tag ->
                isDomainCapableRuleSet(root, tag)
            }
            if (domainTags.isEmpty()) match.remove("rule_set")
            else match["rule_set"] = JsonArray(domainTags.map(::JsonPrimitive))
        }
        if (match.keys.none(DOMAIN_HEADLESS_FIELDS::contains) && "rule_set" !in match) return emptyMap()
        return match
    }

    private fun isDomainCapableRuleSet(root: JsonObject, tag: String): Boolean {
        if (tag == "zapret-ru-ip" || tag.startsWith("zapret-user-ipcidr-")) return false
        val route = root["route"] as? JsonObject ?: return true
        val definition = ((route["rule_set"] as? JsonArray).orEmpty())
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("tag") == tag } ?: return true
        if (definition.string("type") != "inline") return true
        return ((definition["rules"] as? JsonArray).orEmpty())
            .mapNotNull { it as? JsonObject }
            .any { headless -> headless.keys.any(DOMAIN_HEADLESS_FIELDS::contains) }
    }

    private fun automaticFinal(root: JsonObject, selectedProxyTag: String): String {
        val final = (root["route"] as? JsonObject)?.string("final")
        return if (final == "direct" && !routesAllAddressesThrough(root, selectedProxyTag)) {
            ANDROID_DNS_TAG
        } else if (final == selectedProxyTag || routesAllAddressesThrough(root, selectedProxyTag)) {
            SECURE_DNS_TAG
        } else {
            SECURE_DNS_TAG
        }
    }

    private fun routesAllAddressesThrough(root: JsonObject, tag: String): Boolean =
        (((root["route"] as? JsonObject)?.get("rules") as? JsonArray).orEmpty())
            .mapNotNull { it as? JsonObject }
            .filter { it.string("action") == "route" && it.string("outbound") == tag }
            .flatMap { stringArray(it["ip_cidr"]) }
            .any { it == "0.0.0.0/0" || it == "::/0" }

    private fun catchAllDnsRule(server: String, ipv4Only: Boolean = false): JsonObject = buildJsonObject {
        put("action", "route")
        put("server", server)
        if (ipv4Only) put("strategy", IPV4_ONLY_STRATEGY)
    }

    private fun hijackDnsRule(): JsonObject = buildJsonObject {
        put("port", 53)
        put("action", "hijack-dns")
    }

    /**
     * Sniff only this application's short-lived TLS health probe. This makes the
     * following domain rule authoritative even for raw JSON without DNS reverse
     * mapping, without enabling global sniffing for selected user applications.
     */
    private fun healthProbeSniffRule(packageName: String): JsonObject = buildJsonObject {
        put("package_name", JsonArray(listOf(JsonPrimitive(packageName))))
        put("network", "tcp")
        put("port", 443)
        put("action", "sniff")
        put("sniffer", JsonArray(listOf(JsonPrimitive("tls"))))
        put("timeout", "2s")
    }

    private fun healthProbeRule(proxyTag: String, packageName: String?): JsonObject = buildJsonObject {
        packageName?.let {
            put("package_name", JsonArray(listOf(JsonPrimitive(it))))
        }
        put("network", "tcp")
        put("port", 443)
        put(
            "domain",
            JsonArray(ManagedHealthProbe.hosts.map(::JsonPrimitive)),
        )
        put("action", "route")
        put("outbound", proxyTag)
    }

    private fun isRejectRule(element: JsonElement): Boolean =
        (element as? JsonObject)?.string("action") == "reject"

    private fun isGeneratedDnsRule(element: JsonElement): Boolean {
        val rule = element as? JsonObject ?: return false
        return rule.string("server")?.startsWith("zapret-") == true
    }

    private fun isGeneratedRouteRule(element: JsonElement): Boolean {
        val rule = element as? JsonObject ?: return false
        return rule.string("action") == "hijack-dns" &&
            (rule["port"] as? JsonPrimitive)?.contentOrNull == "53"
            || rule.string("action") == "route" &&
            rule.string("outbound")?.startsWith("zapret-") == true &&
            (rule["domain"] as? JsonArray)?.any {
                (it as? JsonPrimitive)?.contentOrNull in ManagedHealthProbe.hosts
            } == true
    }

    private fun hasFakeIp(root: JsonObject): Boolean =
        (((root["dns"] as? JsonObject)?.get("servers") as? JsonArray).orEmpty()).any {
            (it as? JsonObject)?.string("type") == "fakeip"
        }

    private fun JsonArray.hasTaggedEntry(tag: String): Boolean = any { element ->
        (element as? JsonObject)?.string("tag") == tag
    }

    private fun applyBootstrapResolver(
        entries: JsonArray,
        overlay: BootstrapHostOverlay,
    ): JsonArray = JsonArray(entries.map { element ->
        val entry = element as? JsonObject ?: return@map element
        if (entry.string("tag") != overlay.outboundTag) return@map element
        JsonObject(entry.toMutableMap().apply {
            this["domain_resolver"] = JsonPrimitive(BOOTSTRAP_DNS_TAG)
        })
    })

    private fun hasDualStack(tun: JsonObject): Boolean {
        val addresses = stringArray(tun["address"])
        val legacyV4 = stringArray(tun["inet4_address"])
        val legacyV6 = stringArray(tun["inet6_address"])
        return (addresses + legacyV4).any(::isIpv4Prefix) &&
            (addresses + legacyV6).any(::isIpv6Prefix)
    }

    private fun hasManagedDnsIpv4(tun: JsonObject): Boolean {
        val addresses = stringArray(tun["address"]) + stringArray(tun["inet4_address"])
        return addresses.any { value ->
            if (!isIpv4Prefix(value)) return@any false
            value.substringAfterLast('/').toIntOrNull()?.let { it <= 30 } == true
        }
    }

    private fun hasFullRoutes(tun: JsonObject): Boolean {
        if (ROUTE_SET_FIELDS.any { key ->
                when (val value = tun[key]) {
                    is JsonArray -> value.isNotEmpty()
                    is JsonPrimitive -> value.contentOrNull?.isNotBlank() == true
                    else -> value != null
                }
            }
        ) {
            return false
        }
        val routes = stringArray(tun["route_address"])
        val legacyV4 = stringArray(tun["inet4_route_address"])
        val legacyV6 = stringArray(tun["inet6_route_address"])
        if (routes.isEmpty() && legacyV4.isEmpty() && legacyV6.isEmpty()) return true
        return (routes + legacyV4).any { it == "0.0.0.0/0" } &&
            (routes + legacyV6).any { it == "::/0" }
    }

    private fun hasRouteExclusions(tun: JsonObject): Boolean = ROUTE_EXCLUDE_FIELDS.any { key ->
        when (val value = tun[key]) {
            is JsonArray -> value.isNotEmpty()
            is JsonPrimitive -> value.contentOrNull?.isNotBlank() == true
            else -> value != null
        }
    }

    private fun enableManagedInterrupt(outbounds: JsonArray): JsonArray = JsonArray(
        outbounds.map { element ->
            val outbound = element as? JsonObject ?: return@map element
            if (outbound.string("type") != "selector" ||
                outbound.string("tag") != ConfigAnalyzer.MANAGED_SELECTOR_TAG
            ) {
                return@map element
            }
            JsonObject(outbound.toMutableMap().apply {
                this["interrupt_exist_connections"] = JsonPrimitive(true)
            })
        },
    )

    /** Enables the in-process traffic manager without opening a Clash HTTP controller. */
    private fun runtimeExperimental(stored: JsonElement?): JsonObject {
        val experimental = (stored as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (experimental["clash_api"] !is JsonObject) {
            experimental["clash_api"] = JsonObject(emptyMap())
        }
        return JsonObject(experimental)
    }

    /** Managed profiles never expose a Clash HTTP listener; the stored JSON remains untouched. */
    private fun sanitizeManagedExperimental(stored: JsonElement?): JsonObject? {
        val experimental = (stored as? JsonObject)?.toMutableMap() ?: return null
        val clash = experimental["clash_api"] as? JsonObject
        if (clash != null) {
            experimental["clash_api"] = JsonObject(clash.filterKeys { it !in CLASH_LISTENER_FIELDS })
        }
        return JsonObject(experimental)
    }

    private fun findForbiddenField(element: JsonElement): String? = when (element) {
        is JsonObject -> element.entries.firstNotNullOfOrNull { (key, value) ->
            key.takeIf(FORBIDDEN_FIELDS::contains) ?: findForbiddenField(value)
        }
        is JsonArray -> element.firstNotNullOfOrNull(::findForbiddenField)
        else -> null
    }

    private fun stringArray(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun isIpv4Prefix(value: String): Boolean = ':' !in value && '/' in value
    private fun isIpv6Prefix(value: String): Boolean = ':' in value && '/' in value
    private fun invalid(message: String) = RuntimeConfigResult.Invalid(message)

    private val PACKAGE_FIELDS = setOf("include_package", "exclude_package")
    private val ANDROID_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val UPDATER_DOMAINS = listOf("zapret.moe")
    private val UPDATER_DOMAIN_SUFFIXES = listOf(".zapret.moe")
    private val ROUTE_EXCLUDE_FIELDS = setOf(
        "route_exclude_address",
        "route_exclude_address_set",
        "inet4_route_exclude_address",
        "inet6_route_exclude_address",
    )
    private val ROUTE_SET_FIELDS = setOf(
        "route_address_set",
        "inet4_route_address_set",
        "inet6_route_address_set",
    )
    private val FORBIDDEN_FIELDS = setOf(
        "default_interface",
        "default_mark",
        "bind_interface",
        "inet4_bind_address",
        "inet6_bind_address",
        "bind_address_no_port",
        "routing_mark",
        "netns",
        "protect_path",
    )
    private val CLASH_LISTENER_FIELDS = setOf(
        "external_controller",
        "external_controller_tls",
        "secret",
        "external_ui",
        "external_ui_download_url",
        "external_ui_download_detour",
        "access_control_allow_origin",
        "access_control_allow_private_network",
    )
    private val MANAGED_DNS_MODES = setOf(DnsMode.Automatic, DnsMode.Android, DnsMode.Secure)
    private val PROXY_DNS_MODES = setOf(DnsMode.Automatic, DnsMode.Secure)
    private const val ANDROID_DNS_TAG = "zapret-android-dns"
    private const val ANDROID_WIREGUARD_MTU = 1280
    private const val RUNTIME_LOG_LEVEL = "warn"
    private const val DOH_1_TAG = "zapret-doh-1"
    private const val DOH_2_TAG = "zapret-doh-2"
    private const val DOH_3_TAG = "zapret-doh-3"
    private const val DOH_4_TAG = "zapret-doh-4"
    private const val DOH_5_TAG = "zapret-doh-5"
    private const val SECURE_DNS_TAG = "zapret-secure-dns"
    private const val BOOTSTRAP_DNS_TAG = "zapret-bootstrap-lkg"
    private const val DNS_OVERRIDE_TAG = "zapret-dns-override"
    private const val IPV4_ONLY_STRATEGY = "ipv4_only"
    private val GENERATED_DNS_SERVER_TAGS = setOf(
        ANDROID_DNS_TAG,
        DOH_1_TAG,
        DOH_2_TAG,
        DOH_3_TAG,
        DOH_4_TAG,
        DOH_5_TAG,
        SECURE_DNS_TAG,
        BOOTSTRAP_DNS_TAG,
        DNS_OVERRIDE_TAG,
    )
    private val DOMAIN_MATCH_FIELDS = setOf(
        "domain",
        "domain_suffix",
        "domain_keyword",
        "domain_regex",
        "package_name",
        "rule_set",
        "rule_set_ip_cidr_match_source",
        "invert",
    )
    private val DOMAIN_HEADLESS_FIELDS = setOf(
        "domain",
        "domain_suffix",
        "domain_keyword",
        "domain_regex",
    )
}
