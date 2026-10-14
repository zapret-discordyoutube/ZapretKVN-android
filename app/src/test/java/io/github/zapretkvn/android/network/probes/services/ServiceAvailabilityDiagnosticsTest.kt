package io.github.zapretkvn.android.network.probes.services

import io.github.zapretkvn.android.network.probes.HttpsProbeFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceAvailabilityDiagnosticsTest {
    @Test
    fun reportHoldsOnlyTokensAndNumbers() {
        val blocked = snapshot(ServiceId.Telegram, Headline.Direct, measuredAt = 1_000)
            .copy(direct = path(ServiceVerdict.Blocked, null, HttpsProbeFailureKind.Reset))
        val state = ServiceAvailabilityState(
            items = mapOf(
                ServiceId.YouTube to ServiceAvailability.Result(snapshot(ServiceId.YouTube, latency = 230)),
                ServiceId.Telegram to ServiceAvailability.Stale(blocked),
                ServiceId.Discord to ServiceAvailability.NotChecked,
            ),
            lastRun = ServiceRunSummary(ServiceCheckTrigger.Auto, 4_200, 22, 20, 140_000, 9L),
        )

        val json = ServiceAvailabilityDiagnostics.toJson(state, nowEpochMillis = 61_000).toString()

        assertTrue("\"headline\":\"via_vpn\"" in json)
        assertTrue("\"failure\":\"reset\"" in json)
        assertTrue("\"trigger\":\"auto\"" in json)
        assertTrue("\"latency_bucket\":\"150_400\"" in json)
        assertTrue("\"age_s\":60" in json)
        // Ни адресов сервисов, ни IP, ни текста.
        ServiceCatalog.allTargets.forEach { assertFalse(it, it in json) }
        assertFalse("://" in json)
        assertFalse(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""").containsMatchIn(json))
    }

    @Test
    fun latencyBuckets() {
        assertEquals("none", ServiceAvailabilityDiagnostics.bucket(null))
        assertEquals("lt_150", ServiceAvailabilityDiagnostics.bucket(149))
        assertEquals("150_400", ServiceAvailabilityDiagnostics.bucket(150))
        assertEquals("ge_3000", ServiceAvailabilityDiagnostics.bucket(5_000))
    }

    @Test
    fun emptyStateIsValid() {
        val json = ServiceAvailabilityDiagnostics.toJson(ServiceAvailabilityState.Initial, 0).toString()
        assertTrue("\"last_run\":null" in json)
    }
}
