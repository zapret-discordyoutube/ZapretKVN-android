package io.github.zapretkvn.masking.presets

import io.github.zapretkvn.masking.model.MaskingOverride

/**
 * What one server's masking can be set to on the "Маскировка" page: inherit the global set,
 * off, or one named preset that fits the server's protocol (its sing-box outbound type).
 */
object ServerChoices {
    data class Choice(val id: String, val title: String, val override: MaskingOverride)

    private val INHERIT = Choice("inherit", "Как в общих", MaskingOverride.Inherit)
    private val OFF = Choice("off", "Выключить", MaskingOverride.Off)

    fun forType(type: String): List<Choice> {
        val groups = when (type.lowercase()) {
            "wireguard" -> setOf(MaskingPresets.Group.WireGuard)
            "vless" -> setOf(MaskingPresets.Group.Xray, MaskingPresets.Group.Experiment)
            "hysteria2" -> emptySet()
            else -> setOf(MaskingPresets.Group.Experiment)
        }
        val presets = MaskingPresets.all.filter { preset ->
            preset.group in groups && when (type.lowercase()) {
                "vless" -> preset.masking.xray != null
                "wireguard" -> preset.masking.wireguard != null
                else -> preset.masking.singbox != null
            }
        }
        return listOf(INHERIT, OFF) + presets.map { Choice(it.id, it.title, MaskingOverride.Custom(it.masking)) }
    }

    /** The label of the stored choice; a custom set that is no preset reads "Своя". */
    fun label(override: MaskingOverride): String = when (override) {
        MaskingOverride.Inherit -> INHERIT.title
        MaskingOverride.Off -> "Выключена"
        is MaskingOverride.Custom ->
            MaskingPresets.all.firstOrNull { it.masking == override.masking }?.title ?: "Своя"
    }
}
