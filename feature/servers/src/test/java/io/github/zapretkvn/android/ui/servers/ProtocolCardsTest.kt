package io.github.zapretkvn.android.ui.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cards of the «Добавить сервер» sheet accept only a link of their own protocol (owner request 2026-11-27). */
class ProtocolCardsTest {
    private fun card(id: String) = ProtocolCards.all.single { it.id == id }

    @Test
    fun `every protocol of the importer has a card with its own scheme`() {
        val schemes = ProtocolCards.all.flatMap { it.schemes }.toSet()
        assertEquals(
            setOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "tuic://", "naive+https://"),
            schemes,
        )
    }

    @Test
    fun `a card accepts its scheme in any case and refuses another protocol`() {
        assertTrue(ProtocolCards.accepts(card("vless"), "VLESS://uuid@host:443#n"))
        assertTrue(ProtocolCards.accepts(card("hysteria2"), "hy2://pw@host:443#n"))
        assertTrue(ProtocolCards.accepts(card("naive"), "naive+https://id:secret@host.example.com:443#n"))
        assertFalse(ProtocolCards.accepts(card("naive"), "vless://uuid@host:443#n"))
        assertFalse(ProtocolCards.accepts(card("vless"), "naive+https://id:secret@host.example.com:443#n"))
    }

    @Test
    fun `a pasted block with a note and several links is accepted when one line fits`() {
        assertTrue(ProtocolCards.accepts(card("vless"), "заметка\n\nvless://a@h:1#x\nvless://b@h:2#y"))
        assertFalse(ProtocolCards.accepts(card("vless"), "заметка без ссылки"))
    }

    @Test
    fun `empty text is never accepted`() {
        ProtocolCards.all.filter { it.id != ProtocolCards.SUBSCRIPTION_ID }.forEach {
            assertFalse(it.id, ProtocolCards.accepts(it, "   "))
        }
    }

    @Test
    fun `the WireGuard card takes a config with an Interface section, not a link`() {
        val wg = card("wireguard")
        assertTrue(ProtocolCards.accepts(wg, "[Interface]\nPrivateKey = x\n[Peer]\nPublicKey = y"))
        assertFalse(ProtocolCards.accepts(wg, "vless://uuid@host:443#n"))
        assertEquals("Нужен текст конфигурации с разделом [Interface].", ProtocolCards.mismatchMessage(wg))
    }

    @Test
    fun `the error names the expected scheme`() {
        assertEquals("Ссылка должна начинаться с hysteria2:// или hy2://", ProtocolCards.mismatchMessage(card("hysteria2")))
    }
}
