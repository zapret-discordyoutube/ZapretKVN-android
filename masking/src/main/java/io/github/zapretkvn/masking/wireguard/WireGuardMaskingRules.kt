package io.github.zapretkvn.masking.wireguard

import io.github.zapretkvn.masking.model.Problem
import io.github.zapretkvn.masking.model.WireGuardMasking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Validation and emission of [WireGuardMasking] into a sing-box `wireguard` endpoint's `amnezia` object. */
object WireGuardMaskingRules {
    /** More junk packets than this only adds a visible burst before every handshake. */
    const val MAX_JUNK_COUNT = 128

    fun validate(masking: WireGuardMasking): List<Problem> = buildList {
        // All three together: one value set on top of an imported profile could pair a new
        // Jmax with the profile's larger Jmin, and the core would refuse the whole tunnel.
        if (masking.hasJunk && (masking.jc == null || masking.jmin == null || masking.jmax == null)) {
            add(Problem.error("jc", "Мусорные пакеты задаются тремя числами сразу: Jc, Jmin и Jmax."))
        }
        masking.jc?.let { if (it !in 0..MAX_JUNK_COUNT) add(Problem.error("jc", "Число мусорных пакетов: от 0 до $MAX_JUNK_COUNT.")) }
        listOf("jmin" to masking.jmin, "jmax" to masking.jmax).forEach { (field, value) ->
            if (value != null && value !in 0..SignatureChain.MAX_PACKET_BYTES) {
                add(Problem.error(field, "Размер мусорного пакета: от 0 до ${SignatureChain.MAX_PACKET_BYTES} байт."))
            }
        }
        val jmin = masking.jmin
        val jmax = masking.jmax
        // amneziawg-go v3.1.20261116 refuses jmin > jmax (device/uapi.go:880); earlier builds
        // took it and underflowed the size, so the editor refuses it for every build.
        if (jmin != null && jmax != null && jmin > jmax) add(Problem.error("jmin", "Jmin не может быть больше Jmax."))
        if ((masking.jc ?: 0) > 0 && (jmax ?: 0) == 0) {
            add(Problem.error("jmax", "При мусорных пакетах нужен Jmax больше 0, иначе пакеты пустые."))
        }
        masking.signatures.forEach { (key, chain) ->
            if (key !in WireGuardMasking.SIGNATURE_KEYS) {
                add(Problem.error(key, "Неизвестное поле $key: допустимы I1-I5."))
            } else if (chain.isNotBlank()) {
                val result = SignatureChain.validate(chain)
                if (result is SignatureChain.Result.Invalid) add(Problem.error(key, "${key.uppercase()}: ${result.reason}"))
            }
        }
    }

    /**
     * The `amnezia` object of an endpoint with [masking] applied. Only junk and I1-I5 are
     * written; every other key of [current] (H1-H4, S1-S4, AWG 3 fields) is kept as imported.
     * A blank signature removes nothing: it just is not set by masking.
     */
    fun applyTo(current: JsonObject?, masking: WireGuardMasking): JsonObject {
        val result = LinkedHashMap(current.orEmpty())
        masking.jc?.let { result["jc"] = JsonPrimitive(it) }
        masking.jmin?.let { result["jmin"] = JsonPrimitive(it) }
        masking.jmax?.let { result["jmax"] = JsonPrimitive(it) }
        WireGuardMasking.SIGNATURE_KEYS.forEach { key ->
            masking.signatures[key]?.takeIf(String::isNotBlank)?.let { result[key] = JsonPrimitive(it.trim()) }
        }
        return JsonObject(result)
    }
}
