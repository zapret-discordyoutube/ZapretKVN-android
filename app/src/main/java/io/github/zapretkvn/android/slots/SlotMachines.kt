package io.github.zapretkvn.android.slots

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which machine each slot server is (docs/SERVICE_SLOTS.md, "one machine is one server"): tag to host keys, the
 * server name or address of the outbound (first peer of an endpoint) plus the addresses remembered for that name
 * ([addressesOf], the app's DNS answers of earlier sessions). Two tags whose keys meet are entries of one machine.
 * Pure: the session start passes the memory in once, so the plan stays fixed for the session.
 */
internal object SlotMachines {
    fun of(
        root: JsonObject?,
        foreign: List<ForeignServer>,
        addressesOf: (String) -> Set<String>,
    ): Map<String, Set<String>> {
        val hosts = LinkedHashMap<String, String>()
        fun add(tag: String?, host: String?) {
            val name = host?.trim()?.trimEnd('.')?.lowercase()?.removePrefix("[")?.removeSuffix("]")
            if (tag != null && !name.isNullOrEmpty()) hosts.putIfAbsent(tag, name)
        }
        (root?.get("outbounds") as? JsonArray)?.forEach { element ->
            val outbound = element as? JsonObject ?: return@forEach
            add(outbound.text("tag"), outbound.text("server"))
        }
        (root?.get("endpoints") as? JsonArray)?.forEach { element ->
            val endpoint = element as? JsonObject ?: return@forEach
            val peer = (endpoint["peers"] as? JsonArray)?.firstOrNull() as? JsonObject
            add(endpoint.text("tag"), peer?.text("address"))
        }
        foreign.forEach { add(it.runtimeTag, it.outbound.text("server")) }
        return hosts.mapValues { (_, host) -> setOf(host) + addressesOf(host) }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
