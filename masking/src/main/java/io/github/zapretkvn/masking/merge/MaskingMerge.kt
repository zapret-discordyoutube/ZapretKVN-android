package io.github.zapretkvn.masking.merge

import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.MaskingOverride
import io.github.zapretkvn.masking.model.ServerShape
import io.github.zapretkvn.masking.model.errors
import io.github.zapretkvn.masking.singbox.SingboxFragmentRules
import io.github.zapretkvn.masking.validate.MaskingValidator

/**
 * All masking settings of the app: the master switch, the global set, and the per-server
 * choices keyed by the server's stable key (the one server pins already use).
 */
data class MaskingSettings(
    val enabled: Boolean = false,
    val global: Masking = Masking.NONE,
    val overrides: Map<String, MaskingOverride> = emptyMap(),
) {
    fun overrideFor(serverKey: String?): MaskingOverride =
        serverKey?.let(overrides::get) ?: MaskingOverride.Inherit

    companion object {
        val OFF = MaskingSettings()
    }
}

/**
 * The one decision "which masking does this server get". Pure and deterministic: the UI
 * preview and the runtime config both call it, so what the user sees is what the core gets.
 *
 * - Master switch off, or override [MaskingOverride.Off]: nothing.
 * - [MaskingOverride.Custom]: each section present in the custom set replaces the global one
 *   (even when that section is disabled), each absent section falls back to global.
 * - A section that does not fit the server's native core is dropped with a note.
 * - A section with a validation error is dropped with a note: a core never receives a value
 *   the app knows it would refuse.
 */
object MaskingMerge {
    data class Effective(val masking: Masking, val notes: List<String>) {
        val isEmpty: Boolean get() = masking.isEmpty
    }

    fun effective(settings: MaskingSettings, serverKey: String?, shape: ServerShape): Effective {
        if (!settings.enabled) return Effective(Masking.NONE, emptyList())
        val chosen = when (val override = settings.overrideFor(serverKey)) {
            MaskingOverride.Inherit -> settings.global
            MaskingOverride.Off -> return Effective(Masking.NONE, listOf("Маскировка выключена для этого сервера."))
            is MaskingOverride.Custom -> Masking(
                wireguard = override.masking.wireguard ?: settings.global.wireguard,
                xray = override.masking.xray ?: settings.global.xray,
                singbox = override.masking.singbox ?: settings.global.singbox,
            )
        }
        val notes = mutableListOf<String>()
        val wireguard = chosen.wireguard?.takeIf { it.enabled }?.let { section ->
            when {
                shape.core != ServerShape.Core.Amnezia -> null
                MaskingValidator.wireguard(section).errors.isNotEmpty() -> {
                    notes += "WireGuard-маскировка пропущена: в ней ошибка."
                    null
                }
                else -> section
            }
        }
        val xray = chosen.xray?.takeIf { it.enabled }?.let { section ->
            when {
                shape.core != ServerShape.Core.Xray -> null
                MaskingValidator.xray(section).errors.isNotEmpty() -> {
                    notes += "Настройки Xray пропущены: в них ошибка."
                    null
                }
                else -> section
            }
        }
        val singbox = chosen.singbox?.takeIf { it.enabled }?.let { section ->
            when {
                !SingboxFragmentRules.applies(shape) -> null
                MaskingValidator.singbox(section).errors.isNotEmpty() -> {
                    notes += "Фрагментация TLS пропущена: в ней ошибка."
                    null
                }
                else -> section
            }
        }
        if (shape.core == ServerShape.Core.Hysteria && !chosen.isEmpty) {
            notes += "Для Hysteria2 маскировка задаётся в самой ссылке (obfs salamander)."
        }
        return Effective(Masking(wireguard = wireguard, xray = xray, singbox = singbox), notes)
    }
}
