package io.github.zapretkvn.masking.validate

import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.Problem
import io.github.zapretkvn.masking.model.SingboxFragment
import io.github.zapretkvn.masking.model.WireGuardMasking
import io.github.zapretkvn.masking.model.XrayShaping
import io.github.zapretkvn.masking.singbox.SingboxFragmentRules
import io.github.zapretkvn.masking.wireguard.WireGuardMaskingRules
import io.github.zapretkvn.masking.xray.XrayShapingRules

/** One entry point for the editor and the merge; the rules live next to each emitter. */
object MaskingValidator {
    fun validate(masking: Masking): List<Problem> = buildList {
        masking.wireguard?.let { addAll(wireguard(it)) }
        masking.xray?.let { addAll(xray(it)) }
        masking.singbox?.let { addAll(singbox(it)) }
    }

    fun wireguard(section: WireGuardMasking): List<Problem> = WireGuardMaskingRules.validate(section)

    fun xray(section: XrayShaping): List<Problem> = XrayShapingRules.validate(section)

    fun singbox(section: SingboxFragment): List<Problem> = SingboxFragmentRules.validate(section)
}
