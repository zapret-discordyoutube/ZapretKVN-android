package io.github.zapretkvn.android.vpn

import android.os.SystemClock
import io.github.zapretkvn.android.diagnostics.CoreDiagnosticClassifier
import io.github.zapretkvn.android.diagnostics.DiagnosticAttemptOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticConnectionAttempt
import io.github.zapretkvn.android.diagnostics.DiagnosticFailure
import io.github.zapretkvn.android.diagnostics.DiagnosticFailureClassifier
import io.github.zapretkvn.android.diagnostics.DiagnosticLogCategory
import io.github.zapretkvn.android.diagnostics.DiagnosticLogLine
import io.github.zapretkvn.android.diagnostics.DiagnosticLogSource
import io.github.zapretkvn.android.diagnostics.DiagnosticLogStats
import io.github.zapretkvn.android.diagnostics.DiagnosticNetworkState
import io.github.zapretkvn.android.diagnostics.DiagnosticReportRedactor
import io.github.zapretkvn.android.diagnostics.DiagnosticRuntimeMap
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.diagnostics.DiagnosticStageTiming
import io.github.zapretkvn.android.diagnostics.DiagnosticState
import io.github.zapretkvn.android.diagnostics.DiagnosticStopAttempt
import io.github.zapretkvn.android.diagnostics.DiagnosticStopOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticVpnPolicy
import io.github.zapretkvn.android.diagnostics.MAX_DIAGNOSTIC_ATTEMPTS
import io.github.zapretkvn.android.diagnostics.MAX_DIAGNOSTIC_LOG_LINES
import io.github.zapretkvn.android.diagnostics.MAX_DIAGNOSTIC_LOG_LINE_CHARS
import io.github.zapretkvn.android.diagnostics.MAX_DIAGNOSTIC_STAGES
import io.github.zapretkvn.android.diagnostics.MAX_DIAGNOSTIC_STARTUP_LOG_LINES
import io.github.zapretkvn.android.diagnostics.RuntimeErrorJournal
import io.github.zapretkvn.android.diagnostics.RuntimeErrors
import io.github.zapretkvn.android.diagnostics.appendPrioritizedBounded
import io.github.zapretkvn.android.network.UnderlyingNetworkState
import io.github.zapretkvn.android.network.probes.VpnHealthStage
import io.github.zapretkvn.android.platform.VpnSystemPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Журнал диагностики подключения: стадии попытки и остановки, журнал ядра и
 * приложения, отказы. Единственный владелец [DiagnosticState]; [VpnController]
 * делегирует сюда, не зная устройства диагностики.
 *
 * Поколение сессии хранит контроллер: рекордер читает его через
 * [currentGeneration] и сам его не меняет.
 */
internal class ConnectionDiagnosticsRecorder(
    initial: DiagnosticState,
    private val currentGeneration: () -> Long,
    private val runtimeErrors: RuntimeErrorJournal,
) {
    private val mutable = MutableStateFlow(initial)
    val diagnostics: StateFlow<DiagnosticState> = mutable.asStateFlow()

    @Volatile
    private var diagnosticRuntimeMap: DiagnosticRuntimeMap? = null

    /** Новая сессия: карта целей прежней больше не годится. */
    fun resetRuntimeMap() {
        diagnosticRuntimeMap = null
    }

    /**
     * Сообщение пользователю (snackbar) тоже идёт в журнал приложения.
     * @return очищенный текст, который контроллер публикует в [VpnController.message].
     */
    fun logUserMessage(message: String): String {
        val safe = sanitizeDiagnosticText(message, MAX_MESSAGE_CHARS)
        appendApplicationDiagnosticLog(level = 5, message = safe, generation = currentGeneration())
        return safe
    }

    /**
     * Провал попытки, после которого сервис остаётся жив и переподключается сам:
     * фиксируется отказ, но терминальное состояние не публикуется.
     */
    fun recordRecoverableFailure(generation: Long, failure: VpnConnectionState.Error) {
        if (generation < currentGeneration()) return
        val message = sanitizeDiagnosticText(failure.message, MAX_MESSAGE_CHARS)
        recordConnectionFailure(
            generation,
            failure.copy(
                message = message,
                code = VpnFailureCodeSanitizer.sanitize(failure.code).ifBlank {
                    DiagnosticFailureClassifier.classify(message).supportCode
                },
                technicalDetail = failure.technicalDetail
                    ?.let { sanitizeDiagnosticText(it, 240) }
                    ?.takeIf(String::isNotBlank),
            ),
        )
    }

    internal fun beginConnectionDiagnostic(
        generation: Long,
        trigger: String,
        profileId: String? = null,
    ) {
        if (generation != currentGeneration()) return
        val elapsed = SystemClock.elapsedRealtime()
        val epoch = System.currentTimeMillis()
        val initialRuntimeMap = DiagnosticRuntimeMap.profileOnly(profileId)
        diagnosticRuntimeMap = initialRuntimeMap
        mutable.update { current ->
            val previous = current.connectionAttempt?.finishForReplacement(elapsed)
            val history = (current.previousConnectionAttempts + listOfNotNull(previous))
                .takeLast(MAX_DIAGNOSTIC_ATTEMPTS - 1)
            current.copy(
                generation = generation,
                lastFailure = null,
                coreLogs = emptyList(),
                coreLogStats = DiagnosticLogStats(),
                logStreamActive = false,
                network = null,
                vpnPolicy = null,
                effectiveOverlay = null,
                previousConnectionAttempts = history,
                connectionAttempt = DiagnosticConnectionAttempt(
                    generation = generation,
                    trigger = trigger.take(40),
                    startedAtEpochMillis = epoch,
                    startedAtElapsedRealtimeMillis = elapsed,
                    target = initialRuntimeMap?.profile,
                ),
            )
        }
    }

    internal fun attachDiagnosticRuntimeMap(
        generation: Long,
        runtimeMap: DiagnosticRuntimeMap,
    ) {
        if (generation != currentGeneration()) return
        diagnosticRuntimeMap = runtimeMap
        mutable.update { current ->
            val attempt = current.connectionAttempt
                ?.takeIf { it.generation == generation }
                ?: return@update current
            current.copy(
                connectionAttempt = attempt.copy(target = runtimeMap.profile),
            )
        }
    }

    internal fun startConnectionDiagnosticStage(
        generation: Long,
        key: String,
        label: String,
    ) {
        val elapsed = SystemClock.elapsedRealtime()
        val epoch = System.currentTimeMillis()
        mutable.update { current ->
            val attempt = current.connectionAttempt
                ?.takeIf { it.generation == generation && it.outcome == DiagnosticAttemptOutcome.Running }
                ?: return@update current
            // HTTPS-проба идёт параллельно DNS-пробе: её старт не закрывает DNS-этап,
            // тот завершается своим отчётом, чтобы длительность каждого была честной.
            val completed = attempt.stages.completeRunningStage(
                elapsed = elapsed,
                status = DiagnosticStageStatus.Success,
                keepRunning = { running -> key == HTTPS_PROBE_STAGE_KEY && running.key in PARALLEL_DNS_STAGE_KEYS },
            )
            val stage = DiagnosticStageTiming(
                key = key.take(48),
                label = label.take(80),
                startedAtEpochMillis = epoch,
                startedAtElapsedRealtimeMillis = elapsed,
                attempt = generation,
                target = diagnosticRuntimeMap?.resolve("") ?: attempt.target,
            )
            current.copy(
                connectionAttempt = attempt.copy(
                    stages = (completed + stage).takeLast(MAX_DIAGNOSTIC_STAGES),
                ),
            )
        }
    }

    /**
     * Записывает уже завершённую стадию, которая шла параллельно другим
     * (например, проверка конфигурации ядром рядом с созданием TUN-адаптера):
     * последовательная [startConnectionDiagnosticStage] закрыла бы её досрочно.
     * Стадия встаёт в список по времени начала.
     */
    internal fun recordConnectionDiagnosticStage(
        generation: Long,
        key: String,
        label: String,
        startedAtElapsedRealtimeMillis: Long,
        startedAtEpochMillis: Long,
        durationMillis: Long,
        status: DiagnosticStageStatus,
        detail: String? = null,
    ) {
        val safeDetail = detail
            ?.let { sanitizeDiagnosticText(it, MAX_DIAGNOSTIC_STAGE_DETAIL_CHARS) }
            ?.takeIf(String::isNotBlank)
        mutable.update { current ->
            val attempt = current.connectionAttempt
                ?.takeIf { it.generation == generation && it.outcome == DiagnosticAttemptOutcome.Running }
                ?: return@update current
            val stage = DiagnosticStageTiming(
                key = key.take(48),
                label = label.take(80),
                startedAtEpochMillis = startedAtEpochMillis,
                startedAtElapsedRealtimeMillis = startedAtElapsedRealtimeMillis,
                durationMillis = durationMillis.coerceAtLeast(0L),
                status = status,
                detail = safeDetail,
                attempt = generation,
                target = diagnosticRuntimeMap?.resolve("") ?: attempt.target,
            )
            val before = attempt.stages.takeWhile { it.startedAtElapsedRealtimeMillis <= startedAtElapsedRealtimeMillis }
            val after = attempt.stages.drop(before.size)
            current.copy(
                connectionAttempt = attempt.copy(
                    stages = (before + stage + after).takeLast(MAX_DIAGNOSTIC_STAGES),
                ),
            )
        }
    }

    internal fun beginConnectionCandidate(generation: Long, candidateAttemptId: Int) {
        mutable.update { current ->
            val attempt = current.connectionAttempt
                ?.takeIf { it.generation == generation && it.outcome == DiagnosticAttemptOutcome.Running }
                ?: return@update current
            current.copy(
                connectionAttempt = attempt.copy(candidateAttemptId = candidateAttemptId.coerceAtLeast(1)),
            )
        }
    }

    internal fun recordConnectionVpnNetwork(
        generation: Long,
        identity: String? = null,
        lost: Boolean = false,
    ) {
        mutable.update { current ->
            val attempt = current.connectionAttempt
                ?.takeIf { it.generation == generation && it.outcome == DiagnosticAttemptOutcome.Running }
                ?: return@update current
            current.copy(
                connectionAttempt = attempt.copy(
                    vpnNetworkIdentity = identity?.take(40) ?: attempt.vpnNetworkIdentity,
                    vpnNetworkLost = attempt.vpnNetworkLost || lost,
                ),
            )
        }
    }

    internal fun finishConnectionDiagnosticStage(
        generation: Long,
        key: String,
        status: DiagnosticStageStatus,
        detail: String? = null,
    ) {
        val elapsed = SystemClock.elapsedRealtime()
        val safeDetail = detail
            ?.let { sanitizeDiagnosticText(it, MAX_DIAGNOSTIC_STAGE_DETAIL_CHARS) }
            ?.takeIf(String::isNotBlank)
        mutable.update { current ->
            val attempt = current.connectionAttempt
                ?.takeIf { it.generation == generation && it.outcome == DiagnosticAttemptOutcome.Running }
                ?: return@update current
            val safeKey = key.take(48)
            current.copy(
                connectionAttempt = attempt.copy(
                    stages = attempt.stages.map { stage ->
                        if (stage.key == safeKey && stage.status == DiagnosticStageStatus.Running) {
                            stage.copy(
                                durationMillis = (elapsed - stage.startedAtElapsedRealtimeMillis)
                                    .coerceAtLeast(0L),
                                status = status,
                                detail = safeDetail,
                                attempt = stage.attempt ?: generation,
                                target = stage.target ?: attempt.target,
                            )
                        } else {
                            stage
                        }
                    },
                ),
            )
        }
    }

    internal fun cancelCurrentConnectionDiagnostic(reason: String = "superseded") {
        finishConnectionDiagnostic(
            generation = null,
            outcome = DiagnosticAttemptOutcome.Cancelled,
            stageStatus = DiagnosticStageStatus.Cancelled,
            cancellationReason = reason,
        )
    }

    internal fun beginStopDiagnostic(generation: Long, trigger: String) {
        val elapsed = SystemClock.elapsedRealtime()
        val epoch = System.currentTimeMillis()
        mutable.update { current ->
            current.copy(
                generation = generation,
                stopAttempt = DiagnosticStopAttempt(
                    generation = generation,
                    trigger = trigger.take(40),
                    startedAtEpochMillis = epoch,
                    startedAtElapsedRealtimeMillis = elapsed,
                    target = diagnosticRuntimeMap?.resolve("")
                        ?: current.connectionAttempt?.target,
                ),
            )
        }
    }

    internal fun startStopDiagnosticStage(
        generation: Long,
        key: String,
        label: String,
    ) {
        val elapsed = SystemClock.elapsedRealtime()
        val epoch = System.currentTimeMillis()
        mutable.update { current ->
            val attempt = current.stopAttempt
                ?.takeIf { it.generation == generation && it.outcome == DiagnosticStopOutcome.Running }
                ?: return@update current
            val completed = attempt.stages.completeRunningStage(
                elapsed = elapsed,
                status = DiagnosticStageStatus.Success,
            )
            current.copy(
                stopAttempt = attempt.copy(
                    stages = (completed + DiagnosticStageTiming(
                        key = key.take(48),
                        label = label.take(80),
                        startedAtEpochMillis = epoch,
                        startedAtElapsedRealtimeMillis = elapsed,
                        attempt = generation,
                        target = attempt.target,
                    )).takeLast(MAX_DIAGNOSTIC_STAGES),
                ),
            )
        }
    }

    internal fun finishStopDiagnosticStage(
        generation: Long,
        key: String,
        error: Throwable? = null,
    ) {
        val elapsed = SystemClock.elapsedRealtime()
        val safeKey = key.take(48)
        val detail = error?.let {
            sanitizeDiagnosticText(
                generateSequence(it) { cause -> cause.cause }
                    .mapNotNull(Throwable::message)
                    .firstOrNull(String::isNotBlank)
                    ?: it.javaClass.simpleName,
                MAX_DIAGNOSTIC_STAGE_DETAIL_CHARS,
            )
        }
        mutable.update { current ->
            val attempt = current.stopAttempt
                ?.takeIf { it.generation == generation && it.outcome == DiagnosticStopOutcome.Running }
                ?: return@update current
            current.copy(
                stopAttempt = attempt.copy(
                    stages = attempt.stages.map { stage ->
                        if (stage.key == safeKey && stage.status == DiagnosticStageStatus.Running) {
                            stage.copy(
                                durationMillis = (elapsed - stage.startedAtElapsedRealtimeMillis)
                                    .coerceAtLeast(0L),
                                status = if (error == null) {
                                    DiagnosticStageStatus.Success
                                } else {
                                    DiagnosticStageStatus.Failed
                                },
                                detail = detail,
                                attempt = stage.attempt ?: generation,
                                target = stage.target ?: attempt.target,
                            )
                        } else {
                            stage
                        }
                    },
                ),
            )
        }
    }

    internal fun completeStopDiagnostic(generation: Long) {
        val elapsed = SystemClock.elapsedRealtime()
        mutable.update { current ->
            val attempt = current.stopAttempt
                ?.takeIf { it.generation == generation && it.outcome == DiagnosticStopOutcome.Running }
                ?: return@update current
            current.copy(
                stopAttempt = attempt.copy(
                    totalDurationMillis = (elapsed - attempt.startedAtElapsedRealtimeMillis)
                        .coerceAtLeast(0L),
                    outcome = DiagnosticStopOutcome.Completed,
                    stages = attempt.stages.completeRunningStage(
                        elapsed,
                        DiagnosticStageStatus.Success,
                    ),
                ),
            )
        }
    }

    /** Решение автоматики без ошибки: только в диагностику, без snackbar. */
    internal fun publishDiagnosticInfo(message: String) {
        val safe = sanitizeDiagnosticText(message, 360)
        appendApplicationDiagnosticLog(level = 4, message = safe, generation = currentGeneration())
    }

    internal fun publishDiagnosticWarning(message: String) {
        val safe = sanitizeDiagnosticText(message, 360)
        appendApplicationDiagnosticLog(level = 3, message = safe, generation = currentGeneration())
    }

    internal fun publishDiagnosticNetwork(generation: Long, state: UnderlyingNetworkState) {
        if (generation < currentGeneration()) return
        mutable.update {
            it.copy(
                generation = generation,
                network = DiagnosticNetworkState(
                    available = state.network != null,
                    transport = state.transport,
                    interfaceName = state.interfaceName,
                    metered = state.metered,
                    validated = state.validated,
                    captivePortal = state.captivePortal,
                    privateDnsMode = state.privateDnsMode.name.lowercase(),
                    privateDnsActive = state.privateDnsActive,
                ),
            )
        }
    }

    internal fun publishVpnSystemPolicy(generation: Long, policy: VpnSystemPolicy) {
        if (generation < currentGeneration()) return
        mutable.update {
            it.copy(
                generation = generation,
                vpnPolicy = DiagnosticVpnPolicy(
                    statusAvailable = policy.statusAvailable,
                    alwaysOn = policy.alwaysOn,
                    lockdown = policy.lockdown,
                ),
            )
        }
    }

    internal fun publishEffectiveOverlay(generation: Long, overlay: String) {
        if (generation < currentGeneration()) return
        mutable.update { it.copy(generation = generation, effectiveOverlay = overlay) }
    }

    internal fun clearCoreDiagnosticLogs(generation: Long) {
        if (generation != currentGeneration()) return
        mutable.update {
            it.copy(
                coreLogs = emptyList(),
                coreLogStats = DiagnosticLogStats(),
            )
        }
    }

    internal fun publishCoreDiagnosticLog(generation: Long, level: Int, message: String) {
        publishCoreDiagnosticLogs(generation, listOf(level to message), ingressDropped = 0)
    }

    internal fun recordCoreFailure(generation: Long, level: Int, message: String) {
        if (level !in 0..3) return
        val tagged = Regex("outbound/([^\\[]+)\\[([^]]+)]").find(message)
        val component = tagged?.groupValues?.get(1) ?: "sing-box"
        val stage = mutable.value.connectionAttempt
            ?.takeIf { it.generation == generation }
            ?.stages?.lastOrNull { it.status == DiagnosticStageStatus.Running }?.key ?: "runtime"
        val failure = RuntimeErrors.capture(
            component = component,
            stage = stage,
            message = message,
            sessionGeneration = generation,
            targetId = tagged?.groupValues?.get(2).orEmpty(),
            level = level,
        )
        // Отмена QUIC-потока (error code 0) — штатное закрытие; строка остаётся в журнале ядра.
        if (RuntimeErrors.isBenignStreamCancel(failure)) return
        runtimeErrors.record(failure)
        mutable.update { it.copy(runtimeErrors = runtimeErrors.entries.value) }
    }

    internal fun recordCommandLogDisconnect(generation: Long, message: String, expectedClose: Boolean) {
        val failure = RuntimeErrors.commandLogFailure(generation, message, expectedClose)
        if (failure == null) {
            // A locally requested close is lifecycle evidence, not a runtime failure.
            publishCoreDiagnosticLog(generation, 4, "CommandLog: $message")
        } else {
            runtimeErrors.record(failure)
            mutable.update { it.copy(runtimeErrors = runtimeErrors.entries.value) }
        }
    }

    internal fun publishCoreDiagnosticLogs(
        generation: Long,
        entries: List<Pair<Int, String>>,
        ingressDropped: Int,
    ) {
        if (generation != currentGeneration()) return
        val attemptSnapshot = mutable.value.connectionAttempt
            ?.takeIf { it.generation == generation }
        val currentStage = attemptSnapshot
            ?.stages
            ?.lastOrNull { it.status == DiagnosticStageStatus.Running }
            ?.key
        val runtimeMap = diagnosticRuntimeMap
        val lines = entries.mapNotNull { (level, message) ->
            val target = runtimeMap?.resolve(message) ?: attemptSnapshot?.target
            val safe = sanitizeDiagnosticText(message, MAX_DIAGNOSTIC_LOG_LINE_CHARS)
            safe.takeIf(String::isNotEmpty)?.let {
                CoreDiagnosticClassifier.classify(level, it).copy(
                    attempt = generation,
                    stage = currentStage,
                    target = target,
                )
            }
        }
        if (lines.isEmpty() && ingressDropped <= 0) return
        mutable.update { current ->
            var coreLogs = current.coreLogs
            var coreStats = current.coreLogStats
            var startupLogs = current.connectionAttempt?.startupCoreLogs.orEmpty()
            var startupStats = current.connectionAttempt?.startupCoreLogStats
                ?: DiagnosticLogStats()
            lines.forEach { line ->
                coreLogs.appendPrioritizedBounded(line, coreStats, MAX_DIAGNOSTIC_LOG_LINES).also {
                    coreLogs = it.lines
                    coreStats = it.stats
                }
                if (current.connectionAttempt?.outcome == DiagnosticAttemptOutcome.Running) {
                    startupLogs.appendPrioritizedBounded(
                        line,
                        startupStats,
                        MAX_DIAGNOSTIC_STARTUP_LOG_LINES,
                    ).also {
                        startupLogs = it.lines
                        startupStats = it.stats
                    }
                }
            }
            if (ingressDropped > 0) {
                coreStats = coreStats.copy(
                    receivedLines = coreStats.receivedLines + ingressDropped,
                    droppedLines = coreStats.droppedLines + ingressDropped,
                )
                if (current.connectionAttempt?.outcome == DiagnosticAttemptOutcome.Running) {
                    startupStats = startupStats.copy(
                        receivedLines = startupStats.receivedLines + ingressDropped,
                        droppedLines = startupStats.droppedLines + ingressDropped,
                    )
                }
            }
            val startupAttempt = current.connectionAttempt?.let { attempt ->
                if (attempt.outcome == DiagnosticAttemptOutcome.Running) {
                    attempt.copy(
                        startupCoreLogs = startupLogs,
                        startupCoreLogStats = startupStats,
                    )
                } else {
                    attempt
                }
            }
            current.copy(
                coreLogs = coreLogs,
                coreLogStats = coreStats,
                connectionAttempt = startupAttempt,
            )
        }
    }

    internal fun publishDiagnosticLogStream(generation: Long, active: Boolean) {
        if (generation < currentGeneration()) return
        mutable.update { it.copy(logStreamActive = active) }
    }

    internal fun updateDiagnosticConnectionState(
        generation: Long,
        state: VpnConnectionState,
    ) {
        when (state) {
            is VpnConnectionState.Starting -> mutable.update { current ->
                if (generation > current.generation) {
                    current.copy(
                        generation = generation,
                        lastFailure = null,
                        coreLogs = emptyList(),
                        coreLogStats = DiagnosticLogStats(),
                        logStreamActive = false,
                        network = null,
                        vpnPolicy = null,
                        effectiveOverlay = null,
                    )
                } else {
                    current
                }
            }
            is VpnConnectionState.Error -> recordConnectionFailure(generation, state)
            is VpnConnectionState.Reconnecting -> publishDiagnosticLogStream(generation, false)
            is VpnConnectionState.Paused -> {
                cancelCurrentConnectionDiagnostic()
                publishDiagnosticLogStream(generation, false)
            }
            VpnConnectionState.Stopped,
            is VpnConnectionState.Stopping,
            -> publishDiagnosticLogStream(generation, false)
            is VpnConnectionState.Connected -> finishConnectionDiagnostic(
                generation = generation,
                outcome = DiagnosticAttemptOutcome.Connected,
                stageStatus = DiagnosticStageStatus.Success,
                cancellationReason = null,
            )
        }
    }

    private fun recordConnectionFailure(generation: Long, state: VpnConnectionState.Error) {
        val runningAttempt = mutable.value.connectionAttempt
            ?.takeIf { it.generation == generation }
        val failureStage = runningAttempt
            ?.stages
            ?.lastOrNull { it.status == DiagnosticStageStatus.Running }
            ?.key
        val failureTarget = diagnosticRuntimeMap?.resolve("") ?: runningAttempt?.target
        finishConnectionDiagnostic(
            generation = generation,
            outcome = DiagnosticAttemptOutcome.Failed,
            stageStatus = DiagnosticStageStatus.Failed,
            cancellationReason = null,
        )
        val safe = sanitizeDiagnosticText(state.message, 360)
        val now = System.currentTimeMillis()
        val failure = DiagnosticFailure(
            type = DiagnosticFailureClassifier.classify(safe),
            supportCode = state.code,
            message = safe,
            technicalDetail = state.technicalDetail
                ?.let { sanitizeDiagnosticText(it, MAX_DIAGNOSTIC_STAGE_DETAIL_CHARS) }
                ?.takeIf(String::isNotBlank),
            occurredAtEpochMillis = now,
            attempt = generation,
            stage = failureStage,
            target = failureTarget,
        )
        val line = DiagnosticLogLine(
            level = 2,
            message = safe,
            receivedAtEpochMillis = now,
            source = DiagnosticLogSource.Application,
            category = DiagnosticLogCategory.Lifecycle,
            priority = true,
            attempt = generation,
            stage = failureStage,
            target = failureTarget,
        )
        mutable.update {
            val attempt = it.connectionAttempt
            val failedAttempt = if (attempt?.generation == generation) {
                attempt.copy(failure = failure)
            } else {
                attempt
            }
            val application = it.applicationLogs.appendPrioritizedBounded(
                line,
                it.applicationLogStats,
                MAX_DIAGNOSTIC_LOG_LINES,
            )
            it.copy(
                generation = generation,
                lastFailure = failure,
                applicationLogs = application.lines,
                applicationLogStats = application.stats,
                logStreamActive = false,
                connectionAttempt = failedAttempt,
            )
        }
    }

    private fun finishConnectionDiagnostic(
        generation: Long?,
        outcome: DiagnosticAttemptOutcome,
        stageStatus: DiagnosticStageStatus,
        cancellationReason: String?,
    ) {
        val elapsed = SystemClock.elapsedRealtime()
        mutable.update { current ->
            val attempt = current.connectionAttempt
                ?.takeIf {
                    it.outcome == DiagnosticAttemptOutcome.Running &&
                        (generation == null || it.generation == generation)
                }
                ?: return@update current
            current.copy(
                connectionAttempt = attempt.copy(
                    totalDurationMillis = (elapsed - attempt.startedAtElapsedRealtimeMillis)
                        .coerceAtLeast(0L),
                    outcome = outcome,
                    cancellationReason = cancellationReason?.take(40),
                    stages = attempt.stages.completeRunningStage(elapsed, stageStatus),
                ),
            )
        }
    }

    private fun List<DiagnosticStageTiming>.completeRunningStage(
        elapsed: Long,
        status: DiagnosticStageStatus,
        keepRunning: (DiagnosticStageTiming) -> Boolean = { false },
    ): List<DiagnosticStageTiming> = map { stage ->
        if (stage.status == DiagnosticStageStatus.Running && !keepRunning(stage)) {
            stage.copy(
                durationMillis = (elapsed - stage.startedAtElapsedRealtimeMillis).coerceAtLeast(0L),
                status = status,
            )
        } else {
            stage
        }
    }

    private fun DiagnosticConnectionAttempt.finishForReplacement(
        elapsed: Long,
    ): DiagnosticConnectionAttempt = if (outcome == DiagnosticAttemptOutcome.Running) {
        copy(
            totalDurationMillis = (elapsed - startedAtElapsedRealtimeMillis).coerceAtLeast(0L),
            outcome = DiagnosticAttemptOutcome.Cancelled,
            cancellationReason = "superseded",
            stages = stages.completeRunningStage(elapsed, DiagnosticStageStatus.Cancelled),
        )
    } else {
        this
    }

    internal fun appendApplicationDiagnosticLog(
        level: Int,
        message: String,
        generation: Long? = null,
    ) {
        if (message.isEmpty()) return
        val attempt = mutable.value.connectionAttempt
            ?.takeIf { generation == null || it.generation == generation }
        val stage = attempt
            ?.stages
            ?.lastOrNull { it.status == DiagnosticStageStatus.Running }
            ?.key
        val line = DiagnosticLogLine(
            level = level,
            message = message.take(MAX_DIAGNOSTIC_LOG_LINE_CHARS),
            receivedAtEpochMillis = System.currentTimeMillis(),
            source = DiagnosticLogSource.Application,
            category = DiagnosticLogCategory.Lifecycle,
            priority = level <= 3,
            attempt = generation ?: attempt?.generation,
            stage = stage,
            target = diagnosticRuntimeMap?.resolve("") ?: attempt?.target,
        )
        mutable.update {
            val result = it.applicationLogs.appendPrioritizedBounded(
                line,
                it.applicationLogStats,
                MAX_DIAGNOSTIC_LOG_LINES,
            )
            it.copy(applicationLogs = result.lines, applicationLogStats = result.stats)
        }
    }

    private companion object {
        const val MAX_DIAGNOSTIC_STAGE_DETAIL_CHARS = 160
        val HTTPS_PROBE_STAGE_KEY = VpnHealthStage.HttpsProbe.diagnosticKey

        /** DNS-этапы проверки, идущие параллельно HTTPS-пробе (см. VpnHealthPipeline). */
        val PARALLEL_DNS_STAGE_KEYS = setOf(
            VpnHealthStage.DnsUdpProbe.diagnosticKey,
            VpnHealthStage.DnsTcpProbe.diagnosticKey,
            VpnHealthStage.DnsAndroidProbe.diagnosticKey,
        )
    }
}

private const val MAX_MESSAGE_CHARS = 360

private val ANSI_ESCAPE = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|[@-_])")
private val NEW_LINES = Regex("[\\r\\n]+")

internal fun sanitizeDiagnosticText(message: String, maxLength: Int): String =
    DiagnosticReportRedactor.redact(message)
        .replace(ANSI_ESCAPE, "")
        .replace(NEW_LINES, " ")
        .trim()
        .take(maxLength)
