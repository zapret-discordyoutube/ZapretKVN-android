package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.config.OutboundDescription
import io.github.zapretkvn.android.config.SelectorGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerPingTargetResolverTest {
    private val descriptions = mapOf(
        "proxy-a" to OutboundDescription("proxy-a", "vless", "a.example", "a.example:443"),
        "proxy-b" to OutboundDescription("proxy-b", "vless", "b.example", "b.example:443"),
        "direct" to OutboundDescription("direct", "direct", null, null),
    )

    @Test
    fun `selected target follows runtime selector to server hostname`() {
        val resolver = ServerPingTargetResolver(
            descriptions,
            listOf(SelectorGroup("zapret-proxy", listOf("proxy-a", "proxy-b"), "proxy-a")),
        )

        val target = resolver.selected(
            listOf(group("zapret-proxy", selected = "proxy-b", "proxy-a", "proxy-b")),
        )

        assertEquals(ServerPingTarget("proxy-b", "b.example"), target)
    }

    @Test
    fun `group omits non-server outbounds instead of inventing route latency`() {
        val resolver = ServerPingTargetResolver(
            descriptions,
            listOf(SelectorGroup("zapret-proxy", listOf("proxy-a", "direct"), "proxy-a")),
        )
        val runtime = listOf(group("zapret-proxy", selected = "direct", "proxy-a", "direct"))

        assertEquals(listOf(ServerPingTarget("proxy-a", "a.example")), resolver.group("zapret-proxy", runtime))
        assertNull(resolver.selected(runtime))
    }

    @Test
    fun `direct selection never falls back to the only configured server`() {
        val oneServer = descriptions.filterKeys { it != "proxy-b" }
        val resolver = ServerPingTargetResolver(
            oneServer,
            listOf(SelectorGroup("zapret-proxy", listOf("proxy-a", "direct"), "proxy-a")),
        )

        assertNull(
            resolver.selected(
                listOf(group("zapret-proxy", selected = "direct", "proxy-a", "direct")),
            ),
        )
    }

    @Test
    fun `group probe does not recurse into nested selector`() {
        val resolver = ServerPingTargetResolver(
            descriptions,
            listOf(
                SelectorGroup("root", listOf("nested", "proxy-a"), "nested"),
                SelectorGroup("nested", listOf("proxy-b"), "proxy-b"),
            ),
            primaryGroupTag = "root",
        )
        val runtime = listOf(
            group("root", selected = "nested", "nested", "proxy-a"),
            group("nested", selected = "proxy-b", "proxy-b"),
        )

        assertEquals(
            listOf(ServerPingTarget("proxy-a", "a.example")),
            resolver.group("root", runtime),
        )
        assertEquals(ServerPingTarget("proxy-b", "b.example"), resolver.selected(runtime))
    }

    private fun group(tag: String, selected: String, vararg items: String) = RuntimeSelectorGroup(
        tag = tag,
        type = "selector",
        selected = selected,
        selectable = true,
        items = items.map { item ->
            RuntimeOutboundItem(item, "vless", null)
        },
    )
}
