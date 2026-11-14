package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.SlotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCardsPresenterTest {
    private val group = RuntimeSelectorGroup(
        tag = "zapret-proxy",
        type = "selector",
        selected = "🇳🇱 Amsterdam",
        selectable = true,
        primary = true,
        items = listOf(
            RuntimeOutboundItem("🇳🇱 Amsterdam", "vless", "ams.example.com:443"),
            RuntimeOutboundItem("🇩🇪 Berlin", "hysteria2", "ber.example.com:443"),
            RuntimeOutboundItem("Plain", "vless", "plain.example.com:443"),
        ),
    )
    private val main = HomeServerUi("Amsterdam", "🇳🇱", "VLESS", "V", null, null, null, true)

    @Test
    fun `no slots means no rows, the card shows only the switch`() {
        assertTrue(slotCards(emptyList(), listOf(group), main, hide = false, hostCountries = emptyMap()).isEmpty())
    }

    @Test
    fun `rows keep the session order, use Russian titles and end with the main server`() {
        val rows = slotCards(
            listOf(SlotState("ai", "AI", "🇩🇪 Berlin"), SlotState("youtube", "YouTube", "Plain")),
            listOf(group), main, hide = false, hostCountries = emptyMap(),
        )
        assertEquals(listOf("ai", "youtube", "default"), rows.map { it.id })
        assertEquals(listOf("ИИ-приложения", "YouTube", "Остальные приложения"), rows.map { it.title })
        assertEquals("🇩🇪", rows[0].flag)
        assertEquals("Авто", rows[0].modeLabel)
        assertEquals("Amsterdam", rows.last().serverTitle)
        assertEquals("Выбран вами", rows.last().modeLabel)
    }

    @Test
    fun `a slot added by the user keeps its own title`() {
        assertEquals("Мой слот", slotTitle("app:com.example", "Мой слот"))
        assertEquals("app:com.example", slotTitle("app:com.example", ""))
    }
}
