package io.github.zapretkvn.masking

import io.github.zapretkvn.masking.model.Desync
import io.github.zapretkvn.masking.model.MuxCool
import io.github.zapretkvn.masking.model.Problem
import io.github.zapretkvn.masking.model.ServerShape
import io.github.zapretkvn.masking.model.Span
import io.github.zapretkvn.masking.model.XrayFragment
import io.github.zapretkvn.masking.model.XrayShaping
import io.github.zapretkvn.masking.model.Xmux
import io.github.zapretkvn.masking.model.errors
import io.github.zapretkvn.masking.presets.MaskingPresets
import io.github.zapretkvn.masking.xray.XrayShapingRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayShapingTest {
    private val realityTcp = ServerShape(ServerShape.Core.Xray, "vless", network = "tcp", security = "reality")
    private val vision = realityTcp.copy(flow = "xtls-rprx-vision")
    private val xhttp = ServerShape(ServerShape.Core.Xray, "vless", network = "xhttp", security = "reality")
    private val kcp = ServerShape(ServerShape.Core.Xray, "vless", network = "kcp", security = "none")

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `mux emits the native object and never a bare zero concurrency`() {
        val emission = XrayShapingRules.emit(XrayShaping(mux = MuxCool(concurrency = 8)), realityTcp)
        assertEquals(
            json("""{"mux":{"enabled":true,"concurrency":8,"xudpConcurrency":16,"xudpProxyUDP443":"reject"}}"""),
            emission.json,
        )
        assertTrue(emission.notes.any { it.contains("QUIC") })
        assertTrue(XrayShapingRules.validate(XrayShaping(mux = MuxCool(concurrency = 0))).errors.isNotEmpty())
    }

    @Test
    fun `concurrency under 128 warns that the core raises it`() {
        val problems = XrayShapingRules.validate(XrayShaping(mux = MuxCool(concurrency = 8)))
        assertEquals(listOf(Problem.Severity.Warning), problems.map { it.severity })
    }

    @Test
    fun `vision multiplexes only UDP`() {
        val emission = XrayShapingRules.emit(XrayShaping(mux = MuxCool()), vision)
        val mux = emission.json!!["mux"] as JsonObject
        assertEquals("-1", mux["concurrency"].toString())
        assertEquals("16", mux["xudpConcurrency"].toString())
    }

    @Test
    fun `mux is dropped with a note on already multiplexed transports`() {
        val emission = XrayShapingRules.emit(XrayShaping(mux = MuxCool()), xhttp)
        assertNull(emission.json)
        assertTrue(emission.notes.single().contains("xmux"))
    }

    @Test
    fun `xmux ranges serialize as Xray range strings`() {
        val emission = XrayShapingRules.emit(MaskingPresets.xmuxRotate.masking.xray!!, xhttp)
        assertEquals(
            json("""{"xmux":{"maxConcurrency":"16-32","cMaxReuseTimes":"8-16","hMaxRequestTimes":"60-120","hMaxReusableSecs":"60-120"}}"""),
            emission.json,
        )
    }

    @Test
    fun `xmux is not written for a non XHTTP server`() {
        val emission = XrayShapingRules.emit(XrayShaping(xmux = Xmux(maxConnections = Span.of(2))), realityTcp)
        assertNull(emission.json)
    }

    @Test
    fun `maxConnections with maxConcurrency is refused like transport_method go`() {
        val problems = XrayShapingRules.validate(XrayShaping(xmux = Xmux(maxConnections = Span.of(2), maxConcurrency = Span(8, 16))))
        assertTrue(problems.errors.any { it.field == "xmux.maxConnections" })
    }

    @Test
    fun `fragment goes to finalmask tcp and only on TCP streams`() {
        val emission = XrayShapingRules.emit(XrayShaping(fragment = XrayFragment()), realityTcp)
        assertEquals(
            json("""{"finalmask":{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"100-200","delay":"10-20"}}]}}"""),
            emission.json,
        )
        assertNull(XrayShapingRules.emit(XrayShaping(fragment = XrayFragment()), kcp).json)
    }

    @Test
    fun `fragment refuses the values finalmask refuses`() {
        assertTrue(XrayShapingRules.validate(XrayShaping(fragment = XrayFragment(packets = "0-3"))).errors.isNotEmpty())
        assertTrue(XrayShapingRules.validate(XrayShaping(fragment = XrayFragment(length = Span(0, 10)))).errors.isNotEmpty())
        assertTrue(XrayShapingRules.validate(XrayShaping(fragment = XrayFragment(packets = "1-3"))).errors.isEmpty())
    }

    @Test
    fun `disabled shaping or a non Xray server emits nothing`() {
        assertNull(XrayShapingRules.emit(XrayShaping(enabled = false, mux = MuxCool()), realityTcp).json)
        assertNull(XrayShapingRules.emit(XrayShaping(mux = MuxCool()), realityTcp.copy(core = ServerShape.Core.SingBox)).json)
    }

    @Test
    fun `every xray preset is valid`() {
        MaskingPresets.all.mapNotNull { it.masking.xray }.forEach { assertEquals(emptyList<Problem>(), XrayShapingRules.validate(it).errors) }
    }

    @Test
    fun `span parses the Xray forms and refuses the rest`() {
        assertEquals(Span(8, 16), Span.parse(" 8 - 16 "))
        assertEquals(Span.of(3), Span.parse("3"))
        assertNull(Span.parse("16-8"))
        assertNull(Span.parse("-1"))
        assertNull(Span.parse("a-b"))
        assertNull(Span.parse("1-2-3"))
        assertEquals("8-16", Span(8, 16).toString())
        assertEquals("3", Span.of(3).toString())
    }

    @Test
    fun `desync goes to the dialer key in patch 0017 names, only on TCP streams`() {
        val fake = XrayShaping(desync = Desync(method = "fake", fakeTtl = 4, fakeSni = " VK.com "))
        assertEquals(
            json("""{"desync":{"method":"fake","split_at":"sni","fake_ttl":4,"fake_sni":"vk.com"}}"""),
            XrayShapingRules.emit(fake, realityTcp).json,
        )
        assertEquals(
            json("""{"desync":{"method":"disorder","split_at":"sni"}}"""),
            XrayShapingRules.emit(XrayShaping(desync = Desync(method = "disorder")), xhttp).json,
        )
        val skipped = XrayShapingRules.emit(fake, kcp)
        assertNull(skipped.json)
        assertTrue(skipped.notes.single().startsWith("Пакеты-обманки не применены"))
    }

    @Test
    fun `desync refuses what kvndesync Parse refuses and warns on a TTL that may reach the server`() {
        fun problems(desync: Desync) = XrayShapingRules.validate(XrayShaping(desync = desync))
        assertTrue(problems(Desync()).isEmpty())
        assertEquals(listOf("desync.method"), problems(Desync(method = "spoof")).errors.map(Problem::field))
        assertEquals(listOf("desync.splitAt"), problems(Desync(splitAt = "0")).errors.map(Problem::field))
        assertEquals(listOf("desync.splitAt"), problems(Desync(splitAt = "1401")).errors.map(Problem::field))
        assertEquals(listOf("desync.fakeTtl"), problems(Desync(fakeTtl = 33)).errors.map(Problem::field))
        assertEquals(listOf("desync.fakeSni"), problems(Desync(fakeSni = "not a host")).errors.map(Problem::field))
        assertTrue(problems(Desync(fakeTtl = 12)).single().severity == Problem.Severity.Warning)
        assertTrue(problems(Desync(method = "oob")).single().message.startsWith("OOB ломает"))
        assertTrue("TTL and SNI only matter for fake", problems(Desync(method = "split", fakeTtl = 99, fakeSni = "")).isEmpty())
    }
}
