package io.github.zapretkvn.core.uimodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerNameTest {
    @Test
    fun `flag and protocol prefix move out of the title`() {
        val name = ServerName.parse("VLESS 🇳🇱 Amsterdam-2")
        assertEquals("Amsterdam-2", name.title)
        assertEquals("🇳🇱", name.flag)
        assertEquals("NL", name.countryCode)
        assertEquals("VLESS", name.protocolPrefix)
        assertEquals("Нидерланды", name.countryName)
    }

    @Test
    fun `flag first and separators at the edges are trimmed but inner text kept`() {
        val name = ServerName.parse("🇩🇪 | Frankfurt - fast 1x |")
        assertEquals("Frankfurt - fast 1x", name.title)
        assertEquals("DE", name.countryCode)
        assertNull(name.protocolPrefix)
    }

    @Test
    fun `plain names stay as they are`() {
        val name = ServerName.parse("us-vdsryzen-8")
        assertEquals("us-vdsryzen-8", name.title)
        assertNull(name.flag)
        assertNull(name.countryCode)
    }

    @Test
    fun `protocol word inside a name is not stripped`() {
        assertEquals("VLESSmart", ServerName.parse("VLESSmart").title)
    }

    @Test
    fun `a name that is only a flag falls back to the original`() {
        assertEquals("🇫🇮", ServerName.parse("🇫🇮").title)
    }

    @Test
    fun `protocol labels and monograms are human readable`() {
        assertEquals("Hysteria2", protocolLabel("hysteria2"))
        assertEquals("VL", protocolMonogram("vless"))
        assertEquals("SS", protocolMonogram("shadowsocks"))
    }
}
