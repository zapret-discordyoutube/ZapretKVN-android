package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.config.ConfigAnalyzer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One service slot as the config builder needs it (docs/SERVICE_SLOTS.md, owner decision 2026-11-14): its own managed
 * selector over the profile's servers plus the route rules that send the slot's traffic to that selector. The
 * decisions (which apps, which server) are made in `slots/ServiceSlots.kt`; this only turns them into sing-box JSON.
 *
 * [members] are server outbound tags of the managed selector (the pool of the slot); [defaultMember] is the server the
 * slot starts on. sing-box ANDs the fields of one rule, so apps, names and addresses become separate rules.
 */
data class SlotRouteSpec(
    val slotId: String,
    val members: List<String>,
    val defaultMember: String,
    val packages: List<String> = emptyList(),
    val domainSuffixes: List<String> = emptyList(),
    val cidrs: List<String> = emptyList(),
) {
    val selectorTag: String get() = SlotSelectors.tag(slotId)
}

/** DNS servers of the slots: tags only, the JSON is built where the node-DNS conventions live (RuntimeConfigBuilder). */
object SlotDns {
    const val PREFIX = "zapret-slot-dns-"

    /** The server the DNS rules of a slot point at (a parallel UDP and TCP race, like the main node DNS). */
    fun tag(slotId: String): String = PREFIX + slotId

    fun udpTag(slotId: String): String = tag(slotId) + "-udp"

    fun tcpTag(slotId: String): String = tag(slotId) + "-tcp"

    fun isSlotDnsServer(tag: String?): Boolean = tag != null && tag.startsWith(PREFIX)
}

object SlotSelectors {
    const val PREFIX = "zapret-slot-"

    fun tag(slotId: String): String = PREFIX + slotId

    fun isSlotSelector(tag: String?): Boolean = tag != null && tag.startsWith(PREFIX)
}

/** The servers of the managed selector (profile order) and the one it starts on. */
internal data class ManagedServers(val tags: List<String>, val selected: String?)

/** Builds the slot selectors and slot route rules. Pure JSON in, JSON out. */
internal object SlotRoutes {
    /** Members and default of the managed `zapret-proxy` selector; null for a profile that has none. */
    fun managedServers(root: JsonObject?): ManagedServers? {
        val outbounds = root?.get("outbounds") as? JsonArray ?: return null
        val selector = outbounds.mapNotNull { it as? JsonObject }.firstOrNull {
            (it["type"] as? JsonPrimitive)?.content == "selector" &&
                (it["tag"] as? JsonPrimitive)?.content == ConfigAnalyzer.MANAGED_SELECTOR_TAG
        } ?: return null
        val tags = (selector["outbounds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        return ManagedServers(tags, (selector["default"] as? JsonPrimitive)?.content)
    }

    /**
     * Specs whose members all exist in [availableTags], with unknown members dropped and the default repaired.
     * A slot left without a member is dropped: its traffic then falls through to the main selector.
     */
    fun usable(specs: List<SlotRouteSpec>, availableTags: Set<String>): List<SlotRouteSpec> =
        specs.mapNotNull { spec ->
            val members = spec.members.filter { it in availableTags }.distinct()
            if (members.isEmpty()) return@mapNotNull null
            spec.copy(
                members = members,
                defaultMember = spec.defaultMember.takeIf { it in members } ?: members.first(),
            )
        }.distinctBy { it.slotId }

    fun selector(spec: SlotRouteSpec): JsonObject = buildJsonObject {
        put("type", "selector")
        put("tag", spec.selectorTag)
        put("outbounds", JsonArray(spec.members.map(::JsonPrimitive)))
        put("default", spec.defaultMember)
        put("interrupt_exist_connections", true)
    }

    /**
     * Route rules in the router's order (apps, then names, then addresses): an app that belongs to a slot wins over
     * the domain it connects to. They go before the forced-proxy rule and before user rules, after QUIC reject.
     */
    fun rules(specs: List<SlotRouteSpec>): List<JsonObject> = buildList {
        specs.filter { it.packages.isNotEmpty() }.forEach { add(rule(it, "package_name", it.packages)) }
        specs.filter { it.domainSuffixes.isNotEmpty() }.forEach { add(rule(it, "domain_suffix", it.domainSuffixes)) }
        specs.filter { it.cidrs.isNotEmpty() }.forEach { add(rule(it, "ip_cidr", it.cidrs)) }
    }

    /**
     * DNS rules of the slots, in the router's order (apps, then names). With separate servers a slot's names must be
     * resolved by the node the slot leaves through: the node's DNS picks the cache node (a googlevideo cluster) for its
     * own network, and an address chosen for another node's network can be unreachable from the slot's node (field
     * 2026-11-14: `connect error: timeout` to a cache address that the main node's DNS had handed out, while other
     * clusters worked at 245 KB/s on the same slot server). The answers are not cached by the core: the slot's server
     * can change, and a cached answer from the previous node would bring the mismatch back.
     */
    fun dnsRules(specs: List<SlotRouteSpec>, ipv4Only: Boolean): List<JsonObject> = buildList {
        specs.filter { it.packages.isNotEmpty() }.forEach { add(dnsRule(it, "package_name", it.packages, ipv4Only)) }
        specs.filter { it.domainSuffixes.isNotEmpty() }.forEach { add(dnsRule(it, "domain_suffix", it.domainSuffixes, ipv4Only)) }
    }

    private fun dnsRule(spec: SlotRouteSpec, field: String, values: List<String>, ipv4Only: Boolean): JsonObject =
        buildJsonObject {
            put(field, JsonArray(values.sorted().map(::JsonPrimitive)))
            put("action", "route")
            put("server", SlotDns.tag(spec.slotId))
            put("disable_cache", true)
            if (ipv4Only) put("strategy", "ipv4_only")
        }

    private fun rule(spec: SlotRouteSpec, field: String, values: List<String>): JsonObject = buildJsonObject {
        put(field, JsonArray(values.sorted().map(::JsonPrimitive)))
        put("action", "route")
        put("outbound", spec.selectorTag)
    }
}
