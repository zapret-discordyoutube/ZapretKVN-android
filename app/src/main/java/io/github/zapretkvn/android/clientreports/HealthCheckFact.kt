package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.android.network.probes.VpnHealthTimeoutException
import io.github.zapretkvn.android.network.probes.VpnServerSilentException
import io.github.zapretkvn.android.vpn.runtime.StartupBudgetExhausted
import io.github.zapretkvn.clientreports.ReportCheck
import io.github.zapretkvn.clientreports.ReportChecks

/**
 * The app's own health check through the tunnel as the `health` probe (vpnbot 2026-11-20): a fact the app already
 * has when the core started, gave no code and no data came back. It costs no traffic, so no rarity limit applies.
 *
 * `timeout`: no answer before the app gave up (the check's deadline, the startup budget, or not one byte through the
 * server). `reset`: the flow through the tunnel was closed; the TUN completes the connect locally, so it is never a
 * refusal. `unknown`: a failure that proves nothing about data (DNS inside the tunnel, an unexpected HTTP status).
 */
internal object HealthCheckFact {
    fun of(error: Throwable, waitedMillis: Long): ReportCheck {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val silent = chain.any { it is VpnHealthTimeoutException || it is StartupBudgetExhausted || it is VpnServerSilentException }
        val result = if (silent) ReportChecks.TIMEOUT else ProbeErrors.result(error, connected = true)
        return ReportCheck(result, waitedMillis.coerceIn(0, ReportChecks.MAX_CHECK_MS))
    }
}
