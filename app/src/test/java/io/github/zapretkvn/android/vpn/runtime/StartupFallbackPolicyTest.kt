package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.diagnostics.RuntimeErrors
import io.github.zapretkvn.android.diagnostics.RuntimeFailure
import io.github.zapretkvn.android.diagnostics.RuntimeStartupFailure
import io.github.zapretkvn.android.engines.failover.FailoverTarget
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.network.VpnNetworkLostException
import io.github.zapretkvn.android.network.probes.VpnDnsHealthException
import io.github.zapretkvn.android.network.probes.VpnHealthTimeoutException
import io.github.zapretkvn.networkbootstrap.CodedFailure
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupFallbackPolicyTest {
    private fun plan(type: String = PathSupervisor.HYSTERIA_TYPE) = FailoverPlan(
        groupTag = "zapret-proxy",
        currentId = "tinl",
        currentType = type,
        targets = listOf(FailoverTarget("tinl", valid = true), FailoverTarget("tinul", valid = true)),
    )

    private class Coded(override val failureCode: String) : IOException("проверка не прошла"), CodedFailure {
        override val userMessage = "проверка не прошла"
        override val technicalDetail = "nl2"
    }

    private fun evidence(component: String, target: String, message: String): RuntimeFailure =
        RuntimeErrors.capture(component, "https_probe", message, targetId = target)

    private val serverTimeout = evidence(
        "hysteria2",
        "tinl",
        "outbound/hysteria2[tinl]: connect error: timeout: no recent network activity",
    )
    private val groupTimeout = evidence(
        "selector",
        "zapret-proxy",
        "connection: open connection to example.org:443 using outbound/selector[zapret-proxy]: " +
            "connect error: timeout: no recent network activity",
    )
    private val authRejected = evidence(
        "hysteria2",
        "tinl",
        "outbound/hysteria2[tinl]: authentication error, HTTP status code: 301",
    )

    private fun code(error: Throwable, evidence: RuntimeFailure?, plan: FailoverPlan? = plan()) =
        StartupFallbackPolicy.deadServerCode(error, evidence, plan)

    @Test
    fun `failed traffic probe means the selected server is dead even without core evidence`() {
        assertEquals(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT, code(Coded("VPN-200"), null))
        assertEquals(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT, code(VpnHealthTimeoutException(), null))
        assertEquals(
            HysteriaFailureCode.TARGET_NETWORK_TIMEOUT,
            code(RuntimeStartupFailure(Coded("VPN-200"), serverTimeout), serverTimeout),
        )
    }

    @Test
    fun `dns probe failure blames the server only when the core names it`() {
        val dns = VpnDnsHealthException("DNS через VPN не отвечает")
        val untargeted = evidence("sing-box", "", "dns: exchange failed: context deadline exceeded")
        val otherServer = evidence(
            "hysteria2",
            "tinul",
            "outbound/hysteria2[tinul]: connect error: timeout: no recent network activity",
        )

        assertEquals(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT, code(dns, serverTimeout))
        assertEquals(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT, code(dns, groupTimeout))
        assertNull(code(dns, null))
        assertNull(code(dns, untargeted))
        assertNull(code(dns, otherServer))
    }

    @Test
    fun `hysteria key rejection is terminal and never replaced`() {
        assertNull(code(Coded("VPN-200"), authRejected))
        assertNull(code(VpnDnsHealthException("DNS через VPN не отвечает"), authRejected))
    }

    @Test
    fun `security noise of other protocols does not block the fallback`() {
        val vlessAuth = evidence("vless", "tinl", "outbound/vless[tinl]: authentication error, HTTP status code: 301")

        assertEquals(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT, code(Coded("VPN-200"), vlessAuth, plan("vless")))
    }

    @Test
    fun `network and policy failures are not about the server`() {
        assertNull(code(VpnNetworkLostException(), serverTimeout))
        listOf("NET-101", "NET-110", "DNS-101", "DNS-110", "VPN-201", "SRV-100").forEach { typed ->
            assertNull(typed, code(Coded(typed), serverTimeout))
        }
        assertNull(code(ConnectionStartupTimeoutException(45_000), serverTimeout))
        assertNull(code(IllegalStateException("libbox не передал внутренний DNS TUN."), serverTimeout))
    }

    @Test
    fun `profile without a switchable group has no fallback`() {
        assertNull(code(Coded("VPN-200"), serverTimeout, plan = null))
    }

    @Test
    fun `fallback budget holds two full candidate checks and no partial one`() {
        val budget = StartupFallbackPolicy.budgetMillis(verifyMillis = 20_000)

        assertTrue(StartupFallbackPolicy.fitsBudget(budget, verifyMillis = 20_000))
        assertTrue(StartupFallbackPolicy.fitsBudget(budget / 2, verifyMillis = 20_000))
        assertFalse(StartupFallbackPolicy.fitsBudget(budget / 2 - 1, verifyMillis = 20_000))
    }

    @Test
    fun `fallback time is not charged to the startup budget`() = runBlocking {
        var now = 0L
        val budget = StartupBudget({ now }, baseMillis = 45_000)
        now = 27_000

        val inside = budget.apart(capMillis = 46_000) {
            assertEquals(46_000, budget.remainingMillis())
            now += 30_000
            budget.remainingMillis()
        }

        assertEquals(16_000, inside)
        // Следующему DNS-режиму и финализации осталось столько же, сколько до перебора.
        assertEquals(18_000, budget.remainingMillis())
    }

    @Test
    fun `startup budget returns the result, rethrows failures and cancels on expiry`() = runBlocking {
        assertEquals("ok", StartupBudget(System::currentTimeMillis, baseMillis = 5_000).run { "ok" })
        val failure = runCatching {
            StartupBudget(System::currentTimeMillis, baseMillis = 5_000).run<Unit> { throw Coded("VPN-200") }
        }.exceptionOrNull()
        assertTrue(failure is Coded)

        var cleaned = false
        val expired = StartupBudget(System::currentTimeMillis, baseMillis = 50).run {
            try {
                delay(10_000)
            } finally {
                cleaned = true
            }
        }
        assertNull(expired)
        assertTrue(cleaned)
    }
}
