package io.github.zapretkvn.masking

import io.github.zapretkvn.masking.codec.MaskingCodec
import io.github.zapretkvn.masking.merge.MaskingMerge
import io.github.zapretkvn.masking.merge.MaskingSettings
import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.MaskingOverride
import io.github.zapretkvn.masking.model.MuxCool
import io.github.zapretkvn.masking.model.ServerShape
import io.github.zapretkvn.masking.model.SingboxFragment
import io.github.zapretkvn.masking.model.WireGuardMasking
import io.github.zapretkvn.masking.model.XrayShaping
import io.github.zapretkvn.masking.presets.MaskingPresets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskingMergeTest {
    private val wg = ServerShape(ServerShape.Core.Amnezia, "wireguard")
    private val tcp = ServerShape(ServerShape.Core.Xray, "vless", network = "tcp", security = "reality")
    private val trojan = ServerShape(ServerShape.Core.SingBox, "trojan", security = "tls")

    private val global = Masking(
        wireguard = MaskingPresets.wireGuardJunkOnly.masking.wireguard,
        xray = XrayShaping(mux = MuxCool()),
    )

    @Test
    fun `master switch off gives nothing`() {
        assertTrue(MaskingMerge.effective(MaskingSettings(enabled = false, global = global), "a", wg).isEmpty)
    }

    @Test
    fun `inherit gives the global section that fits the core`() {
        val effective = MaskingMerge.effective(MaskingSettings(enabled = true, global = global), "a", wg).masking
        assertEquals(global.wireguard, effective.wireguard)
        assertNull(effective.xray)
    }

    @Test
    fun `override off switches one server off`() {
        val settings = MaskingSettings(enabled = true, global = global, overrides = mapOf("a" to MaskingOverride.Off))
        assertTrue(MaskingMerge.effective(settings, "a", wg).isEmpty)
        assertFalse(MaskingMerge.effective(settings, "b", wg).isEmpty)
    }

    @Test
    fun `custom replaces present sections and falls back for absent ones`() {
        val custom = Masking(wireguard = WireGuardMasking(enabled = false))
        val settings = MaskingSettings(enabled = true, global = global, overrides = mapOf("a" to MaskingOverride.Custom(custom)))
        assertNull(MaskingMerge.effective(settings, "a", wg).masking.wireguard)
        assertEquals(global.xray, MaskingMerge.effective(settings, "a", tcp).masking.xray)
    }

    @Test
    fun `a section with an error is dropped with a note`() {
        val broken = Masking(wireguard = WireGuardMasking(jc = 3, jmin = 40, jmax = 10))
        val effective = MaskingMerge.effective(MaskingSettings(enabled = true, global = broken), null, wg)
        assertNull(effective.masking.wireguard)
        assertEquals(1, effective.notes.size)
    }

    @Test
    fun `merge is deterministic`() {
        val settings = MaskingSettings(enabled = true, global = global)
        assertEquals(MaskingMerge.effective(settings, "a", tcp), MaskingMerge.effective(settings, "a", tcp))
    }

    @Test
    fun `applier writes the xray block and removes it again when off`() {
        val vless = Json.parseToJsonElement(
            """{"type":"vless","tag":"de","uri":"vless://id@h:443?type=tcp&security=reality&pbk=k"}""",
        ) as JsonObject
        val on = MaskingApplier.apply(JsonArray(listOf(vless)), MaskingSettings(enabled = true, global = global)) { it }
        val masked = on.entries.single() as JsonObject
        assertTrue(MaskingApplier.XRAY_KEY in masked)
        assertEquals(1, on.changed)
        val again = MaskingApplier.apply(on.entries, MaskingSettings(enabled = true, global = global)) { it }
        assertEquals(on.entries, again.entries)
        val off = MaskingApplier.apply(on.entries, MaskingSettings.OFF) { it }
        assertEquals(vless, off.entries.single())
    }

    @Test
    fun `applier reads the vision flow and the transport from the link`() {
        val shape = MaskingApplier.shapeOf(
            Json.parseToJsonElement(
                """{"type":"vless","uri":"vless://id@h:443?type=xhttp&security=reality&flow=xtls-rprx-vision&mode=auto"}""",
            ) as JsonObject,
        )!!
        assertEquals("xhttp", shape.network)
        assertEquals("reality", shape.security)
        assertTrue(shape.isVision)
        assertNull(MaskingApplier.shapeOf(JsonObject(mapOf("type" to JsonPrimitive("selector")))))
    }

    @Test
    fun `applier sets sing-box tls fragment but not on reality`() {
        val settings = MaskingSettings(enabled = true, global = Masking(singbox = SingboxFragment(recordFragment = true, fallbackDelayMs = 500)))
        val trojanJson = Json.parseToJsonElement("""{"type":"trojan","tag":"t","tls":{"enabled":true,"server_name":"x"}}""") as JsonObject
        val tls = ((MaskingApplier.apply(JsonArray(listOf(trojanJson)), settings) { it }.entries.single() as JsonObject)["tls"] as JsonObject)
        assertEquals("true", tls["fragment"].toString())
        assertEquals("true", tls["record_fragment"].toString())
        assertEquals("\"500ms\"", tls["fragment_fallback_delay"].toString())
        assertTrue(MaskingMerge.effective(settings, null, trojan).masking.singbox != null)
        assertNull(MaskingMerge.effective(settings, null, trojan.copy(security = "reality")).masking.singbox)
    }

    @Test
    fun `codec round trip keeps everything and carries unknown keys`() {
        val settings = MaskingSettings(
            enabled = true,
            global = Masking(
                wireguard = MaskingPresets.wireGuardDns().masking.wireguard,
                xray = MaskingPresets.xmuxRotate.masking.xray!!.copy(mux = MuxCool(enabled = false)),
                singbox = SingboxFragment(fallbackDelayMs = 300),
            ),
            overrides = mapOf("a" to MaskingOverride.Off, "b" to MaskingOverride.Custom(MaskingPresets.xrayFragment.masking)),
        )
        val previous = """{"schema":2,"futureFeature":{"x":1}}"""
        val text = MaskingCodec.encodeString(settings, previous)
        assertEquals(settings, MaskingCodec.decodeString(text))
        assertTrue(text.contains("futureFeature"))
        assertTrue(text.contains("\"schema\":1"))
    }

    @Test
    fun `codec writes the exact document Windows reads`() {
        // Same vector as windows tests/test_masking.py CodecTests.test_android_document_reads_the_same.
        val document = """{"schema":1,"enabled":true,"global":{"wireguard":{"enabled":true,"jc":3,"jmin":10,"jmax":30}},""" +
            """"overrides":{"p|wg":"off"}}"""
        val settings = MaskingSettings(
            enabled = true,
            global = Masking(wireguard = WireGuardMasking(jc = 3, jmin = 10, jmax = 30)),
            overrides = mapOf("p|wg" to MaskingOverride.Off),
        )
        assertEquals(document, MaskingCodec.encodeString(settings))
        assertEquals(settings, MaskingCodec.decodeString(document))
    }

    @Test
    fun `codec writes the desync section Windows reads`() {
        // Same vector as windows tests/test_masking.py CodecTests.test_android_desync_document_reads_the_same.
        val document = """{"schema":1,"enabled":true,"global":{"xray":{"enabled":true,"desync":""" +
            """{"enabled":true,"method":"fake","splitAt":"sni","fakeTtl":5,"fakeSni":"vk.com"}}},"overrides":{}}"""
        val settings = MaskingSettings(enabled = true, global = MaskingPresets.desyncFake(5).masking)
        assertEquals(document, MaskingCodec.encodeString(settings))
        assertEquals(settings, MaskingCodec.decodeString(document))
    }

    @Test
    fun `codec reads garbage as off and never crashes`() {
        assertEquals(MaskingSettings.OFF, MaskingCodec.decodeString("not json"))
        assertEquals(MaskingSettings.OFF, MaskingCodec.decodeString(null))
        val odd = MaskingCodec.decodeString("""{"enabled":"yes","global":{"wireguard":{"jc":"3"}},"overrides":{"x":7}}""")
        assertFalse(odd.enabled)
        assertNull(odd.global.wireguard?.jc)
        assertTrue(odd.overrides.isEmpty())
    }
}
