package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.config.PlainTransportPolicy
import io.github.zapretkvn.android.config.TlsVerificationPolicy
import io.github.zapretkvn.android.network.probes.ServerLatencyFingerprint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A stored profile as the slot pool sees it: its id, its name for the user, its sing-box tree. */
internal data class ProfileServers(val profileId: String, val profileName: String, val root: JsonObject)

/**
 * A server of another profile that a slot may use (owner decision 2026-11-14: a slot's pool is every server the user
 * has, not only the active profile's). [runtimeTag] is the tag it gets in the runtime config; it is the original tag
 * plus `@<first 8 digits of the profile id>`, so it never collides with a server of the active profile and the log and
 * speed history keep the servers of different profiles apart.
 */
internal data class ForeignServer(
    val ref: ServerRef,
    val profileName: String,
    val runtimeTag: String,
    /** The outbound with its tag already replaced by [runtimeTag]. */
    val outbound: JsonObject,
)

/**
 * Picks the servers of the other profiles that join the slot pool. Pure: it reads trees and returns trees; the session
 * start decides which profiles to read and appends the outbounds to the runtime config (never to the main selector and
 * never to a stored profile).
 *
 * Only plain servers join: one protocol outbound with an address, no `detour` to another outbound of its profile, and
 * the same transport rules the runtime applies to the active profile (no plain VLESS to a public address, no disabled
 * certificate check without a pin). A server whose address and protocol the active profile already has joins once, as
 * the active profile's. The count is capped so a user with many profiles does not make the core start hundreds of
 * outbounds.
 */
internal object ForeignServers {
    const val MAX_SERVERS = 64

    fun runtimeTag(tag: String, profileId: String): String = "$tag@${profileId.take(8)}"

    fun collect(active: ProfileServers, others: List<ProfileServers>): List<ForeignServer> {
        val seen = serverOutbounds(active.root).mapNotNullTo(mutableSetOf(), ::fingerprint)
        val usedTags = allTags(active.root).toMutableSet()
        val result = mutableListOf<ForeignServer>()
        for (profile in others.filter { it.profileId != active.profileId }.sortedBy { it.profileId }) {
            val managed = SlotRoutes.managedServers(profile.root) ?: continue
            val byTag = serverOutbounds(profile.root).associateBy { tagOf(it) }
            for (tag in managed.tags) {
                if (result.size >= MAX_SERVERS) return result
                val outbound = byTag[tag] ?: continue
                if ("detour" in outbound) continue
                if (PlainTransportPolicy.violates(outbound) || TlsVerificationPolicy.violates(outbound)) continue
                val print = fingerprint(outbound) ?: continue
                if (!seen.add(print)) continue
                var runtime = runtimeTag(tag, profile.profileId)
                while (runtime in usedTags) runtime += "'"
                usedTags += runtime
                result += ForeignServer(
                    ref = ServerRef(profile.profileId, tag),
                    profileName = profile.profileName,
                    runtimeTag = runtime,
                    outbound = JsonObject(outbound + ("tag" to JsonPrimitive(runtime))),
                )
            }
        }
        return result
    }

    /**
     * The pins as the runtime knows them (slot id to runtime tag). A pin on the active profile's server is that tag; a
     * pin on a server of another profile is the runtime tag it got this session; a pin whose server is gone (profile
     * deleted, server removed) is dropped, so the slot is automatic again; an older unqualified pin means the active
     * profile.
     */
    fun runtimePins(pins: Map<String, String>, activeProfileId: String, foreign: List<ForeignServer>): Map<String, String> {
        val byRef = foreign.associateBy { it.ref }
        return pins.mapNotNull { (slot, stored) ->
            val ref = ServerRef.parse(stored)
            val runtime = when {
                ref == null -> stored
                ref.profileId == activeProfileId -> ref.tag
                else -> byRef[ref]?.runtimeTag
            }
            runtime?.let { slot to it }
        }.toMap()
    }

    /** The stored form of the server with [runtimeTag]: its reference, active or foreign. */
    fun refOf(runtimeTag: String, activeProfileId: String, foreign: List<ForeignServer>): ServerRef =
        foreign.firstOrNull { it.runtimeTag == runtimeTag }?.ref ?: ServerRef(activeProfileId, runtimeTag)

    private fun serverOutbounds(root: JsonObject): List<JsonObject> =
        (root["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull in SLOT_SERVER_TYPES }

    private fun allTags(root: JsonObject): List<String> =
        ((root["outbounds"] as? JsonArray).orEmpty() + (root["endpoints"] as? JsonArray).orEmpty())
            .mapNotNull { (it as? JsonObject)?.let(::tagOf) }

    private fun tagOf(outbound: JsonObject): String? = (outbound["tag"] as? JsonPrimitive)?.contentOrNull

    private fun fingerprint(outbound: JsonObject): String? {
        val type = (outbound["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        val host = (outbound["server"] as? JsonPrimitive)?.contentOrNull ?: return null
        val port = (outbound["server_port"] as? JsonPrimitive)?.intOrNull ?: return null
        return ServerLatencyFingerprint.of(type, "$host:$port")
    }
}
