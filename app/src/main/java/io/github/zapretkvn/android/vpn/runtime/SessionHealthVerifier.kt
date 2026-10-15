package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.config.BootstrapConfig
import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.network.probes.HealthCheckResult
import io.github.zapretkvn.android.network.probes.VpnHealthStageOutcome

/**
 * Единственный вызов DNS/HTTPS-проверки работающей сессии: запуск и переключение
 * сервера проверяют одинаково. [reportStages] пишет этапы в диагностику
 * подключения; переключение в работающей сессии их не пишет, чтобы не
 * перезаписывать картину последнего запуска.
 */
internal class SessionHealthVerifier(private val container: AppContainer) {
    suspend fun verify(
        session: CoreSession,
        config: ParsedConfig,
        dnsServer: String,
        reportStages: Boolean,
    ): HealthCheckResult {
        val controller = container.vpnController
        val token = session.generation
        return container.vpnHealthPipeline.verify(
            mode = session.runtimeDnsMode,
            internalDnsServer = dnsServer,
            proxyIpFamily = BootstrapConfig.selectedProxyIpFamily(config),
            onNetworkLease = { identity -> controller.recordConnectionVpnNetwork(token, identity.toString()) },
            onNetworkLost = { controller.recordConnectionVpnNetwork(token, lost = true) },
            onStageStarted = { stage ->
                if (reportStages) {
                    controller.startConnectionDiagnosticStage(token, stage.diagnosticKey, stage.diagnosticLabel)
                }
            },
            onStageFinished = { stage, outcome, detail ->
                if (reportStages) {
                    controller.finishConnectionDiagnosticStage(
                        generation = token,
                        key = stage.diagnosticKey,
                        status = when (outcome) {
                            VpnHealthStageOutcome.Success -> DiagnosticStageStatus.Success
                            VpnHealthStageOutcome.Recovered -> DiagnosticStageStatus.Recovered
                            VpnHealthStageOutcome.Failed -> DiagnosticStageStatus.Failed
                        },
                        detail = detail,
                    )
                }
            },
        )
    }
}
