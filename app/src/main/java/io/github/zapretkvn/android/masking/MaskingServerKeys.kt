package io.github.zapretkvn.android.masking

import io.github.zapretkvn.android.config.MaskingReport
import io.github.zapretkvn.android.slots.ForeignServer
import io.github.zapretkvn.android.slots.ServerRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Glue between a session and the `:masking` merge: which stored key each runtime server has,
 * and the one log line that shows masking acted. The key is [ServerRef.encode], the same
 * identity slot pins use, so a per-server choice survives a subscription refresh.
 */
internal object MaskingServerKeys {
    fun of(profileId: String, root: JsonObject?, foreign: List<ForeignServer>): Map<String, String> {
        val own = listOf("outbounds", "endpoints").flatMap { section ->
            (root?.get(section) as? JsonArray).orEmpty().mapNotNull { entry ->
                ((entry as? JsonObject)?.get("tag") as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            }
        }.associateWith { tag -> ServerRef(profileId, tag).encode() }
        return own + foreign.associate { it.runtimeTag to it.ref.encode() }
    }

    /** One compact line per session: nothing when masking is off and did nothing. */
    fun logLine(enabled: Boolean, report: MaskingReport): String? {
        if (!enabled && report.changed == 0) return null
        val notes = report.notes.values.flatten().groupingBy { it }.eachCount()
            .entries.joinToString("; ") { (note, count) -> if (count > 1) "$note (×$count)" else note }
        return "маскировка: ${if (enabled) "включена" else "выключена"}, изменено серверов: ${report.changed}" +
            if (notes.isEmpty()) "" else "; $notes"
    }
}
