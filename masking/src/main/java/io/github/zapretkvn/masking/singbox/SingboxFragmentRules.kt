package io.github.zapretkvn.masking.singbox

import io.github.zapretkvn.masking.model.Problem
import io.github.zapretkvn.masking.model.ServerShape
import io.github.zapretkvn.masking.model.SingboxFragment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * sing-box's own TLS ClientHello fragmentation for the TLS protocols sing-box itself owns.
 * Keys from option/tls.go (sing-box-extended v1.14.3): `fragment`, `record_fragment`,
 * `fragment_fallback_delay`. They act in the std and uTLS client engines only, so an outbound
 * with REALITY (its own client) or a QUIC protocol is left untouched.
 *
 * The native `spoof` / `spoof_method` of the same file is NOT offered: common/tlsspoof opens a
 * SOCK_RAW socket on Linux (raw_linux.go) and a WinDivert handle on Windows (raw_windows.go),
 * which an ordinary Android app cannot do without root.
 */
object SingboxFragmentRules {
    /** sing-box-owned TCP+TLS protocols; VLESS, VMess and Trojan are Xray's, Hysteria2/TUIC are QUIC. */
    val PROTOCOLS = setOf("anytls", "http", "shadowtls")

    const val MAX_FALLBACK_DELAY_MS = 10_000

    data class Emission(val outbound: JsonObject, val notes: List<String>)

    fun validate(fragment: SingboxFragment): List<Problem> = buildList {
        fragment.fallbackDelayMs?.let {
            if (it !in 1..MAX_FALLBACK_DELAY_MS) {
                add(Problem.error("singbox.fallbackDelayMs", "Задержка: от 1 до $MAX_FALLBACK_DELAY_MS мс."))
            }
        }
        if (fragment.enabled && !fragment.fragment && !fragment.recordFragment) {
            add(Problem.warning("singbox.fragment", "Не выбран ни один способ фрагментации: ничего не изменится."))
        }
    }

    fun applies(shape: ServerShape): Boolean =
        shape.core == ServerShape.Core.SingBox && shape.protocol in PROTOCOLS && shape.security == "tls"

    fun emit(outbound: JsonObject, fragment: SingboxFragment, shape: ServerShape): Emission {
        if (!fragment.enabled || shape.core != ServerShape.Core.SingBox || shape.protocol !in PROTOCOLS) {
            return Emission(outbound, emptyList())
        }
        val tls = outbound["tls"] as? JsonObject
        if (tls == null || (tls["enabled"] as? JsonPrimitive)?.content != "true") {
            return Emission(outbound, listOf("Фрагментация TLS не применена: у сервера нет TLS."))
        }
        if ((tls["reality"] as? JsonObject)?.get("enabled")?.let { (it as? JsonPrimitive)?.content } == "true") {
            return Emission(outbound, listOf("Фрагментация TLS не применена: REALITY её обходит."))
        }
        val updated = LinkedHashMap(tls)
        updated.remove("fragment")
        updated.remove("record_fragment")
        updated.remove("fragment_fallback_delay")
        if (fragment.fragment) updated["fragment"] = JsonPrimitive(true)
        if (fragment.recordFragment) updated["record_fragment"] = JsonPrimitive(true)
        fragment.fallbackDelayMs?.let { updated["fragment_fallback_delay"] = JsonPrimitive("${it}ms") }
        return Emission(JsonObject(outbound + ("tls" to JsonObject(updated))), emptyList())
    }
}
