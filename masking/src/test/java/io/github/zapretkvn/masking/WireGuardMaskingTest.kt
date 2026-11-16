package io.github.zapretkvn.masking

import io.github.zapretkvn.masking.model.WireGuardMasking
import io.github.zapretkvn.masking.model.errors
import io.github.zapretkvn.masking.presets.MaskingPresets
import io.github.zapretkvn.masking.wireguard.DnsSignature
import io.github.zapretkvn.masking.wireguard.SignatureChain
import io.github.zapretkvn.masking.wireguard.WireGuardMaskingRules
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardMaskingTest {
    @Test
    fun `chain accepts exactly the tags amneziawg-go builds`() {
        assertEquals(SignatureChain.Result.Valid(2 + 2 + 4 + 16 + 8 + 3), SignatureChain.validate("<b 0x0100><r 2><t><rc 16><rd 8><r 3>"))
    }

    @Test
    fun `counter tag c is refused because the core has no builder for it`() {
        val result = SignatureChain.validate("<b 0x01><c>")
        assertTrue(result is SignatureChain.Result.Invalid)
        assertTrue((result as SignatureChain.Result.Invalid).reason.contains("не поддерживается ядром"))
    }

    @Test
    fun `uppercase 0X prefix is refused like obf_bytes does`() {
        assertTrue(SignatureChain.validate("<b 0X0100>") is SignatureChain.Result.Invalid)
        assertTrue(SignatureChain.validate("<b 0100>") is SignatureChain.Result.Valid)
    }

    @Test
    fun `packet larger than the MTU cap is refused`() {
        assertTrue(SignatureChain.validate("<r 1000><r 401>") is SignatureChain.Result.Invalid)
        assertTrue(SignatureChain.validate("<r 1000><r 400>") is SignatureChain.Result.Valid)
    }

    @Test
    fun `text outside tags and empty chains are refused`() {
        assertTrue(SignatureChain.validate("hello<b 0x01>") is SignatureChain.Result.Invalid)
        assertTrue(SignatureChain.validate("   ") is SignatureChain.Result.Invalid)
        assertTrue(SignatureChain.validate("<b 0x01") is SignatureChain.Result.Invalid)
    }

    @Test
    fun `dns signature randomizes the transaction id in the core, not at build time`() {
        val chain = checkNotNull(DnsSignature.chain("yandex.ru"))
        assertTrue(chain.startsWith("<r 2>"))
        // header after id, then 06 "yandex" 02 "ru" 00, QTYPE A, QCLASS IN
        assertEquals("<r 2><b 0x" + "01000001000000000000" + "0679616e646578" + "027275" + "00" + "00010001" + ">", chain)
        assertEquals(chain, DnsSignature.chain("YANDEX.RU."))
        assertTrue(SignatureChain.validate(chain) is SignatureChain.Result.Valid)
    }

    @Test
    fun `dns signature refuses names that are not host names`() {
        assertNull(DnsSignature.chain("localhost"))
        assertNull(DnsSignature.chain("bad_label.ru"))
        assertNull(DnsSignature.chain("-x.ru"))
        assertNull(DnsSignature.chain(""))
    }

    @Test
    fun `junk is all three numbers or none`() {
        val problems = WireGuardMaskingRules.validate(WireGuardMasking(jmax = 30))
        assertTrue(problems.errors.any { it.field == "jc" })
    }

    @Test
    fun `jmin above jmax and empty junk packets are refused`() {
        assertTrue(WireGuardMaskingRules.validate(WireGuardMasking(jc = 3, jmin = 50, jmax = 10)).errors.any { it.field == "jmin" })
        assertTrue(WireGuardMaskingRules.validate(WireGuardMasking(jc = 3, jmin = 0, jmax = 0)).errors.any { it.field == "jmax" })
        assertTrue(WireGuardMaskingRules.validate(WireGuardMasking(jc = 3, jmin = 10, jmax = 30)).isEmpty())
    }

    @Test
    fun `apply writes only junk and signatures and keeps the server header fields`() {
        val imported = JsonObject(
            mapOf("h1" to JsonPrimitive("1-5"), "s1" to JsonPrimitive(20), "jc" to JsonPrimitive(9), "i2" to JsonPrimitive("<r 4>")),
        )
        val masked = WireGuardMaskingRules.applyTo(
            imported,
            WireGuardMasking(jc = 3, jmin = 10, jmax = 30, signatures = mapOf("i1" to "<r 2>", "i3" to " ")),
        )
        assertEquals(JsonPrimitive("1-5"), masked["h1"])
        assertEquals(JsonPrimitive(20), masked["s1"])
        assertEquals(JsonPrimitive(3), masked["jc"])
        assertEquals(JsonPrimitive("<r 2>"), masked["i1"])
        assertEquals(JsonPrimitive("<r 4>"), masked["i2"])
        assertNull(masked["i3"])
    }

    @Test
    fun `apply creates the amnezia object for a plain WireGuard server`() {
        val masked = WireGuardMaskingRules.applyTo(null, MaskingPresets.wireGuardJunkOnly.masking.wireguard!!)
        assertEquals(setOf("jc", "jmin", "jmax"), masked.keys)
    }

    @Test
    fun `every wireguard preset is valid`() {
        MaskingPresets.all.mapNotNull { it.masking.wireguard }.forEach { section ->
            assertEquals(emptyList<Any>(), WireGuardMaskingRules.validate(section))
        }
        DnsSignature.DOMAINS.forEach { assertTrue(it, DnsSignature.isValidDomain(it)) }
    }
}
