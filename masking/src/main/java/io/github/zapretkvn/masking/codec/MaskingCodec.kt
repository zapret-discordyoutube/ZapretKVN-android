package io.github.zapretkvn.masking.codec

import io.github.zapretkvn.masking.merge.MaskingSettings
import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.MaskingOverride
import io.github.zapretkvn.masking.model.MuxCool
import io.github.zapretkvn.masking.model.SingboxFragment
import io.github.zapretkvn.masking.model.Span
import io.github.zapretkvn.masking.model.WireGuardMasking
import io.github.zapretkvn.masking.model.XrayFragment
import io.github.zapretkvn.masking.model.XrayShaping
import io.github.zapretkvn.masking.model.Xmux
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Storage and exchange format of masking settings, the same on Android and Windows
 * (`xray_fluent/masking/codec.py`):
 *
 * ```
 * {"schema":1, "enabled":false, "global":{...masking...},
 *  "overrides":{"<server key>":"off" | {"custom":{...masking...}}}}
 * ```
 *
 * Reading is tolerant: a malformed field reads as absent (never a crash, never an invented
 * value). Keys this build does not know are carried over by [encode] from the previously
 * stored JSON, so settings written by a newer build survive a rollback by the updater.
 */
object MaskingCodec {
    private val json = Json { prettyPrint = false }

    fun decodeString(text: String?): MaskingSettings =
        text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }?.let(::decode)
            ?: MaskingSettings.OFF

    fun encodeString(settings: MaskingSettings, previous: String? = null): String {
        val previousJson = previous?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        return json.encodeToString(JsonObject.serializer(), encode(settings, previousJson))
    }

    fun decode(root: JsonObject): MaskingSettings = MaskingSettings(
        enabled = root.bool("enabled") ?: false,
        global = (root["global"] as? JsonObject)?.let(::decodeMasking) ?: Masking.NONE,
        overrides = (root["overrides"] as? JsonObject)?.mapNotNull { (key, value) ->
            decodeOverride(value)?.let { key to it }
        }?.toMap().orEmpty(),
    )

    fun encode(settings: MaskingSettings, previous: JsonObject? = null): JsonObject {
        val known = buildJsonObject {
            put("schema", Masking.SCHEMA)
            put("enabled", settings.enabled)
            put("global", encodeMasking(settings.global))
            put(
                "overrides",
                JsonObject(
                    settings.overrides.filterValues { it != MaskingOverride.Inherit }.toSortedMap().mapValues { (_, value) ->
                        when (value) {
                            MaskingOverride.Off -> JsonPrimitive("off")
                            is MaskingOverride.Custom -> buildJsonObject { put("custom", encodeMasking(value.masking)) }
                            MaskingOverride.Inherit -> error("filtered above")
                        }
                    },
                ),
            )
        }
        val carried = previous?.filterKeys { it !in known }.orEmpty()
        return JsonObject(known + carried)
    }

    /** One masking set as JSON: the global section, a custom override, or an exported set. */
    fun encodeMasking(masking: Masking): JsonObject = buildJsonObject {
        masking.wireguard?.let { put("wireguard", encodeWireGuard(it)) }
        masking.xray?.let { put("xray", encodeXray(it)) }
        masking.singbox?.let { put("singbox", encodeSingbox(it)) }
    }

    fun decodeMasking(root: JsonObject): Masking = Masking(
        wireguard = (root["wireguard"] as? JsonObject)?.let(::decodeWireGuard),
        xray = (root["xray"] as? JsonObject)?.let(::decodeXray),
        singbox = (root["singbox"] as? JsonObject)?.let(::decodeSingbox),
    )

    private fun decodeOverride(value: JsonElement): MaskingOverride? = when {
        value is JsonPrimitive && value.contentOrNull == "off" -> MaskingOverride.Off
        value is JsonPrimitive && value.contentOrNull == "inherit" -> MaskingOverride.Inherit
        value is JsonObject && value["custom"] is JsonObject -> MaskingOverride.Custom(decodeMasking(value["custom"] as JsonObject))
        else -> null
    }

    private fun encodeWireGuard(section: WireGuardMasking) = buildJsonObject {
        put("enabled", section.enabled)
        section.jc?.let { put("jc", it) }
        section.jmin?.let { put("jmin", it) }
        section.jmax?.let { put("jmax", it) }
        WireGuardMasking.SIGNATURE_KEYS.forEach { key -> section.signatures[key]?.let { put(key, it) } }
    }

    private fun decodeWireGuard(root: JsonObject) = WireGuardMasking(
        enabled = root.bool("enabled") ?: true,
        jc = root.int("jc"),
        jmin = root.int("jmin"),
        jmax = root.int("jmax"),
        signatures = WireGuardMasking.SIGNATURE_KEYS.mapNotNull { key -> root.string(key)?.let { key to it } }.toMap(),
    )

    private fun encodeXray(section: XrayShaping) = buildJsonObject {
        put("enabled", section.enabled)
        section.mux?.let { mux ->
            put(
                "mux",
                buildJsonObject {
                    put("enabled", mux.enabled)
                    put("concurrency", mux.concurrency)
                    put("xudpConcurrency", mux.xudpConcurrency)
                    put("xudpProxyUDP443", mux.xudpProxyUDP443)
                },
            )
        }
        section.xmux?.let { xmux ->
            put(
                "xmux",
                buildJsonObject {
                    xmux.maxConcurrency?.let { put("maxConcurrency", it.toString()) }
                    xmux.maxConnections?.let { put("maxConnections", it.toString()) }
                    xmux.cMaxReuseTimes?.let { put("cMaxReuseTimes", it.toString()) }
                    xmux.hMaxRequestTimes?.let { put("hMaxRequestTimes", it.toString()) }
                    xmux.hMaxReusableSecs?.let { put("hMaxReusableSecs", it.toString()) }
                    xmux.hKeepAlivePeriod?.let { put("hKeepAlivePeriod", it) }
                },
            )
        }
        section.fragment?.let { fragment ->
            put(
                "fragment",
                buildJsonObject {
                    put("enabled", fragment.enabled)
                    put("packets", fragment.packets)
                    put("length", fragment.length.toString())
                    put("delay", fragment.delay.toString())
                    fragment.maxSplit?.let { put("maxSplit", it.toString()) }
                },
            )
        }
    }

    private fun decodeXray(root: JsonObject): XrayShaping {
        val defaults = MuxCool()
        val fragmentDefaults = XrayFragment()
        return XrayShaping(
            enabled = root.bool("enabled") ?: true,
            mux = (root["mux"] as? JsonObject)?.let { mux ->
                MuxCool(
                    enabled = mux.bool("enabled") ?: true,
                    concurrency = mux.int("concurrency") ?: defaults.concurrency,
                    xudpConcurrency = mux.int("xudpConcurrency") ?: defaults.xudpConcurrency,
                    xudpProxyUDP443 = mux.string("xudpProxyUDP443") ?: defaults.xudpProxyUDP443,
                )
            },
            xmux = (root["xmux"] as? JsonObject)?.let { xmux ->
                Xmux(
                    maxConcurrency = xmux.span("maxConcurrency"),
                    maxConnections = xmux.span("maxConnections"),
                    cMaxReuseTimes = xmux.span("cMaxReuseTimes"),
                    hMaxRequestTimes = xmux.span("hMaxRequestTimes"),
                    hMaxReusableSecs = xmux.span("hMaxReusableSecs"),
                    hKeepAlivePeriod = (xmux["hKeepAlivePeriod"] as? JsonPrimitive)?.longOrNull,
                )
            },
            fragment = (root["fragment"] as? JsonObject)?.let { fragment ->
                XrayFragment(
                    enabled = fragment.bool("enabled") ?: true,
                    packets = fragment.string("packets") ?: fragmentDefaults.packets,
                    length = fragment.span("length") ?: fragmentDefaults.length,
                    delay = fragment.span("delay") ?: fragmentDefaults.delay,
                    maxSplit = fragment.span("maxSplit"),
                )
            },
        )
    }

    private fun encodeSingbox(section: SingboxFragment) = buildJsonObject {
        put("enabled", section.enabled)
        put("fragment", section.fragment)
        put("recordFragment", section.recordFragment)
        section.fallbackDelayMs?.let { put("fallbackDelayMs", it) }
    }

    private fun decodeSingbox(root: JsonObject) = SingboxFragment(
        enabled = root.bool("enabled") ?: true,
        fragment = root.bool("fragment") ?: true,
        recordFragment = root.bool("recordFragment") ?: false,
        fallbackDelayMs = root.int("fallbackDelayMs"),
    )

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = primitive(key)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf(JsonPrimitive::isString)?.content

    /** A range field: a JSON number or a `"A-B"` string, like Xray's own Int32Range. */
    private fun JsonObject.span(key: String): Span? {
        val value = primitive(key) ?: return null
        return if (value.isString) Span.parse(value.content) else value.intOrNull?.takeIf { it >= 0 }?.let(Span::of)
    }
}
