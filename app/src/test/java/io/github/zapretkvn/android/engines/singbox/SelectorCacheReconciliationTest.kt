package io.github.zapretkvn.android.engines.singbox

import io.github.zapretkvn.android.config.SelectorGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorCacheReconciliationTest {
    @Test
    fun `stored default wins over the core cache`() {
        val selections = SelectorCacheReconciliation.selections(
            listOf(SelectorGroup("zapret-proxy", listOf("server-a", "server-b"), "server-b")),
        )

        assertEquals(listOf(SelectorSelection("zapret-proxy", "server-b")), selections)
    }

    @Test
    fun `group without default pins the first member instead of the cached one`() {
        val selections = SelectorCacheReconciliation.selections(
            listOf(SelectorGroup("zapret-proxy", listOf("server-a", "server-b"), null)),
        )

        assertEquals(listOf(SelectorSelection("zapret-proxy", "server-a")), selections)
    }

    @Test
    fun `default outside the group falls back to the first member`() {
        val selections = SelectorCacheReconciliation.selections(
            listOf(SelectorGroup("zapret-proxy", listOf("server-a"), "removed-by-subscription")),
        )

        assertEquals(listOf(SelectorSelection("zapret-proxy", "server-a")), selections)
    }

    @Test
    fun `every selector group is reconciled`() {
        val selections = SelectorCacheReconciliation.selections(
            listOf(
                SelectorGroup("zapret-proxy", listOf("server-a", "server-b"), "server-b"),
                SelectorGroup("custom", listOf("server-c"), null),
            ),
        )

        assertEquals(
            listOf(
                SelectorSelection("zapret-proxy", "server-b"),
                SelectorSelection("custom", "server-c"),
            ),
            selections,
        )
    }

    @Test
    fun `unusable groups are skipped`() {
        val selections = SelectorCacheReconciliation.selections(
            listOf(
                SelectorGroup("", listOf("server-a"), "server-a"),
                SelectorGroup("empty", emptyList(), null),
            ),
        )

        assertTrue(selections.isEmpty())
    }
}
