package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.engines.failover.FailoverTarget
import io.github.zapretkvn.android.engines.failover.LatencyHint
import io.github.zapretkvn.android.engines.hysteria.HysteriaCapabilityClassifier
import io.github.zapretkvn.android.engines.hysteria.isFailoverEligible
import io.github.zapretkvn.android.network.probes.ServerLatencyStore
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
        val root = runCatching { JsonConfig.parse(rawJson) }.getOrNull() as? JsonObject ?: return null
        val byTag = (root["outbounds"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { outbound -> outbound.text("tag")?.let { it to outbound } }
            .toMap()
        fun typeOf(tag: String?): String? = tag?.let { byTag[it]?.text("type")?.lowercase(Locale.ROOT) }
        fun isProxy(tag: String?): Boolean = typeOf(tag)?.let { it !in NON_PROXY_TYPES } == true
        fun currentOf(tag: String, default: String?): String? = runtimeSelections[tag] ?: default

        val groups = ConfigAnalyzer.selectorGroups(root)
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

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** Сохранённые пинги серверов профиля — для ранжирования кандидатов замены. */
internal fun ServerLatencyStore.failoverHints(profileId: String): Map<String, LatencyHint> =
    forProfile(profileId)
        .mapNotNull { (tag, entry) -> entry.hint()?.let { tag to it } }
        .toMap()
