package io.github.zapretkvn.android.network.probes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsRescueBudgetTest {
    @Test
    fun `no rescue when remaining deadline is too short`() {
        assertNull(HttpsRescueBudget.of(HttpsRescueBudget.MIN_REMAINING_MILLIS - 1))
        assertNull(HttpsRescueBudget.of(0))
        assertNull(HttpsRescueBudget.of(-5_000))
    }

    @Test
    fun `rescue always ends before the health deadline`() {
        for (remaining in HttpsRescueBudget.MIN_REMAINING_MILLIS..20_000L step 250) {
            val budget = checkNotNull(HttpsRescueBudget.of(remaining))
            assertTrue(
                "wall ${budget.wallMillis} must leave margin in $remaining",
                budget.wallMillis <= remaining - HttpsRescueBudget.DEADLINE_MARGIN_MILLIS,
            )
            assertTrue(budget.socketTimeoutMillis <= budget.wallMillis)
            assertTrue(budget.socketTimeoutMillis <= HttpsRescueBudget.SOCKET_TIMEOUT_MILLIS)
        }
    }

    @Test
    fun `slow tunnel keeps the full socket timeout when time allows`() {
        val budget = checkNotNull(HttpsRescueBudget.of(14_000))
        assertEquals(HttpsRescueBudget.SOCKET_TIMEOUT_MILLIS, budget.socketTimeoutMillis)
        assertEquals(13_500L, budget.wallMillis)
    }
}
