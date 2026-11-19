package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.config.BootstrapConfig
import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.network.probes.HealthCheckResult
import io.github.zapretkvn.android.network.probes.VpnHealthStageOutcome
import io.github.zapretkvn.android.diagnostics.RuntimeErrors
import io.github.zapretkvn.android.diagnostics.ServerAddressRedactor
import io.github.zapretkvn.android.network.VpnNetworkLostException
import io.github.zapretkvn.android.network.probes.VpnHealthTimeoutException
import io.github.zapretkvn.android.clientreports.ProbeTargets
import io.github.zapretkvn.clientreports.AttemptFacts
import io.github.zapretkvn.networkbootstrap.CodedFailure
import kotlinx.coroutines.CancellationException

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
        val startedAt = System.currentTimeMillis()
        val startedElapsed = android.os.SystemClock.elapsedRealtime()
        val result = try {
            runPipeline(session, config, dnsServer, reportStages)
        } catch (cancelled: CancellationException) {
            // Бюджет запуска кончился на проверке: сервер не дал рабочего трафика за отведённое время.
            // Это отказ, а не отмена; отмена — переключение или остановка.
            val facts = if (cancelled is StartupBudgetExhausted) {
                failureFacts(session, config, cancelled).copy(trafficFailed = true)
            } else {
                AttemptFacts(cancelled = true)
            }
            reportAttempt(session, config, startedAt, startedElapsed, facts)
            throw cancelled
        } catch (error: Throwable) {
            reportAttempt(session, config, startedAt, startedElapsed, failureFacts(session, config, error))
            throw error
        }
        reportAttempt(session, config, startedAt, startedElapsed, AttemptFacts(succeeded = true))
        return result
    }

    private suspend fun runPipeline(
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
            strictPrivateDns = session.networkPolicyKey.strictPrivateDns,
            transportWarm = session::proxyTrafficSeen,
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

    /** Выбранный в проверке сервер: тег и тип outbound; null — профиль без группы серверов. */
    private fun selected(session: CoreSession, config: ParsedConfig): Pair<String, String>? =
        runCatching {
            FailoverPlanner.plan(config, session.runtimeSelections(), session.primaryGroupTag)
                ?.let { it.currentId to it.currentType }
        }.getOrNull()

    /** Отчёт о попытке для status.zapret.moe ([io.github.zapretkvn.android.clientreports.ClientReporter]). */
    private fun reportAttempt(
        session: CoreSession,
        config: ParsedConfig,
        startedAt: Long,
        startedElapsed: Long,
        facts: AttemptFacts,
    ) {
        runCatching {
            val (tag, type) = selected(session, config) ?: return
            val udp = type.lowercase() in ProbeTargets.UDP_TYPES
            val withPreflight = facts.copy(
                udpTransport = udp,
                tcpPreflightFailed = container.clientReporter.preflightFailed(session.generation, tag),
            )
            container.clientReporter.onAttempt(
                tag = tag,
                outboundType = type,
                facts = withPreflight,
                elapsedMs = android.os.SystemClock.elapsedRealtime() - startedElapsed,
                attemptedAtMillis = startedAt,
                generation = session.generation,
                target = ProbeTargets.of(config, tag),
                // Пробы «нет данных» идут через туннель: только пока он тот же и обслуживает этот же сервер.
                stillServing = {
                    session.generation == container.vpnController.currentGeneration() && selected(session, config)?.first == tag
                },
            )
        }
    }

    /** Факты отказа: свидетельство ядра именно об этом сервере, потеря сети, провал трафика. */
    private fun failureFacts(session: CoreSession, config: ParsedConfig, error: Throwable): AttemptFacts {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val tag = selected(session, config)?.first
        val targets = listOfNotNull(tag, tag?.let(ServerAddressRedactor::redact))
        val evidence = RuntimeErrors.bestEvidence(container.vpnController.runtimeErrors.forGeneration(session.generation))
            ?.takeIf { it.targetId.isNotBlank() && it.targetId in targets }
        return AttemptFacts(
            coreCode = evidence?.code,
            networkLost = chain.any { it is VpnNetworkLostException },
            trafficFailed = chain.any {
                it is VpnHealthTimeoutException || (it as? CodedFailure)?.failureCode == TRAFFIC_PROBE_CODE
            },
        )
    }

    private companion object {
        const val TRAFFIC_PROBE_CODE = "VPN-200"
    }
}
