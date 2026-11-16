package io.github.zapretkvn.android.config

import io.github.zapretkvn.android.masking.MaskingServerKeys
import io.github.zapretkvn.masking.MaskingApplier
import io.github.zapretkvn.masking.merge.MaskingSettings
import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.MaskingOverride
import io.github.zapretkvn.masking.model.MuxCool
import io.github.zapretkvn.masking.model.XrayShaping
import io.github.zapretkvn.masking.presets.MaskingPresets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Masking reaches the runtime copy of the servers, per server, and never the stored profile. */
class RuntimeMaskingTest {
    private val stored = """
        {
          "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
          "outbounds":[
            {"type":"vless","tag":"de","uri":"vless://b831381d-6324-4d53-ad4f-8cda48b30811@de.example.net:443?type=tcp&security=reality&sni=a.example.net&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=01"},
            {"type":"selector","tag":"zapret-proxy","outbounds":["de","wg"],"default":"de"},
            {"type":"direct","tag":"direct"}
          ],
          "endpoints":[
            {"type":"wireguard","tag":"wg","mtu":1280,"address":["10.0.0.2/32"],
             "private_key":"aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8taGU=",
             "peers":[{"address":"wg.example.net","port":51820,"public_key":"aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8taGU=","allowed_ips":["0.0.0.0/0"]}]}
          ],
          "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
        }
    """.trimIndent()

    private val settings = MaskingSettings(
        enabled = true,
        global = Masking(
            wireguard = MaskingPresets.wireGuardDns().masking.wireguard,
            xray = XrayShaping(mux = MuxCool()),
        ),
    )

    private fun build(masking: MaskingSettings, keys: Map<String, String> = emptyMap()): RuntimeConfigResult.Ready =
        when (val result = RuntimeConfigBuilder.build(stored, options = RuntimeConfigOptions(masking = masking, maskingServerKeys = keys))) {
            is RuntimeConfigResult.Ready -> result
            is RuntimeConfigResult.Invalid -> throw AssertionError("runtime config refused: ${result.message}")
        }

    private fun RuntimeConfigResult.Ready.entry(section: String, tag: String): JsonObject =
        ((JsonConfig.parse(json) as JsonObject)[section] as JsonArray)
            .map { it as JsonObject }
            .single { (it["tag"] as? JsonPrimitive)?.content == tag }

    @Test
    fun `masking off leaves the runtime servers as stored`() {
        val ready = build(MaskingSettings.OFF)
        assertNull(ready.entry("outbounds", "de")[MaskingApplier.XRAY_KEY])
        assertNull(ready.entry("endpoints", "wg")["amnezia"])
        assertEquals(MaskingReport.NONE, ready.masking)
    }

    @Test
    fun `global masking reaches the xray outbound and the wireguard endpoint`() {
        val ready = build(settings)
        val xray = ready.entry("outbounds", "de")[MaskingApplier.XRAY_KEY] as JsonObject
        assertTrue("mux" in xray)
        val amnezia = ready.entry("endpoints", "wg")["amnezia"] as JsonObject
        assertEquals(JsonPrimitive(3), amnezia["jc"])
        assertTrue((amnezia["i1"] as JsonPrimitive).content.startsWith("<r 2>"))
        assertEquals(2, ready.masking.changed)
        assertTrue(MaskingServerKeys.logLine(true, ready.masking)!!.contains("изменено серверов: 2"))
    }

    @Test
    fun `per server off is honoured by the runtime key`() {
        val keys = mapOf("wg" to "p|wg", "de" to "p|de")
        val ready = build(settings.copy(overrides = mapOf("p|wg" to MaskingOverride.Off)), keys)
        assertNull(ready.entry("endpoints", "wg")["amnezia"])
        assertTrue(MaskingApplier.XRAY_KEY in ready.entry("outbounds", "de"))
    }
}
