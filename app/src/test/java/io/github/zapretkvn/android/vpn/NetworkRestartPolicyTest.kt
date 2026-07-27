package io.github.zapretkvn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRestartPolicyTest {
    @Test
    fun `transient change that returns to session baseline cancels restart`() {
        val baseline = "wifi-a"

        assertEquals(
            NetworkRestartDecision.DebounceRestart,
            NetworkRestartPolicy.decide(baseline, "no-network"),
        )
        assertEquals(
            NetworkRestartDecision.KeepSession,
            NetworkRestartPolicy.decide(baseline, baseline),
        )
    }

    @Test
    fun `stable different network still requests controlled restart`() {
        assertEquals(
            NetworkRestartDecision.DebounceRestart,
            NetworkRestartPolicy.decide("wifi-a", "mobile-b"),
        )
    }

    @Test
    fun `restart waits longer while the new network is still settling`() {
        val plan = NetworkRestartPolicy.plan(
            sessionBaseline = "mobile-a",
            observed = "wifi-b",
            observedSettled = false,
            waitedMillis = 0,
        )

        assertEquals(NetworkRestartDecision.DebounceRestart, plan.decision)
        assertEquals(NetworkRestartPolicy.SETTLING_DEBOUNCE_MILLIS, plan.debounceMillis)
    }

    @Test
    fun `settled network restarts on the short debounce`() {
        val plan = NetworkRestartPolicy.plan(
            sessionBaseline = "mobile-a",
            observed = "wifi-b",
            observedSettled = true,
            waitedMillis = 0,
        )

        assertEquals(NetworkRestartPolicy.SETTLED_DEBOUNCE_MILLIS, plan.debounceMillis)
    }

    @Test
    fun `network that never becomes validated stops delaying the restart`() {
        val plan = NetworkRestartPolicy.plan(
            sessionBaseline = "mobile-a",
            observed = "wifi-b",
            observedSettled = false,
            waitedMillis = NetworkRestartPolicy.MAX_SETTLING_WAIT_MILLIS,
        )

        assertEquals(NetworkRestartPolicy.SETTLED_DEBOUNCE_MILLIS, plan.debounceMillis)
    }

    @Test
    fun `returning to the session baseline cancels the pending restart`() {
        val plan = NetworkRestartPolicy.plan(
            sessionBaseline = "wifi-a",
            observed = "wifi-a",
            observedSettled = false,
            waitedMillis = 5_000,
        )

        assertEquals(NetworkRestartDecision.KeepSession, plan.decision)
        assertEquals(0L, plan.debounceMillis)
    }
}
