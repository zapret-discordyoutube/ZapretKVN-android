package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.ui.home.ReportButtonState
import io.github.zapretkvn.android.ui.home.toUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeTargetsTest {
    private val config = ParsedConfig.parse(
        """
        {"outbounds":[
          {"type":"vless","tag":"nl","server":"nl.node.example","server_port":8443,
           "tls":{"enabled":true,"server_name":"www.microsoft.com","reality":{"enabled":true}}},
          {"type":"vless","tag":"plain","server":"203.0.113.7","server_port":80},
          {"type":"hysteria2","tag":"hy","server":"hy.node.example","server_port":443,
           "tls":{"enabled":true,"server_name":"hy.node.example"}},
          {"type":"selector","tag":"proxy","outbounds":["nl","hy"]}
        ],
        "endpoints":[{"type":"wireguard","tag":"awg","peers":[{"address":"198.51.100.9","port":51820}]}]}
        """.trimIndent(),
    )

    @Test
    fun aRealityServerIsProbedWithItsTlsName() {
        assertEquals(ProbeTarget("nl.node.example", 8443, "www.microsoft.com", usesTls = true, udp = false), ProbeTargets.of(config, "nl"))
        assertEquals(ProbeTarget("203.0.113.7", 80, null, usesTls = false, udp = false), ProbeTargets.of(config, "plain"))
    }

    @Test
    fun udpServersHaveNoTcpTlsStep() {
        assertEquals(ProbeTarget("hy.node.example", 443, "hy.node.example", usesTls = false, udp = true), ProbeTargets.of(config, "hy"))
        assertEquals(ProbeTarget("198.51.100.9", 51820, null, usesTls = false, udp = true), ProbeTargets.of(config, "awg"))
    }

    @Test
    fun groupsAndUnknownTagsAreNotTargets() {
        assertNull(ProbeTargets.of(config, "proxy"))
        assertNull(ProbeTargets.of(config, "missing"))
    }

    @Test
    fun theScreenHidesTheButtonForForeignServersAndAfterSending() {
        fun ui(status: SendStatus) = FailureReport(1, checking = false, diagnosisText = "x", detail = null, status = status)
        assertEquals(ReportButtonState.Hidden, ui(SendStatus.NotOurs).toUi().button)
        assertEquals(ReportButtonState.Sent, ui(SendStatus.Sent).toUi().button)
        assertEquals(ReportButtonState.Queued, ui(SendStatus.Queued).toUi().button)
        assertEquals("x", ui(SendStatus.Available).toUi().diagnosis)
    }
}
