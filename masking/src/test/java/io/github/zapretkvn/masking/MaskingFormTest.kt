package io.github.zapretkvn.masking

import io.github.zapretkvn.masking.form.MaskingForm
import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.MuxCool
import io.github.zapretkvn.masking.model.Span
import io.github.zapretkvn.masking.model.XrayShaping
import io.github.zapretkvn.masking.presets.MaskingPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskingFormTest {
    @Test
    fun `an untouched form is no masking at all`() {
        val result = MaskingForm().toMasking()
        assertEquals(Masking.NONE, result.masking)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `wireguard preset fills the fields and round trips`() {
        val form = MaskingForm().withWireGuardPreset("wg-dns", "vk.com")
        val result = form.toMasking()
        assertFalse(result.hasErrors)
        assertEquals(MaskingPresets.wireGuardDns("vk.com").masking.wireguard, result.masking.wireguard)
        assertEquals(form, MaskingForm.from(result.masking))
    }

    @Test
    fun `typed garbage is an error and is never saved silently`() {
        val result = MaskingForm(wireguardEnabled = true, jc = "три", jmin = "10", jmax = "30").toMasking()
        assertTrue(result.hasErrors)
        assertTrue(result.problems.any { it.field == "jc" && it.message.contains("целое") })
    }

    @Test
    fun `a switched off group keeps no leftovers and blocks nothing`() {
        val result = MaskingForm(wireguardEnabled = false, jc = "x", i1 = "<c>").toMasking()
        assertNull(result.masking.wireguard)
        assertFalse(result.hasErrors)
    }

    @Test
    fun `xmux preset and core rule errors reach the form`() {
        val form = MaskingForm().withXmuxPreset("xmux-few").copy(xmuxMaxConcurrency = "8-16")
        val result = form.toMasking()
        assertEquals(Span.of(2), result.masking.xray?.xmux?.maxConnections)
        assertTrue(result.problems.any { it.field == "xmux.maxConnections" })
        assertTrue(result.hasErrors)
    }

    @Test
    fun `mux warning is shown but does not block saving`() {
        val result = MaskingForm(muxEnabled = true).toMasking()
        assertEquals(XrayShaping(mux = MuxCool()), result.masking.xray)
        assertTrue(result.problems.isNotEmpty())
        assertFalse(result.hasErrors)
    }

    @Test
    fun `fragment needs both its ranges`() {
        assertTrue(MaskingForm(fragmentEnabled = true, fragmentDelay = "").toMasking().hasErrors)
        assertFalse(MaskingForm(fragmentEnabled = true).toMasking().hasErrors)
    }

    @Test
    fun `server choices fit the protocol and label the stored choice`() {
        val wg = io.github.zapretkvn.masking.presets.ServerChoices.forType("wireguard")
        assertEquals(listOf("inherit", "off", "wg-dns", "wg-more-junk", "wg-junk-only"), wg.map { it.id })
        val vless = io.github.zapretkvn.masking.presets.ServerChoices.forType("vless").map { it.id }
        assertTrue("xray-mux" in vless && "xmux-rotate" in vless && "singbox-fragment" !in vless)
        assertEquals(listOf("inherit", "off"), io.github.zapretkvn.masking.presets.ServerChoices.forType("hysteria2").map { it.id })
        assertEquals(listOf("inherit", "off", "singbox-fragment"), io.github.zapretkvn.masking.presets.ServerChoices.forType("trojan").map { it.id })
        val custom = io.github.zapretkvn.masking.model.MaskingOverride.Custom(MaskingPresets.xmuxFew.masking)
        assertEquals("XHTTP: мало соединений", io.github.zapretkvn.masking.presets.ServerChoices.label(custom))
        assertEquals("Своя", io.github.zapretkvn.masking.presets.ServerChoices.label(
            io.github.zapretkvn.masking.model.MaskingOverride.Custom(Masking(xray = XrayShaping(mux = MuxCool(concurrency = 200)))),
        ))
    }
}
