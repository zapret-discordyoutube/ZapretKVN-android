package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Статическая подсказка, куда правила профиля направят домены сервиса. Это
 * догадка по точным правилам `domain` / `domain_suffix` / `domain_keyword`
 * (в том числе во встроенных rule-set) и `route.final`. Любое правило, чьё
 * совпадение по тексту профиля не определить (rule-set из файла, geosite,
 * сопоставление по IP и т. п.), стоящее раньше найденного, даёт Unknown.
 */
internal object ServiceRouteHint {
    fun of(profileJson: String?, definition: ServiceDefinition): RouteHint {
        val root = profileJson?.let { runCatching { JsonConfig.parse(it) as? JsonObject }.getOrNull() }
            ?: return RouteHint.Unknown
        val hints = definition.endpoints.map { hintForHost(root, it.host) }.toSet()
        return hints.singleOrNull() ?: RouteHint.Unknown
    }

    private fun hintForHost(root: JsonObject, host: String): RouteHint {
        val route = root["route"] as? JsonObject ?: return RouteHint.Unknown
        val outbounds = (root["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val inlineSets = (route["rule_set"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "inline" }
            .associateBy { it.string("tag").orEmpty() }
        val rules = (route["rules"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        for (rule in rules) {
            when (val match = matches(rule, host, inlineSets)) {
                Match.No -> continue
                Match.Unsure -> return RouteHint.Unknown
                Match.Yes -> return actionHint(rule, outbounds) ?: continue
            }
        }
        return outboundHint(route.string("final"), outbounds)
    }

    private enum class Match { Yes, No, Unsure }

    private val SERVICE_KEYS = setOf("action", "outbound", "server", "method", "no_drop", "invert")
    private val DOMAIN_KEYS = setOf("domain", "domain_suffix", "domain_keyword")

    private fun matches(rule: JsonObject, host: String, inlineSets: Map<String, JsonObject>): Match {
        val action = rule.string("action") ?: "route"
        // Служебные действия не выбирают исходящий: sniff, DNS и т. п. домен не направляют.
        if (action != "route" && action != "reject") return Match.No
        if ((rule["invert"] as? JsonPrimitive)?.contentOrNull == "true") return Match.Unsure
        val keys = rule.keys - SERVICE_KEYS
        if (keys.isEmpty()) return Match.Yes // правило без условий ловит всё
        // ip_is_private не совпадёт с публичным доменом сервиса.
        val conditions = keys - "ip_is_private"
        if (conditions.isEmpty()) return Match.No
        var sure = false
        for (key in conditions) {
            when {
                key in DOMAIN_KEYS -> if (!domainMatches(key, rule[key], host)) return Match.No else sure = true
                key == "rule_set" -> {
                    val tags = strings(rule[key])
                    val sets = tags.map { inlineSets[it] ?: return Match.Unsure }
                    val hit = sets.any { set -> inlineMatches(set, host) }
                    if (!hit) {
                        // Инлайн-набор, не совпавший с хостом, не совпадёт и вовсе.
                        if (sets.all(::inlineIsPureDomain)) return Match.No
                        return Match.Unsure
                    }
                    sure = true
                }
                else -> return Match.Unsure
            }
        }
        return if (sure) Match.Yes else Match.Unsure
    }

    private fun inlineRules(set: JsonObject): List<JsonObject> =
        (set["rules"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun inlineIsPureDomain(set: JsonObject): Boolean =
        inlineRules(set).all { rule -> rule.keys.all { it in DOMAIN_KEYS || it == "type" } }

    private fun inlineMatches(set: JsonObject, host: String): Boolean = inlineRules(set).any { rule ->
        val keys = rule.keys.filter { it in DOMAIN_KEYS }
        keys.isNotEmpty() && keys.all { domainMatches(it, rule[it], host) }
    }

    private fun domainMatches(key: String, value: JsonElement?, host: String): Boolean {
        val values = strings(value).map { it.lowercase() }
        val name = host.lowercase()
        return values.any { v ->
            when (key) {
                "domain" -> name == v
                "domain_suffix" -> name == v.removePrefix(".") || name.endsWith(if (v.startsWith(".")) v else ".$v")
                else -> v in name
            }
        }
    }

    private fun actionHint(rule: JsonObject, outbounds: List<JsonObject>): RouteHint? {
        if ((rule.string("action") ?: "route") == "reject") return RouteHint.Unknown
        return outboundHint(rule.string("outbound"), outbounds)
    }

    private fun outboundHint(tag: String?, outbounds: List<JsonObject>): RouteHint {
        if (tag == null) return RouteHint.Unknown
        val outbound = outbounds.firstOrNull { it.string("tag") == tag } ?: return RouteHint.Unknown
        return when (outbound.string("type")) {
            "direct" -> RouteHint.Direct
            "block", "dns", null -> RouteHint.Unknown
            else -> RouteHint.Proxy
        }
    }

    private fun strings(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }
}
