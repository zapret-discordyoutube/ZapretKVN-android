package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.engines.failover.FailoverTarget
import io.github.zapretkvn.android.engines.failover.LatencyHint
import io.github.zapretkvn.android.engines.hysteria.HysteriaCapabilityClassifier
import io.github.zapretkvn.android.engines.hysteria.isFailoverEligible
import io.github.zapretkvn.android.network.probes.ServerLatencyStore
import io.github.zapretkvn.android.network.probes.hint
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Строит план замены для selector-группы, в которой работает сервер.
 *
 * sing-box только маршрутизирует: любой настоящий прокси-протокол — участник
 * группы, который можно выбрать командой селектора. Не прокси
 * (direct/block/dns/вложенные группы) кандидатами не бывают. Hysteria
 * пригодна только с корректным URI нативного runtime.
 */
internal object FailoverPlanner {
    private val NON_PROXY_TYPES = setOf("direct", "block", "dns", "selector", "urltest")
    private val GROUP_TYPES = setOf("selector", "urltest")
    private val MAINTENANCE_MARKERS = setOf("maintenance", "техработы", "обслуживание")

    /**
     * @param runtimeSelections фактический выбор групп по сообщениям ядра.
     * @param serverId сервер, о котором речь; null — сервер основной группы.
     */
    fun plan(
        rawJson: String,
        runtimeSelections: Map<String, String>,
        primaryGroupTag: String?,
        serverId: String? = null,
    ): FailoverPlan? {
        val config = runCatching { ParsedConfig.parse(rawJson) }.getOrNull() ?: return null
        return plan(config, runtimeSelections, primaryGroupTag, serverId)
    }

    /** Тот же план по уже разобранному профилю: без повторного разбора JSON. */
    fun plan(
        config: ParsedConfig,
        runtimeSelections: Map<String, String>,
        primaryGroupTag: String?,
        serverId: String? = null,
    ): FailoverPlan? {
        val root = config.root ?: return null
        val byTag = (root["outbounds"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { outbound -> outbound.text("tag")?.let { it to outbound } }
            .toMap()
        fun typeOf(tag: String?): String? = tag?.let { byTag[it]?.text("type")?.lowercase(Locale.ROOT) }
        fun isProxy(tag: String?): Boolean = typeOf(tag)?.let { it !in NON_PROXY_TYPES } == true
        fun currentOf(tag: String, default: String?): String? = runtimeSelections[tag] ?: default

        val groups = ConfigAnalyzer.selectorGroups(config)
        val group = if (serverId != null) {
            groups.firstOrNull { it.tag == primaryGroupTag && currentOf(it.tag, it.default) == serverId }
                ?: groups.firstOrNull { currentOf(it.tag, it.default) == serverId }
        } else {
            groups.firstOrNull { it.tag == primaryGroupTag && isProxy(currentOf(it.tag, it.default)) }
                ?: groups.firstOrNull { isProxy(currentOf(it.tag, it.default)) }
        } ?: return null
        val current = currentOf(group.tag, group.default) ?: return null
        if (!isProxy(current)) return null
        val targets = group.outbounds.mapNotNull { tag ->
            val outbound = byTag[tag] ?: return@mapNotNull null
            val type = typeOf(tag) ?: return@mapNotNull null
            if (type in NON_PROXY_TYPES) return@mapNotNull null
            val valid = if (type == PathSupervisor.HYSTERIA_TYPE) {
                HysteriaCapabilityClassifier.classify(outbound.text("uri").orEmpty()).isFailoverEligible()
            } else {
                true
            }
            val maintenance = (listOf("name", "remarks", "group").mapNotNull { key -> outbound.text(key) } + tag)
                .any { value -> value.lowercase(Locale.ROOT) in MAINTENANCE_MARKERS }
            FailoverTarget(tag, valid = valid, maintenance = maintenance)
        }
        if (targets.firstOrNull { it.id == current }?.valid != true) return null
        return FailoverPlan(group.tag, current, checkNotNull(typeOf(current)), targets)
    }

    /**
     * The server a connection report is about (docs/CLIENT_REPORTS.md), read the way sing-box routes default traffic:
     * 1. the failover plan's current server;
     * 2. the primary group, followed down to a server;
     * 3. the profile's default exit — `route.final`, or the first outbound when it is not set — followed down;
     * 4. the first group (top-level groups first) that leads to a server;
     * 5. a profile without any group: its only server.
     * Following a group means: the core's runtime selection, else a selector's `default`, else its first member (what
     * sing-box itself uses; a urltest is a selector at runtime, QuietOutbounds), through nested groups. Servers are
     * proxy outbounds and `endpoints` (WireGuard/AWG). Null when the chain ends on direct/block/dns or loops.
     * Field check #2 2026-11-22: with only selectors-with-default and outbounds, 0.6.47 still sent `start:VPN-200`
     * app errors from 23 installs, and Android sent no WireGuard/AWG report at all.
     */
    fun reportedServer(config: ParsedConfig, runtimeSelections: Map<String, String>, primaryGroupTag: String?): Pair<String, String>? {
        plan(config, runtimeSelections, primaryGroupTag)?.let { return it.currentId to it.currentType }
        val root = config.root ?: return null
        val outbounds = (root["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val endpoints = (root["endpoints"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val byTag = LinkedHashMap<String, JsonObject>()
        (outbounds + endpoints).forEach { item -> item.text("tag")?.let { tag -> byTag.putIfAbsent(tag, item) } }
        fun typeOf(tag: String): String? = byTag[tag]?.text("type")?.lowercase(Locale.ROOT)
        fun membersOf(tag: String): List<String> =
            (byTag[tag]?.get("outbounds") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        fun isGroup(tag: String) = typeOf(tag) in GROUP_TYPES

        fun resolve(start: String?): Pair<String, String>? {
            var tag = start ?: return null
            val seen = HashSet<String>()
            while (seen.add(tag)) {
                val type = typeOf(tag) ?: return null
                if (type !in GROUP_TYPES) return if (type in NON_PROXY_TYPES) null else tag to type
                val default = if (type == "selector") byTag[tag]?.text("default") else null
                tag = runtimeSelections[tag] ?: default ?: membersOf(tag).firstOrNull() ?: return null
            }
            return null
        }

        primaryGroupTag?.let(::resolve)?.let { return it }
        val final = (root["route"] as? JsonObject)?.text("final") ?: outbounds.firstNotNullOfOrNull { it.text("tag") }
        resolve(final)?.let { return it }
        val groups = byTag.keys.filter(::isGroup)
        if (groups.isNotEmpty()) {
            val nested = groups.flatMap(::membersOf).toSet()
            return (groups.filter { it !in nested } + groups.filter { it in nested }).firstNotNullOfOrNull(::resolve)
        }
        return byTag.keys.mapNotNull { tag -> typeOf(tag)?.takeIf { it !in NON_PROXY_TYPES }?.let { tag to it } }.singleOrNull()
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** Сохранённые пинги серверов профиля — для ранжирования кандидатов замены. */
internal fun ServerLatencyStore.failoverHints(profileId: String): Map<String, LatencyHint> =
    forProfile(profileId)
        .mapNotNull { (tag, entry) -> entry.hint()?.let { tag to it } }
        .toMap()
