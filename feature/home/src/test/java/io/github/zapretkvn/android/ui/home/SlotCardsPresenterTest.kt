package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.vpn.SlotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCardsPresenterTest {
    private val main = HomeServerUi("Amsterdam", "🇳🇱", "VLESS", "V", null, null, null, true)

    @Test
    fun `no slots means no rows, the card shows only the switch`() {
        assertTrue(slotCards(emptyList(), main, hide = false).isEmpty())
    }

    @Test
    fun `rows keep the session order, use Russian titles and end with the main server`() {
        val rows = slotCards(
            listOf(SlotState("ai", "AI", "🇩🇪 Berlin"), SlotState("youtube", "YouTube", "Plain")),
            main,
            hide = false,
        )
        assertEquals(listOf("ai", "youtube", "default"), rows.map { it.id })
        assertEquals(listOf("ИИ-приложения", "YouTube", "Остальные приложения"), rows.map { it.title })
        assertEquals("🇩🇪", rows[0].flag)
        assertEquals("Berlin", rows[0].serverTitle)
        assertEquals("Amsterdam", rows.last().serverTitle)
    }

    @Test
    fun `a server of another profile shows its own name and that profile`() {
        val state = SlotState("telegram", "Telegram", "🇳🇴 Oslo@bbbbbbbb", serverName = "🇳🇴 Oslo", profileName = "Work")
        val row = slotCards(listOf(state), null, hide = false).single()
        assertEquals("Oslo", row.serverTitle)
        assertEquals("🇳🇴", row.flag)
        assertEquals("Work", row.profileName)
        assertNull(slotCards(listOf(SlotState("ai", "AI", "Plain")), null, hide = false).single().profileName)
    }

    @Test
    fun `a slot added by the user keeps its own title`() {
        assertEquals("Мой слот", slotTitle("app:com.example", "Мой слот"))
        assertEquals("app:com.example", slotTitle("app:com.example", ""))
    }

    @Test
    fun `addresses in a server name are masked when the privacy switch is on`() {
        val state = SlotState("youtube", "YouTube", "203.0.113.9:443", serverName = "203.0.113.9:443")
        val row = slotCards(listOf(state), null, hide = true).single()
        assertTrue(row.serverTitle, "203.0.113.9" !in row.serverTitle)
    }
}
