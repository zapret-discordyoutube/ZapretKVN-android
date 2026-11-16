package io.github.zapretkvn.masking

import io.github.zapretkvn.masking.merge.MaskingMerge
import io.github.zapretkvn.masking.merge.MaskingSettings
import io.github.zapretkvn.masking.model.ServerShape
import io.github.zapretkvn.masking.singbox.SingboxFragmentRules
import io.github.zapretkvn.masking.wireguard.WireGuardMaskingRules
import io.github.zapretkvn.masking.xray.XrayShapingRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/**
 * Applies the effective masking to the runtime copy of a profile's outbounds and endpoints.
 * The stored profile is never edited: masking is re-applied on every connect from the
 * current settings, so turning it off needs no re-import.
 *
 * Idempotent: a runtime copy fed back in gives the same result. The Xray block is one
 * app-owned key, [XRAY_KEY], replaced or removed as a whole; the WireGuard fields are set,
 * never accumulated.
 */
object MaskingApplier {
    /**
     * The VLESS outbound key read by the Xray adapter (core patch 0016): an object with
     * `mux`, `xmux`, `finalmask`, merged by name into the Xray outbound it builds.
     */
    const val XRAY_KEY = "xray_masking"

    data class Applied(val entries: JsonArray, val notes: Map<String, List<String>>, val changed: Int)

    /** [serverKey] maps a runtime tag to the stable key overrides are stored under. */
    fun apply(entries: JsonArray, settings: MaskingSettings, serverKey: (tag: String) -> String?): Applied {
        val notes = linkedMapOf<String, List<String>>()
        var changed = 0
        val result = entries.map { element ->
            val entry = element as? JsonObject ?: return@map element
            val tag = (entry["tag"] as? JsonPrimitive)?.content
            val shape = shapeOf(entry) ?: return@map entry
            val effective = MaskingMerge.effective(settings, tag?.let(serverKey), shape)
            val (updated, entryNotes) = applyOne(entry, effective, shape)
            if (tag != null && entryNotes.isNotEmpty()) notes[tag] = entryNotes
            if (updated != entry) changed++
            updated
        }
        return Applied(JsonArray(result), notes, changed)
    }

    private fun applyOne(entry: JsonObject, effective: MaskingMerge.Effective, shape: ServerShape): Pair<JsonObject, List<String>> {
        val notes = effective.notes.toMutableList()
        var updated = entry
        when (shape.core) {
            ServerShape.Core.Xray -> {
                val emission = effective.masking.xray?.let { XrayShapingRules.emit(it, shape) }
                emission?.notes?.let(notes::addAll)
                updated = JsonObject(
                    if (emission?.json != null) entry + (XRAY_KEY to emission.json) else entry - XRAY_KEY,
                )
            }
            ServerShape.Core.Amnezia -> effective.masking.wireguard?.let { wireguard ->
                val amnezia = WireGuardMaskingRules.applyTo(entry["amnezia"] as? JsonObject, wireguard)
                updated = JsonObject(entry + ("amnezia" to amnezia))
            }
            ServerShape.Core.SingBox -> effective.masking.singbox?.let { fragment ->
                val emission = SingboxFragmentRules.emit(entry, fragment, shape)
                notes += emission.notes
                updated = emission.outbound
            }
            ServerShape.Core.Hysteria -> Unit
        }
        return updated to notes
    }

    /** The server's shape, or null for entries that are not servers (selector, direct, dns, ...). */
    fun shapeOf(entry: JsonObject): ServerShape? {
        val type = entry.string("type")?.lowercase() ?: return null
        return when (type) {
            // Owner decision 2026-11-16: the official Xray core owns VLESS, VMess and Trojan.
            "vless", "vmess", "trojan" -> xrayShape(entry, type)
            "wireguard" -> ServerShape(ServerShape.Core.Amnezia, type)
            "hysteria2" -> ServerShape(ServerShape.Core.Hysteria, type)
            in NOT_SERVERS -> null
            else -> {
                val tls = entry["tls"] as? JsonObject
                val security = when {
                    tls?.bool("enabled") != true -> "none"
                    (tls["reality"] as? JsonObject)?.bool("enabled") == true -> "reality"
                    else -> "tls"
                }
                ServerShape(ServerShape.Core.SingBox, type, security = security)
            }
        }
    }

    private fun xrayShape(entry: JsonObject, protocol: String): ServerShape {
        val uri = entry.string("uri")
        if (uri != null) {
            uriShape(uri, protocol)?.let { return it }
        }
        val tls = entry["tls"] as? JsonObject
        return ServerShape(
            core = ServerShape.Core.Xray,
            protocol = protocol,
            network = (entry["transport"] as? JsonObject)?.string("type")?.lowercase() ?: "tcp",
            security = when {
                tls?.bool("enabled") != true -> "none"
                (tls["reality"] as? JsonObject)?.bool("enabled") == true -> "reality"
                else -> "tls"
            },
            flow = entry.string("flow"),
        )
    }

    /** The shape the original share link describes; null when it cannot be read (then the stored fields are used). */
    private fun uriShape(uri: String, protocol: String): ServerShape? {
        if (protocol == "vmess") {
            val body = uri.substringAfter("vmess://", "").substringBefore('#').trim()
            val payload = runCatching {
                val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(body) }
                    .getOrElse { java.util.Base64.getUrlDecoder().decode(body) }
                kotlinx.serialization.json.Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
            }.getOrNull() ?: return null
            val tlsField = payload.string("tls")?.lowercase()
            return ServerShape(
                core = ServerShape.Core.Xray,
                protocol = "vmess",
                network = payload.string("net")?.lowercase()?.ifEmpty { null } ?: "tcp",
                security = if (tlsField == "tls" || tlsField == "reality") tlsField else "none",
            )
        }
        val query = runCatching { URI(uri).rawQuery }.getOrNull().orEmpty()
        val params = query.split('&').filter(String::isNotEmpty).associate { part ->
            decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
        }
        return ServerShape(
            core = ServerShape.Core.Xray,
            protocol = protocol,
            network = params["type"]?.lowercase()?.ifEmpty { null } ?: "tcp",
            // A Trojan link without `security` is TLS; a VLESS link without it is plain.
            security = params["security"]?.lowercase()?.ifEmpty { null } ?: if (protocol == "trojan") "tls" else "none",
            flow = params["flow"]?.ifEmpty { null },
        )
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private val NOT_SERVERS = setOf("selector", "urltest", "direct", "block", "dns", "tor")
}
