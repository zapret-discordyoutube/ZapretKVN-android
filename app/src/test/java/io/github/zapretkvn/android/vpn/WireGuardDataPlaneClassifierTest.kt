package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.diagnostics.DiagnosticErrorType
import io.github.zapretkvn.android.diagnostics.DiagnosticFailureClassifier
import io.github.zapretkvn.android.diagnostics.DiagnosticLogLine
import io.github.zapretkvn.android.diagnostics.DiagnosticLogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardDataPlaneClassifierTest {
    private val trafficFailure = VpnConnectionState.Error(
        message = "HTTPS-проверка через VPN не прошла: cloudflare:истёк тайм-аут 4000 мс.",
        code = "VPN-200",
    )

    @Test
    fun `handshake response with dead data plane becomes VPN-210`() {
        val logs = listOf(
            coreLog("DEBUG[0000] endpoint/wireguard[wireguard-out]: peer(LhqK…mEAw) - sending handshake initiation"),
            coreLog("DEBUG[0000] endpoint/wireguard[wireguard-out]: peer(LhqK…mEAw) - received handshake response"),
            coreLog("ERROR[0014] [2719068594 10.0s] dns: exchange failed for example.com. IN A: context deadline exceeded"),
            coreLog(
                "DEBUG[0015] endpoint/wireguard[wireguard-out]: peer(LhqK…mEAw) - " +
                    "retrying handshake because we stopped hearing back after 15 seconds",
            ),
        )

        val refined = WireGuardDataPlaneClassifier.refine(trafficFailure, logs)

        assertEquals("VPN-210", refined.code)
        val detail = requireNotNull(refined.technicalDetail)
        assertTrue(detail.startsWith("original=VPN-200"))
        assertTrue("received handshake response" in detail)
        assertTrue("stopped hearing back" in detail || "dns: exchange failed" in detail)
        assertEquals(
            DiagnosticErrorType.VpnTraffic,
            DiagnosticFailureClassifier.classify(refined.message),
        )
    }

    @Test
    fun `unanswered handshake becomes VPN-211`() {
        val logs = listOf(
            coreLog("DEBUG[0000] endpoint/wireguard[wireguard-out]: peer(N46Q…3FQ4) - sending handshake initiation"),
            coreLog(
                "DEBUG[0005] endpoint/wireguard[wireguard-out]: peer(N46Q…3FQ4) - " +
                    "handshake did not complete after 5 seconds, retrying (try 2)",
            ),
        )

        val refined = WireGuardDataPlaneClassifier.refine(trafficFailure, logs)

        assertEquals("VPN-211", refined.code)
        assertTrue("handshake did not complete" in requireNotNull(refined.technicalDetail))
        assertEquals(
            DiagnosticErrorType.VpnServer,
            DiagnosticFailureClassifier.classify(refined.message),
        )
    }

    @Test
    fun `vpn dns failure with handshake response also becomes VPN-210`() {
        val failure = VpnConnectionState.Error(
            message = "DNS через VPN не отвечает: DNS-101.",
            code = "DNS-200",
        )
        val logs = listOf(
            coreLog("DEBUG[0000] endpoint/wireguard[wireguard-out]: peer(LhqK…mEAw) - received handshake response"),
        )

        assertEquals("VPN-210", WireGuardDataPlaneClassifier.refine(failure, logs).code)
    }

    @Test
    fun `non wireguard transport keeps generic failure`() {
        val logs = listOf(
            coreLog("outbound/vless[proxy]: connection closed: EOF"),
            coreLog("ERROR[0014] dns: exchange failed for example.com. IN A: context deadline exceeded"),
        )

        assertEquals(trafficFailure, WireGuardDataPlaneClassifier.refine(trafficFailure, logs))
    }

    @Test
    fun `handshake sent without stall evidence keeps generic failure`() {
        val logs = listOf(
            coreLog("DEBUG[0000] endpoint/wireguard[wireguard-out]: peer(LhqK…mEAw) - sending handshake initiation"),
        )

        assertEquals(trafficFailure, WireGuardDataPlaneClassifier.refine(trafficFailure, logs))
    }

    @Test
    fun `non data plane codes are never refined`() {
        val authFailure = VpnConnectionState.Error(
            message = "VPN-сервер явно отклонил ключ или учётные данные.",
            code = "AUTH-100",
        )
        val logs = listOf(
            coreLog("DEBUG[0000] endpoint/wireguard[wireguard-out]: peer(LhqK…mEAw) - received handshake response"),
        )

        assertEquals(authFailure, WireGuardDataPlaneClassifier.refine(authFailure, logs))
    }

    private fun coreLog(message: String) = DiagnosticLogLine(
        level = 6,
        message = message,
        receivedAtEpochMillis = 1L,
        source = DiagnosticLogSource.Core,
    )
}
