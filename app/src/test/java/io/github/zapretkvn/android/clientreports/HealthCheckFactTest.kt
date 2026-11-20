package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.android.network.probes.VpnHealthTimeoutException
import io.github.zapretkvn.android.network.probes.VpnServerSilentException
import io.github.zapretkvn.android.vpn.runtime.StartupBudgetExhausted
import io.github.zapretkvn.clientreports.ReportCheck
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class HealthCheckFactTest {
    @Test
    fun noAnswerBeforeTheAppGaveUpIsATimeout() {
        assertEquals(ReportCheck("timeout", 20_000), HealthCheckFact.of(VpnHealthTimeoutException(), 20_000))
        assertEquals("timeout", HealthCheckFact.of(StartupBudgetExhausted(), 31_000).result)
        assertEquals("timeout", HealthCheckFact.of(VpnServerSilentException(IOException("dns")), 16_000).result)
        assertEquals("timeout", HealthCheckFact.of(IOException("https", SocketTimeoutException()), 8_000).result)
        assertEquals(60_000L, HealthCheckFact.of(VpnHealthTimeoutException(), 95_000).millis)
    }

    @Test
    fun throughTheTunnelADropIsAResetAndNeverARefusal() {
        assertEquals("reset", HealthCheckFact.of(IOException("https", EOFException()), 900).result)
        assertEquals("reset", HealthCheckFact.of(IOException("https", ConnectException("Connection refused")), 900).result)
    }

    @Test
    fun aFailureThatProvesNothingAboutDataIsUnknown() {
        assertEquals("unknown", HealthCheckFact.of(IOException("HTTP 403"), 700).result)
    }
}
