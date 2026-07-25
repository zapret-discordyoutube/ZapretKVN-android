package io.github.zapretkvn.android.routing

import io.github.zapretkvn.android.config.BootstrapConfig
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.string
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

enum class RoutingPreset(val title: String, val detail: String) {
    AllThroughVpn("Всё через VPN", "Все назначения выбранных приложений → VPN"),
    BypassLan("Обход LAN", "LAN → напрямую, остальное → VPN"),
    OnlySelectedSites("Только выбранные сайты", "Выбранные правила → VPN, остальное → напрямую"),
    RussiaDirect("Россия напрямую", "Россия и LAN → напрямую, остальное → VPN"),
    RussiaVpn("Россия через VPN", "Россия → VPN, LAN и остальное → напрямую"),
    Custom("Пользовательский", "Использовать правила настоящего JSON"),
}

enum class RoutingRuleAction(val title: String) {
    Proxy("Через VPN"),
    Direct("Напрямую"),
    Block("Блокировать"),
}

enum class RoutingMatchType(val title: String) {
    Domain("Точный домен"),
    DomainSuffix("Суффикс домена"),
    IpCidr("IP / CIDR"),
    DomainRuleSet("Domain rule-set"),
    IpRuleSet("IP rule-set"),
}

data class ManagedRoutingRule(
    val matchType: RoutingMatchType,
    val values: List<String>,
    val action: RoutingRuleAction,
    val outboundTag: String? = null,
)

data class RoutingInspection(
    val preset: RoutingPreset,
    val rules: List<ManagedRoutingRule>,
    val proxyTag: String?,
    val directTag: String?,
    val outboundTags: List<String>,
    val customRuleCount: Int,
    val remoteRuleSetCount: Int,
    val summary: String,
)

data class RoutingEditResult(
    val json: String,
    val inspection: RoutingInspection,
    val diff: String,
)

/** Pure route/dns compiler. Callers choose whether the result is stored or used as a runtime overlay. */
object RoutingConfigEditor {
    fun inspect(raw: String): RoutingInspection {
        val root = root(raw)
        val route = root["route"] as? JsonObject ?: JsonObject(emptyMap())
        val rules = (route["rules"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val directTag = directTag(root)
        val proxyTag = BootstrapConfig.selectedProxyTag(raw)
            ?: inferredProxyTag(root, rules, directTag)
        val final = route.string("final")
        val ruRule = rules.firstOrNull(::isRuRule)
        val lanDirect = rules.any { it.isPrivateDirect(directTag) }
        val editable = editableRulesFromInline(route, rules, proxyTag, directTag) +
            rules.mapNotNull { parseEditableRule(it, proxyTag, directTag) }
        val selectedSitesMarker = ruleSets(route).any {
            it.string("tag") == SELECTED_SITES_MARKER
        }
        val customMarker = ruleSets(route).any {
            it.string("tag") == CUSTOM_MARKER
        }
        val preset = when {
            customMarker -> RoutingPreset.Custom
            ruRule != null && ruRule.routeOutbound() == directTag && final == proxyTag ->
                RoutingPreset.RussiaDirect
            ruRule != null && ruRule.routeOutbound() == proxyTag && final == directTag ->
                RoutingPreset.RussiaVpn
            selectedSitesMarker && final == directTag -> RoutingPreset.OnlySelectedSites
            lanDirect && final == proxyTag -> RoutingPreset.BypassLan
            final != null && final == proxyTag -> RoutingPreset.AllThroughVpn
            else -> RoutingPreset.Custom
        }
        val knownRules = rules.count { isInfrastructureRule(it) || isGeneratedRoutingRule(it) ||
            isRuRule(it) ||
            it.isPrivateDirect(directTag) || parseEditableRule(it, proxyTag, directTag) != null }
        val remotes = ruleSets(route).count { it.string("type") == "remote" }
        return RoutingInspection(
            preset = preset,
            rules = editable,
            proxyTag = proxyTag,
            directTag = directTag,
            outboundTags = outboundTags(root),
            customRuleCount = (rules.size - knownRules).coerceAtLeast(0),
            remoteRuleSetCount = remotes,
            summary = summary(preset, editable.size, rules.count(::isReject)),
        )
    }

    fun apply(
        raw: String,
        preset: RoutingPreset,
        manualRules: List<ManagedRoutingRule>,
        installed: InstalledRuleSets,
    ): RoutingEditResult {
        val beforeRoot = root(raw)
        val selectedProxyTag = BootstrapConfig.selectedProxyTag(raw)
        val needsProxy = preset != RoutingPreset.Custom || manualRules.any {
            it.action == RoutingRuleAction.Proxy && it.outboundTag.isNullOrBlank()
        }
        val proxyTag = selectedProxyTag ?: manualRules.firstNotNullOfOrNull {
            it.outboundTag?.takeIf(String::isNotBlank)
        } ?: if (needsProxy) {
            error("Для этого режима нужен выбранный proxy/selector outbound.")
        } else {
            directTag(beforeRoot) ?: MANAGED_DIRECT_TAG
        }
        val directTag = directTag(beforeRoot) ?: MANAGED_DIRECT_TAG
        val nextRoot = ensureDirectOutbound(beforeRoot, directTag).toMutableMap()
        val storedRoute = beforeRoot["route"] as? JsonObject
            ?: error("В конфигурации отсутствует route.")
        val route = storedRoute.toMutableMap()
        val existingRules = (storedRoute["rules"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filterNot { rule -> isGeneratedRoutingRule(rule) || parseEditableRule(rule, proxyTag, directTag) != null }
        val retainedRuleSets = ruleSets(storedRoute).filterNot { item ->
            item.string("tag")?.let(::isManagedRuleSetTag) == true
        }

        val compiled = manualRules.map(::normalizeRule).map { rule ->
            CompiledRule(rule, compileMatch(rule))
        }
        val generatedSets = compiled.mapNotNull(CompiledRule::inlineRuleSet)
        val presetSets = buildList {
            if (preset == RoutingPreset.RussiaDirect || preset == RoutingPreset.RussiaVpn) {
                add(localRuleSet(RU_DOMAINS_TAG, installed.requirePath(RU_DOMAINS_TAG)))
                add(localRuleSet(RU_IP_TAG, installed.requirePath(RU_IP_TAG)))
            }
            if (preset == RoutingPreset.OnlySelectedSites) add(selectedSitesMarker())
            if (preset == RoutingPreset.Custom) add(customMarker())
        }

        val manualRouteRules = compiled.map { it.routeRule(proxyTag, directTag) }
        val manualDnsReject = compiled.mapNotNull { it.dnsRejectRule() }
        val retainedInfrastructure = existingRules.filter(::isInfrastructureRule)
        val retainedPolicy = existingRules.filterNot(::isInfrastructureRule)
        val retainedReject = retainedPolicy.filter(::isReject)
        val retainedDirect = retainedPolicy.filter { it.routeOutbound() == directTag }
        val retainedProxy = retainedPolicy.filter { it.routeOutbound() == proxyTag }
        val retainedOther = retainedPolicy - retainedReject.toSet() - retainedDirect.toSet() - retainedProxy.toSet()
        val block = manualRouteRules.filter(::isReject)
        val direct = manualRouteRules.filter { it.routeOutbound() == directTag }
        val proxy = manualRouteRules.filter { it.routeOutbound() != null && it.routeOutbound() != directTag }
        val presetDirect = mutableListOf<JsonObject>()
        val presetProxy = mutableListOf<JsonObject>()

        if (preset in setOf(RoutingPreset.BypassLan, RoutingPreset.RussiaDirect, RoutingPreset.RussiaVpn)) {
            presetDirect += privateDirectRule(directTag)
        }
        when (preset) {
            RoutingPreset.RussiaDirect -> presetDirect += ruRule(directTag)
            RoutingPreset.RussiaVpn -> presetProxy += ruRule(proxyTag)
            else -> Unit
        }
        route["rules"] = JsonArray(
            retainedInfrastructure + block + retainedReject + direct + presetDirect + retainedDirect +
                proxy + presetProxy + retainedProxy + retainedOther,
        )
        route["rule_set"] = JsonArray(retainedRuleSets + generatedSets + presetSets)
        if (preset != RoutingPreset.Custom) {
            route["final"] = JsonPrimitive(
                when (preset) {
                    RoutingPreset.OnlySelectedSites, RoutingPreset.RussiaVpn -> directTag
                    else -> proxyTag
                },
            )
        }
        nextRoot["route"] = JsonObject(route)

        val storedDns = beforeRoot["dns"] as? JsonObject
        if (storedDns != null || manualDnsReject.isNotEmpty()) {
            val dns = storedDns?.toMutableMap() ?: mutableMapOf()
            val existingDnsRules = (dns["rules"] as? JsonArray).orEmpty().filterNot(::isManagedDnsRule)
            dns["rules"] = JsonArray(manualDnsReject + existingDnsRules)
            nextRoot["dns"] = JsonObject(dns)
        }

        val json = JsonConfig.format(JsonObject(nextRoot))
        val inspection = inspect(json)
        return RoutingEditResult(
            json = json,
            inspection = inspection,
            diff = managedDiff(beforeRoot, JsonConfig.parse(json) as JsonObject),
        )
    }

    fun rebindManagedRuleSetPaths(raw: String, installed: InstalledRuleSets): String {
        val root = root(raw)
        val route = root["route"] as? JsonObject ?: return JsonConfig.format(root)
        val sets = route["rule_set"] as? JsonArray ?: return JsonConfig.format(root)
        var changed = false
        val rebound = JsonArray(sets.map { element ->
            val item = element as? JsonObject ?: return@map element
            val tag = item.string("tag")
            if (tag != RU_DOMAINS_TAG && tag != RU_IP_TAG) return@map item
            val expected = installed.requirePath(tag)
            if (item.string("path") == expected && item.string("type") == "local" &&
                item.string("format") == "binary"
            ) return@map item
            changed = true
            localRuleSet(tag, expected)
        })
        if (!changed) return JsonConfig.format(root)
        val nextRoute = JsonObject(route.toMutableMap().apply { this["rule_set"] = rebound })
        return JsonConfig.format(JsonObject(root.toMutableMap().apply { this["route"] = nextRoute }))
    }

    fun usesManagedLocalRuleSets(raw: String): Boolean {
        val route = root(raw)["route"] as? JsonObject ?: return false
        return ruleSets(route).any { it.string("tag") == RU_DOMAINS_TAG || it.string("tag") == RU_IP_TAG }
    }

    private fun compileMatch(rule: ManagedRoutingRule): JsonObject? {
        val field = when (rule.matchType) {
            RoutingMatchType.Domain -> "domain"
            RoutingMatchType.DomainSuffix -> "domain_suffix"
            RoutingMatchType.IpCidr -> "ip_cidr"
            RoutingMatchType.DomainRuleSet,
            RoutingMatchType.IpRuleSet,
            -> return null
        }
        val tag = "$MANAGED_PREFIX${rule.matchType.name.lowercase()}-${shortHash(rule.values.joinToString("\u0000"))}"
        return buildJsonObject {
            put("type", "inline")
            put("tag", tag)
            put(
                "rules",
                JsonArray(listOf(buildJsonObject {
                    put(field, JsonArray(rule.values.map(::JsonPrimitive)))
                })),
            )
        }
    }

    private data class CompiledRule(
        val source: ManagedRoutingRule,
        val inlineRuleSet: JsonObject?,
    ) {
        private val tags: List<String>
            get() = inlineRuleSet?.string("tag")?.let(::listOf) ?: source.values

        fun routeRule(proxyTag: String, directTag: String): JsonObject = buildJsonObject {
            put("rule_set", JsonArray(tags.map(::JsonPrimitive)))
            if (source.matchType == RoutingMatchType.IpRuleSet) {
                put("ip_version", JsonArray(listOf(JsonPrimitive(4), JsonPrimitive(6))))
            }
            when (source.action) {
                RoutingRuleAction.Block -> put("action", "reject")
                RoutingRuleAction.Direct, RoutingRuleAction.Proxy -> {
                    put("action", "route")
                    put(
                        "outbound",
                        if (source.action == RoutingRuleAction.Direct) directTag
                        else source.outboundTag?.takeIf(String::isNotBlank) ?: proxyTag,
                    )
                }
            }
        }

        fun dnsRejectRule(): JsonObject? {
            if (source.action != RoutingRuleAction.Block ||
                source.matchType !in DOMAIN_MATCH_TYPES
            ) return null
            return buildJsonObject {
                put("rule_set", JsonArray(tags.map(::JsonPrimitive)))
                put("action", "reject")
            }
        }
    }

    private fun parseEditableRule(
        rule: JsonObject,
        proxyTag: String?,
        directTag: String?,
    ): ManagedRoutingRule? {
        val tags = strings(rule["rule_set"])
        if (tags.isEmpty() || tags.any { it == RU_DOMAINS_TAG || it == RU_IP_TAG || it == SELECTED_SITES_MARKER }) {
            return null
        }
        val action = when {
            rule.string("action") == "reject" -> RoutingRuleAction.Block
            rule.string("action") == "route" && rule.string("outbound") == directTag ->
                RoutingRuleAction.Direct
            rule.string("action") == "route" && rule.string("outbound") != null ->
                RoutingRuleAction.Proxy
            else -> return null
        }
        if (rule.keys.any { it !in SIMPLE_RULE_FIELDS }) return null
        if (tags.size == 1 && tags.single().startsWith(MANAGED_PREFIX)) {
            return null // Parsed together with its inline set below.
        }
        return ManagedRoutingRule(
            matchType = if (rule["ip_version"] != null) {
                RoutingMatchType.IpRuleSet
            } else {
                RoutingMatchType.DomainRuleSet
            },
            values = tags,
            action = action,
            outboundTag = rule.string("outbound")?.takeIf { it != proxyTag && it != directTag },
        )
    }

    private fun editableRulesFromInline(
        route: JsonObject,
        routeRules: List<JsonObject>,
        proxyTag: String?,
        directTag: String?,
    ): List<ManagedRoutingRule> {
        val sets = ruleSets(route).associateBy { it.string("tag") }
        return routeRules.mapNotNull { rule ->
            val tags = strings(rule["rule_set"])
            if (tags.size != 1 || !tags.single().startsWith(MANAGED_PREFIX)) return@mapNotNull null
            val set = sets[tags.single()] ?: return@mapNotNull null
            val headless = ((set["rules"] as? JsonArray)?.singleOrNull() as? JsonObject)
                ?: return@mapNotNull null
            val (type, values) = when {
                headless["domain"] != null -> RoutingMatchType.Domain to strings(headless["domain"])
                headless["domain_suffix"] != null -> RoutingMatchType.DomainSuffix to strings(headless["domain_suffix"])
                headless["ip_cidr"] != null -> RoutingMatchType.IpCidr to strings(headless["ip_cidr"])
                else -> return@mapNotNull null
            }
            val action = when {
                rule.string("action") == "reject" -> RoutingRuleAction.Block
                rule.routeOutbound() == directTag -> RoutingRuleAction.Direct
                rule.routeOutbound() != null -> RoutingRuleAction.Proxy
                else -> return@mapNotNull null
            }
            ManagedRoutingRule(type, values, action, rule.routeOutbound()?.takeIf { it != proxyTag && it != directTag })
        }
    }

    private fun root(raw: String): JsonObject = JsonConfig.parse(raw) as? JsonObject
        ?: error("Корень конфигурации должен быть JSON-объектом.")

    private fun directTag(root: JsonObject): String? =
        (root["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "direct" }
            .let { direct ->
                direct.firstOrNull { it.string("tag") == "direct" }?.string("tag")
                    ?: direct.firstOrNull { it.string("tag") == MANAGED_DIRECT_TAG }?.string("tag")
                    ?: direct.firstOrNull()?.string("tag")
            }

    private fun outboundTags(root: JsonObject): List<String> =
        (root["outbounds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("tag") }

    private fun inferredProxyTag(
        root: JsonObject,
        rules: List<JsonObject>,
        directTag: String?,
    ): String? {
        val outbounds = (root["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val proxyCandidates = outbounds.filter {
            it.string("type") !in setOf("direct", "block", "dns", null)
        }.mapNotNull { it.string("tag") }.toSet()
        return rules.asSequence()
            .filter(::isRuRule)
            .mapNotNull { it.routeOutbound() }
            .firstOrNull { it != directTag && it in proxyCandidates }
            ?: rules.asSequence()
                .mapNotNull { it.routeOutbound() }
                .firstOrNull { it != directTag && it in proxyCandidates }
            ?: outbounds.firstOrNull { it.string("type") in setOf("selector", "urltest") }
                ?.string("tag")
            ?: proxyCandidates.singleOrNull()
    }

    private fun ensureDirectOutbound(root: JsonObject, directTag: String): JsonObject {
        if (directTag(root) != null) return root
        val outbounds = (root["outbounds"] as? JsonArray)?.toList()
            ?: error("В конфигурации отсутствуют outbounds.")
        val direct = buildJsonObject { put("type", "direct"); put("tag", directTag) }
        return JsonObject(root.toMutableMap().apply { this["outbounds"] = JsonArray(outbounds + direct) })
    }

    private fun ruleSets(route: JsonObject): List<JsonObject> =
        (route["rule_set"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun normalizeRule(rule: ManagedRoutingRule): ManagedRoutingRule {
        val values = rule.values.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
        require(values.isNotEmpty()) { "Правило не содержит значений." }
        values.forEach { value ->
            require(value.length <= 253 && !value.any(Char::isWhitespace)) {
                "Некорректное значение правила: '$value'."
            }
        }
        return rule.copy(values = values)
    }

    private fun localRuleSet(tag: String, path: String): JsonObject = buildJsonObject {
        put("type", "local")
        put("tag", tag)
        put("format", "binary")
        put("path", path)
    }

    private fun selectedSitesMarker(): JsonObject = buildJsonObject {
        put("type", "inline")
        put("tag", SELECTED_SITES_MARKER)
        put(
            "rules",
            JsonArray(listOf(buildJsonObject {
                put("domain", JsonArray(listOf(JsonPrimitive("marker.zapret-kvn.invalid"))))
            })),
        )
    }

    private fun customMarker(): JsonObject = buildJsonObject {
        put("type", "inline")
        put("tag", CUSTOM_MARKER)
        put(
            "rules",
            JsonArray(listOf(buildJsonObject {
                put("domain", JsonArray(listOf(JsonPrimitive("custom.zapret-kvn.invalid"))))
            })),
        )
    }

    private fun privateDirectRule(directTag: String): JsonObject = buildJsonObject {
        put("ip_is_private", true)
        put("action", "route")
        put("outbound", directTag)
    }

    private fun ruRule(outbound: String): JsonObject = buildJsonObject {
        put("rule_set", JsonArray(listOf(JsonPrimitive(RU_DOMAINS_TAG), JsonPrimitive(RU_IP_TAG))))
        put("action", "route")
        put("outbound", outbound)
    }

    private fun JsonObject.routeOutbound(): String? =
        string("outbound") ?: string("server")

    private fun JsonObject.isPrivateDirect(directTag: String?): Boolean =
        (this["ip_is_private"] as? JsonPrimitive)?.contentOrNull == "true" &&
            routeOutbound() == directTag

    private fun isRuRule(rule: JsonObject): Boolean {
        val tags = strings(rule["rule_set"])
        return RU_DOMAINS_TAG in tags && RU_IP_TAG in tags
    }

    private fun isInfrastructureRule(rule: JsonObject): Boolean =
        rule.string("action") == "hijack-dns" ||
            rule.string("outbound")?.startsWith("zapret-") == true &&
            strings(rule["domain"]).contains(HEALTH_PROBE_HOST)

    private fun isGeneratedRoutingRule(rule: JsonObject): Boolean =
        isRuRule(rule) ||
            strings(rule["rule_set"]).any { it.startsWith(MANAGED_PREFIX) } ||
            (rule["ip_is_private"] as? JsonPrimitive)?.contentOrNull == "true"

    private fun isManagedRuleSetTag(tag: String): Boolean =
        tag.startsWith(MANAGED_PREFIX) || tag == RU_DOMAINS_TAG || tag == RU_IP_TAG ||
            tag == SELECTED_SITES_MARKER || tag == CUSTOM_MARKER

    private fun isReject(rule: JsonObject): Boolean = rule.string("action") == "reject"

    private fun isManagedDnsRule(element: JsonElement): Boolean {
        val rule = element as? JsonObject ?: return false
        return rule.string("action") == "reject" &&
            strings(rule["rule_set"]).any { it.startsWith(MANAGED_PREFIX) }
    }

    private fun strings(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }

    private fun summary(preset: RoutingPreset, manual: Int, blocked: Int): String =
        "${preset.detail}. Пользовательских правил: $manual; блокирующих: $blocked. " +
            "Остальные приложения: напрямую, вне VPN."

    private fun managedDiff(before: JsonObject, after: JsonObject): String {
        val old = managedLines(before)
        val new = managedLines(after)
        val added = new - old
        val removed = old - new
        if (added.isEmpty() && removed.isEmpty()) return "Управляемые zapret-* объекты не изменены."
        return buildString {
            if (added.isNotEmpty()) append("Добавлено: ").append(added.joinToString("; "))
            if (added.isNotEmpty() && removed.isNotEmpty()) append("\n")
            if (removed.isNotEmpty()) append("Удалено: ").append(removed.joinToString("; "))
        }
    }

    private fun managedLines(root: JsonObject): Set<String> {
        val route = root["route"] as? JsonObject
        val sets = route?.let(::ruleSets).orEmpty().mapNotNull { set ->
            set.string("tag")?.takeIf { it.startsWith("zapret-") }?.let { "rule-set $it" }
        }
        val routeRules = (route?.get("rules") as? JsonArray).orEmpty().mapNotNull { element ->
            val rule = element as? JsonObject ?: return@mapNotNull null
            val tags = strings(rule["rule_set"]).filter { it.startsWith("zapret-") }
            tags.takeIf(List<String>::isNotEmpty)?.let {
                "route ${rule.string("action")}:${rule.routeOutbound().orEmpty()} ← ${it.joinToString()}"
            }
        }
        val dnsRules = (((root["dns"] as? JsonObject)?.get("rules") as? JsonArray).orEmpty()).mapNotNull { element ->
            val rule = element as? JsonObject ?: return@mapNotNull null
            val tags = strings(rule["rule_set"]).filter { it.startsWith("zapret-") }
            tags.takeIf(List<String>::isNotEmpty)?.let { "dns ${rule.string("action")} ← ${it.joinToString()}" }
        }
        return (sets + routeRules + dnsRules).toSortedSet()
    }

    private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(5)
        .joinToString("") { "%02x".format(it) }

    private const val MANAGED_PREFIX = "zapret-user-"
    private const val MANAGED_DIRECT_TAG = "zapret-direct"
    private const val RU_DOMAINS_TAG = "zapret-ru-domains"
    private const val RU_IP_TAG = "zapret-ru-ip"
    private const val SELECTED_SITES_MARKER = "zapret-preset-selected-sites"
    private const val CUSTOM_MARKER = "zapret-preset-custom"
    private const val HEALTH_PROBE_HOST = "connectivitycheck.gstatic.com"
    private val DOMAIN_MATCH_TYPES = setOf(
        RoutingMatchType.Domain,
        RoutingMatchType.DomainSuffix,
        RoutingMatchType.DomainRuleSet,
    )
    private val SIMPLE_RULE_FIELDS = setOf("rule_set", "ip_version", "action", "outbound", "server")
}
