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
     * The server a connection report is about (docs/CLIENT_REPORTS.md): the plan's current server; without a plan,
     * the proxy a selector group is on (a Hysteria line that is not failover-eligible has no plan but is still the
     * server tried); a profile without any group, its only proxy outbound. Null when the profile does not say which
     * server was tried (several proxies and no group). Field check 2026-11-21: such profiles' traffic failures went
     * only as app errors `start:VPN-200` (16 installs a day).
     */
    fun reportedServer(config: ParsedConfig, runtimeSelections: Map<String, String>, primaryGroupTag: String?): Pair<String, String>? {
        plan(config, runtimeSelections, primaryGroupTag)?.let { return it.currentId to it.currentType }
        val root = config.root ?: return null
        val types = (root["outbounds"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { outbound ->
                val tag = outbound.text("tag") ?: return@mapNotNull null
                val type = outbound.text("type")?.lowercase(Locale.ROOT) ?: return@mapNotNull null
                tag to type
            }
            .toMap()
        fun proxy(tag: String?): Pair<String, String>? =
            tag?.let { types[it] }?.takeIf { it !in NON_PROXY_TYPES }?.let { tag to it }
        val groups = ConfigAnalyzer.selectorGroups(config)
        if (groups.isNotEmpty()) {
            val ordered = groups.filter { it.tag == primaryGroupTag } + groups.filter { it.tag != primaryGroupTag }
            return ordered.firstNotNullOfOrNull { group -> proxy(runtimeSelections[group.tag] ?: group.default) }
        }
        return types.entries.filter { it.value !in NON_PROXY_TYPES }.singleOrNull()?.let { it.key to it.value }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** Сохранённые пинги серверов профиля — для ранжирования кандидатов замены. */
internal fun ServerLatencyStore.failoverHints(profileId: String): Map<String, LatencyHint> =
    forProfile(profileId)
        .mapNotNull { (tag, entry) -> entry.hint()?.let { tag to it } }
        .toMap()
