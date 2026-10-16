package io.github.zapretkvn.android.ui

import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentServerLabelTest {
    @Test
    fun `notification names the server with its flag and real name`() {
        val groups = listOf(group("zapret-proxy", "🇱🇻 Hiprig · VLESS WS · TLS", primary = true))

        assertEquals("🇱🇻 Hiprig · VLESS WS · TLS", CurrentServerLabel.of(groups, emptyMap(), hideAddresses = true))
    }

    @Test
    fun `server without a flag gets the same country as on the servers tab`() {
        val groups = listOf(group("zapret-proxy", "Riga fast", primary = true))

        assertEquals("🇱🇻 Riga fast", CurrentServerLabel.of(groups, emptyMap(), hideAddresses = true))
    }

    @Test
    fun `selected nested auto group resolves to its own choice`() {
        val groups = listOf(
            group("proxy", "auto", primary = true, members = listOf("auto", "🇳🇱 Amsterdam")),
            group("auto", "🇳🇱 Amsterdam", type = "urltest"),
        )

        assertEquals("🇳🇱 Amsterdam", CurrentServerLabel.of(groups, emptyMap(), hideAddresses = false))
    }

    @Test
    fun `self-referencing groups do not hang and unknown selection gives nothing`() {
        assertEquals("loop", CurrentServerLabel.currentTag(listOf(group("loop", "loop", primary = true))))
        assertNull(CurrentServerLabel.currentTag(listOf(group("a", "b", primary = true), group("b", "a"))))
        assertNull(CurrentServerLabel.of(listOf(group("p", "", primary = true)), emptyMap(), hideAddresses = true))
        assertNull(CurrentServerLabel.of(emptyList(), emptyMap(), hideAddresses = true))
    }

    @Test
    fun `address in the name is masked when addresses are hidden`() {
        val groups = listOf(group("zapret-proxy", "203.0.113.7:443", primary = true))

        val label = CurrentServerLabel.of(groups, emptyMap(), hideAddresses = true)

        assertEquals(false, label.orEmpty().contains("203.0.113.7"))
    }

    private fun group(
        tag: String,
        selected: String,
        primary: Boolean = false,
        type: String = "selector",
        members: List<String> = listOf(selected),
    ) = RuntimeSelectorGroup(
        tag = tag,
        type = type,
        selected = selected,
        selectable = type == "selector",
        items = members.filter(String::isNotBlank).map { RuntimeOutboundItem(it, "vless", null) },
        primary = primary,
    )
}
