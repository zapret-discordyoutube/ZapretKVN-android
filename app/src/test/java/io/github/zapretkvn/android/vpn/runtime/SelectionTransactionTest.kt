package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.diagnostics.RuntimeErrors
import io.github.zapretkvn.android.diagnostics.RuntimeFailure
import io.github.zapretkvn.android.engines.failover.FailoverTarget
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.network.probes.VpnDnsHealthException
import io.github.zapretkvn.networkbootstrap.CodedFailure
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Диагностика 31.10.2026: UDP до Hysteria2-сервера из сети пользователя не
 * доходил. Ручной выбор закреплялся до проверки, связь пропадала. Теперь
 * мёртвый кандидат возвращает прежний сервер (профиль не меняется), а сбой
 * не по вине сервера оставляет выбор с предупреждением.
 */
class SelectionTransactionTest {
    private val plan = FailoverPlan(
        groupTag = "zapret-proxy",
        currentId = "4stok",
        currentType = PathSupervisor.HYSTERIA_TYPE,
        targets = listOf(FailoverTarget("hipse", valid = true), FailoverTarget("4stok", valid = true)),
    )

    private class Coded(override val failureCode: String) : IOException("проверка не прошла"), CodedFailure {
        override val userMessage = "проверка не прошла"
        override val technicalDetail = "test"
    }

    private fun transaction(error: Throwable?, evidence: RuntimeFailure? = null) = SelectionTransaction<Unit>(
        "4stok",
        verify = { _, _ -> if (error != null) throw error },
        evidence = { evidence },
        plan = { _, tag -> plan.takeIf { tag == it.currentId } },
    )

    @Test
    fun `silent candidate is dead and the previous server is kept`() = runBlocking {
        val silent = Coded("VPN-200")
        val selection = transaction(silent)
        try {
            selection.check(Unit, "{}")
            fail("a dead candidate must fail the switch")
        } catch (dead: DeadServerException) {
            assertEquals(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT, dead.code)
            assertSame(silent, dead.cause)
            assertTrue(dead.explanation(), "VLESS или Trojan" in dead.explanation())
            assertTrue("UDP" in dead.explanation())
        }
        assertNull(selection.warning)
    }

    @Test
    fun `core evidence against the candidate is dead even for a dns failure`() = runBlocking {
        val evidence = RuntimeErrors.capture(
            "hysteria2", "https_probe",
            "outbound/hysteria2[4stok]: connect error: timeout: no recent network activity",
            targetId = "4stok",
        )
        val selection = transaction(VpnDnsHealthException("DNS не ответил", IOException("timeout")), evidence)
        try {
            selection.check(Unit, "{}")
            fail("named core failure of the candidate must fail the switch")
        } catch (dead: DeadServerException) {
            assertEquals(HysteriaFailureCode.TARGET_NETWORK_TIMEOUT, dead.code)
        }
    }

    @Test
    fun `failure that is not the server keeps the choice with a warning`() = runBlocking {
        val dns = VpnDnsHealthException("DNS не ответил", IOException("timeout"))
        val selection = transaction(dns)
        selection.check(Unit, "{}")
        assertSame(dns, selection.warning)
    }

    @Test
    fun `passing check commits without a warning`() = runBlocking {
        val selection = transaction(null)
        selection.check(Unit, "{}")
        assertNull(selection.warning)
    }

    @Test
    fun `cancellation is never turned into a verdict`() = runBlocking {
        val selection = transaction(CancellationException("superseded"))
        try {
            selection.check(Unit, "{}")
            fail("cancellation must propagate")
        } catch (cancelled: CancellationException) {
            assertNull(selection.warning)
        }
    }
}
