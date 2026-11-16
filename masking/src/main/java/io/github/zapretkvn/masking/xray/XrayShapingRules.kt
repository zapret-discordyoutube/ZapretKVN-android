package io.github.zapretkvn.masking.xray

import io.github.zapretkvn.masking.model.MuxCool
import io.github.zapretkvn.masking.model.Problem
import io.github.zapretkvn.masking.model.ServerShape
import io.github.zapretkvn.masking.model.Span
import io.github.zapretkvn.masking.model.XrayFragment
import io.github.zapretkvn.masking.model.XrayShaping
import io.github.zapretkvn.masking.model.Xmux
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Validation and emission of [XrayShaping] for the pinned xray-core
 * (v1.260327.1-0.20261109052709-676eaf66f8f0). Every limit below names the core file it
 * comes from; the emitted object is what the VLESS adapter merges into the Xray outbound
 * (Android core patch 0016, Windows sidecar config): `mux` on the outbound, `xmux` inside
 * the XHTTP settings, `finalmask` on the stream settings.
 */
object XrayShapingRules {
    /** Xray `MuxConfig.Concurrency` is an int16 (infra/conf/xray.go:112). */
    const val MAX_CONCURRENCY = 1024

    /** app/proxyman/outbound/handler.go: 0..128 all become 128. */
    const val CORE_MIN_EFFECTIVE_CONCURRENCY = 128

    data class Emission(val json: JsonObject?, val notes: List<String>)

    fun validate(shaping: XrayShaping): List<Problem> = buildList {
        shaping.mux?.let { addAll(validateMux(it)) }
        shaping.xmux?.let { addAll(validateXmux(it)) }
        shaping.fragment?.let { addAll(validateFragment(it)) }
    }

    private fun validateMux(mux: MuxCool): List<Problem> = buildList {
        if (mux.concurrency !in 1..MAX_CONCURRENCY) {
            add(Problem.error("mux.concurrency", "Потоков на соединение: от 1 до $MAX_CONCURRENCY."))
        } else if (mux.concurrency < CORE_MIN_EFFECTIVE_CONCURRENCY) {
            add(
                Problem.warning(
                    "mux.concurrency",
                    "Xray поднимает любое значение до $CORE_MIN_EFFECTIVE_CONCURRENCY: меньше задать нельзя, " +
                        "mux либо включён, либо выключен.",
                ),
            )
        }
        if (mux.xudpConcurrency !in -1..MAX_CONCURRENCY) {
            add(Problem.error("mux.xudpConcurrency", "XUDP: -1 (выключен), 0 (внутри mux) или до $MAX_CONCURRENCY."))
        }
        if (mux.xudpProxyUDP443 !in MuxCool.UDP443_MODES) {
            // infra/conf/xray.go:119 refuses any other value.
            add(Problem.error("mux.xudpProxyUDP443", "QUIC в mux: reject, allow или skip."))
        }
    }

    private fun validateXmux(xmux: Xmux): List<Problem> = buildList {
        // infra/conf/transport_method.go:459
        if ((xmux.maxConnections?.to ?: 0) > 0 && (xmux.maxConcurrency?.to ?: 0) > 0) {
            add(Problem.error("xmux.maxConnections", "maxConnections и maxConcurrency нельзя задать вместе: Xray не запустится."))
        }
        if (xmux.hKeepAlivePeriod != null && xmux.hKeepAlivePeriod < 0) {
            add(Problem.error("xmux.hKeepAlivePeriod", "Период keep-alive не может быть отрицательным."))
        }
    }

    private fun validateFragment(fragment: XrayFragment): List<Problem> = buildList {
        val packets = fragment.packets.trim().lowercase()
        if (packets != "tlshello") {
            val span = Span.parse(packets)
            // transport_finalmask.go FragmentMask.Build: "PacketsFrom can't be 0".
            if (span == null || span.from == 0) {
                add(Problem.error("fragment.packets", "Пакеты: tlshello или номера записей от 1, например 1-3."))
            }
        }
        // transport_finalmask.go: "last lengths entry min can't be 0".
        if (fragment.length.from == 0) add(Problem.error("fragment.length", "Длина куска должна быть больше 0."))
    }

    /** The object to merge into the Xray outbound of [shape], or null when nothing applies. */
    fun emit(shaping: XrayShaping, shape: ServerShape): Emission {
        if (!shaping.enabled || shape.core != ServerShape.Core.Xray) return Emission(null, emptyList())
        val notes = mutableListOf<String>()
        val json = buildJsonObject {
            shaping.mux?.takeIf(MuxCool::enabled)?.let { mux ->
                if (shape.isMultiplexedTransport) {
                    notes += "Mux не включён: ${shape.network} уже мультиплексирует потоки, для XHTTP есть xmux."
                } else {
                    put("mux", muxJson(mux, shape, notes))
                }
            }
            shaping.xmux?.let { xmux ->
                if (shape.isXhttp) {
                    put("xmux", xmuxJson(xmux))
                } else {
                    notes += "xmux не применён: сервер не XHTTP."
                }
            }
            shaping.fragment?.takeIf(XrayFragment::enabled)?.let { fragment ->
                if (shape.isTcpStream) {
                    put("finalmask", finalmaskJson(fragment))
                    if (shape.security == "reality") {
                        notes += "Фрагментация с REALITY: куски режет ядро ниже REALITY, проверьте подбором."
                    }
                } else {
                    notes += "Фрагментация не применена: ${shape.network} идёт не по TCP."
                }
            }
        }
        return Emission(json.takeIf { it.isNotEmpty() }, notes)
    }

    private fun muxJson(mux: MuxCool, shape: ServerShape, notes: MutableList<String>): JsonObject = buildJsonObject {
        put("enabled", true)
        if (shape.isVision) {
            // proxy/vless/outbound/outbound.go:259: with Vision the server breaks mux
            // connections that carry TCP, so only UDP (XUDP) is multiplexed.
            put("concurrency", -1)
            notes += "С XTLS Vision в mux идёт только UDP (XUDP): сервер рвёт TCP внутри mux."
        } else {
            put("concurrency", mux.concurrency.coerceIn(1, MAX_CONCURRENCY))
        }
        put("xudpConcurrency", mux.xudpConcurrency)
        put("xudpProxyUDP443", mux.xudpProxyUDP443)
        if (mux.xudpProxyUDP443 == "reject") {
            notes += "QUIC (UDP 443) в mux отклоняется: приложения перейдут на TCP."
        }
    }

    private fun xmuxJson(xmux: Xmux): JsonObject = buildJsonObject {
        xmux.maxConcurrency?.let { put("maxConcurrency", it.toString()) }
        xmux.maxConnections?.let { put("maxConnections", it.toString()) }
        xmux.cMaxReuseTimes?.let { put("cMaxReuseTimes", it.toString()) }
        xmux.hMaxRequestTimes?.let { put("hMaxRequestTimes", it.toString()) }
        xmux.hMaxReusableSecs?.let { put("hMaxReusableSecs", it.toString()) }
        xmux.hKeepAlivePeriod?.let { put("hKeepAlivePeriod", it) }
    }

    private fun finalmaskJson(fragment: XrayFragment): JsonObject = buildJsonObject {
        val settings = buildJsonObject {
            put("packets", fragment.packets.trim().lowercase())
            put("length", fragment.length.toString())
            put("delay", fragment.delay.toString())
            fragment.maxSplit?.let { put("maxSplit", it.toString()) }
        }
        put("tcp", JsonArray(listOf(buildJsonObject { put("type", "fragment"); put("settings", settings) })))
    }

    internal fun JsonObject.stringAt(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
