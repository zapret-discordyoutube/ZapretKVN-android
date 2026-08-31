package io.github.zapretkvn.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.zapretkvn.android.diagnostics.DiagnosticFailure
import io.github.zapretkvn.android.diagnostics.DiagnosticFailureClassifier
import io.github.zapretkvn.android.diagnostics.DiagnosticAttemptOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticConnectionAttempt
import io.github.zapretkvn.android.diagnostics.DiagnosticLogCategory
import io.github.zapretkvn.android.diagnostics.DiagnosticLogLine
import io.github.zapretkvn.android.diagnostics.DiagnosticLogSource
import io.github.zapretkvn.android.diagnostics.DiagnosticLogStats
import io.github.zapretkvn.android.diagnostics.DiagnosticNetworkState
import io.github.zapretkvn.android.diagnostics.DiagnosticState
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.diagnostics.DiagnosticStageTiming
import io.github.zapretkvn.android.diagnostics.DiagnosticStopAttempt
import io.github.zapretkvn.android.diagnostics.DiagnosticStopOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticVpnPolicy
import io.github.zapretkvn.android.diagnostics.AppCrashRecord
import io.github.zapretkvn.android.diagnostics.AppProcessExitReader
import io.github.zapretkvn.android.diagnostics.MAX_DIAGNOSTIC_ATTEMPTS
import io.github.zapretkvn.android.diagnostics.MAX_DIAGNOSTIC_LOG_LINES
import io.github.zapretkvn.android.diagnostics.MAX_DIAGNOSTIC_LOG_LINE_CHARS
import io.github.zapretkvn.android.diagnostics.MAX_DIAGNOSTIC_STARTUP_LOG_LINES
import io.github.zapretkvn.android.diagnostics.MAX_DIAGNOSTIC_STAGES
import io.github.zapretkvn.android.diagnostics.CoreDiagnosticClassifier
import io.github.zapretkvn.android.diagnostics.DiagnosticRuntimeMap
import io.github.zapretkvn.android.diagnostics.DiagnosticReportRedactor
import io.github.zapretkvn.android.diagnostics.appendPrioritizedBounded
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VpnController(
    private val context: Context,
    previousCrash: AppCrashRecord? = null,
) {
    private val mutableState = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Stopped)
    private val mutableGroups = MutableStateFlow<List<RuntimeSelectorGroup>>(emptyList())
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val mutableHomeVisible = MutableStateFlow(false)
    private val mutableDiagnosticsVisible = MutableStateFlow(false)
    private val mutableDiagnostics = MutableStateFlow(
        DiagnosticState(
            previousCrash = previousCrash,
            previousProcessExit = AppProcessExitReader.read(context),
        ),
    )
    private val trafficAccumulator = SessionTrafficAccumulator()
    private val mutableSessionStats = MutableStateFlow(trafficAccumulator.value)
    private val trafficLock = Any()
    private val latestGeneration = AtomicLong(0)
    @Volatile
    private var diagnosticRuntimeMap: DiagnosticRuntimeMap? = null

    val state: StateFlow<VpnConnectionState> = mutableState.asStateFlow()
    val selectorGroups: StateFlow<List<RuntimeSelectorGroup>> = mutableGroups.asStateFlow()
    val message: StateFlow<String?> = mutableMessage.asStateFlow()
    val sessionStats: StateFlow<VpnSessionStats> = mutableSessionStats.asStateFlow()
    val diagnostics: StateFlow<DiagnosticState> = mutableDiagnostics.asStateFlow()
    internal val homeVisible: StateFlow<Boolean> = mutableHomeVisible.asStateFlow()
    internal val diagnosticsVisible: StateFlow<Boolean> = mutableDiagnosticsVisible.asStateFlow()

    fun permissionIntent(): Intent? = VpnService.prepare(context)

    fun start(profileId: String) {
        require(profileId.isNotBlank()) { "Профиль не выбран." }
        ContextCompat.startForegroundService(
            context,
            ZapretVpnService.startIntent(context, profileId),
        )
    }

    fun switchProfileIfConnected(profileId: String): Boolean {
        require(profileId.isNotBlank()) { "Профиль не выбран." }
        val currentProfileId = when (val current = mutableState.value) {
            is VpnConnectionState.Connected -> current.profileId
            is VpnConnectionState.Paused -> current.profileId
            else -> return false
        }
        if (currentProfileId == profileId) return false
        start(profileId)
        return true
    }

    fun startForUpdater(profileId: String) {
        require(profileId.isNotBlank()) { "Профиль не выбран." }
        ContextCompat.startForegroundService(
            context,
            ZapretVpnService.startIntent(context, profileId, updaterRouting = true),
        )
    }

    fun stop() {
        ContextCompat.startForegroundService(context, ZapretVpnService.stopIntent(context))
    }

    fun resumePaused() {
        if (mutableState.value !is VpnConnectionState.Paused) return
        ContextCompat.startForegroundService(context, ZapretVpnService.resumeIntent(context))
    }

    fun restartIfConnected(reason: String) {
        val connected = mutableState.value as? VpnConnectionState.Connected ?: return
        ContextCompat.startForegroundService(
            context,
            ZapretVpnService.restartIntent(context, connected.profileId, reason),
        )
    }

    fun restartUpdaterRouting(profileId: String, enabled: Boolean) {
        require(profileId.isNotBlank()) { "Профиль не выбран." }
        ContextCompat.startForegroundService(
            context,
            ZapretVpnService.restartIntent(
                context = context,
                profileId = profileId,
                reason = if (enabled) "Временный маршрут updater" else "Завершение маршрута updater",
                updaterRouting = enabled,
            ),
        )
    }

    fun clearDnsCache() {
        ContextCompat.startForegroundService(context, ZapretVpnService.clearDnsCacheIntent(context))
    }

    fun selectOutbound(profileId: String, groupTag: String, outboundTag: String) {
        ContextCompat.startForegroundService(
            context,
            ZapretVpnService.selectIntent(context, profileId, groupTag, outboundTag),
        )
    }

    fun measureGroup(groupTag: String) {
        val connected = mutableState.value as? VpnConnectionState.Connected ?: return
        if (groupTag.isBlank()) return
        ContextCompat.startForegroundService(
            context,
            ZapretVpnService.pingGroupIntent(context, connected.profileId, groupTag),
        )
    }

    fun setHomeVisible(visible: Boolean) {
        mutableHomeVisible.value = visible
        if (!visible) {
            synchronized(trafficLock) {
                trafficAccumulator.setStatusStreamActive(currentGeneration(), false)?.let {
                    mutableSessionStats.value = it
                }
            }
        }
    }

    fun setDiagnosticsVisible(visible: Boolean) {
        mutableDiagnosticsVisible.value = visible
        if (!visible) publishDiagnosticLogStream(currentGeneration(), false)
    }

    internal fun publish(generation: Long, state: VpnConnectionState) {
        while (true) {
            val previous = latestGeneration.get()
            if (generation < previous) return
            if (latestGeneration.compareAndSet(previous, generation)) break
        }
        val safeState = if (state is VpnConnectionState.Error) {
            val message = sanitizeDiagnosticText(state.message, 360)
            val fallbackCode = DiagnosticFailureClassifier.classify(message).supportCode
            state.copy(
                message = message,
                code = VpnFailureCodeSanitizer.sanitize(state.code).ifBlank { fallbackCode },
                technicalDetail = state.technicalDetail
                    ?.let { sanitizeDiagnosticText(it, 240) }
                    ?.takeIf(String::isNotBlank),
            )
        } else {
            state
        }
        // Publish diagnostics first: StateFlow collectors may resume immediately on
        // the state write and terminal states must already have a terminal attempt.
        updateDiagnosticConnectionState(generation, safeState)
        mutableState.value = safeState
        synchronized(trafficLock) {
            when (safeState) {
                is VpnConnectionState.Connected -> {
                    mutableSessionStats.value = trafficAccumulator.start(
                        generation = generation,
                        profileId = safeState.profileId,
                        connectedAtEpochMillis = safeState.connectedAtEpochMillis,
                    )
                }
                is VpnConnectionState.Starting,
                is VpnConnectionState.Stopped,
                is VpnConnectionState.Error,
                is VpnConnectionState.Reconnecting,
                is VpnConnectionState.Paused,
                is VpnConnectionState.Stopping,
                -> mutableSessionStats.value = trafficAccumulator.stop()
            }
        }
        if (
            safeState is VpnConnectionState.Stopped ||
            safeState is VpnConnectionState.Error ||
            safeState is VpnConnectionState.Reconnecting ||
            safeState is VpnConnectionState.Paused
        ) {
            mutableGroups.value = emptyList()
        }
    }

    /**
     * Провал попытки, после которого сервис остаётся жив и переподключается сам.
     * Диагностика обязана зафиксировать отказ, а состоянием VPN станет
     * [VpnConnectionState.Reconnecting], а не терминальная ошибка.
     */
    internal fun publishRecoverableFailure(generation: Long, failure: VpnConnectionState.Error) {
        if (generation < currentGeneration()) return
        val message = sanitizeDiagnosticText(failure.message, 360)
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

    internal fun nextGeneration(): Long {
        val generation = latestGeneration.incrementAndGet()
        diagnosticRuntimeMap = null
        // Clear the previous session once, before callbacks can publish data for
        // this generation. Repeated Starting progress updates must not erase the
        // initial CommandGroup snapshot when it arrives quickly.
        mutableGroups.value = emptyList()
        return generation
    }

    internal fun currentGeneration(): Long = latestGeneration.get()

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
        mutableDiagnostics.update { current ->
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
        mutableDiagnostics.update { current ->
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
        mutableDiagnostics.update { current ->
            val attempt = current.connectionAttempt
                ?.takeIf { it.generation == generation && it.outcome == DiagnosticAttemptOutcome.Running }
                ?: return@update current
            val completed = attempt.stages.completeRunningStage(
                elapsed = elapsed,
                status = DiagnosticStageStatus.Success,
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

    internal fun beginConnectionCandidate(generation: Long, candidateAttemptId: Int) {
        mutableDiagnostics.update { current ->
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
        mutableDiagnostics.update { current ->
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
        mutableDiagnostics.update { current ->
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
        mutableDiagnostics.update { current ->
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
        mutableDiagnostics.update { current ->
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
        mutableDiagnostics.update { current ->
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
        mutableDiagnostics.update { current ->
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

    internal fun publishGroups(generation: Long, groups: List<RuntimeSelectorGroup>) {
        mutableGroups.update { current ->
            if (generation != currentGeneration()) return@update current
            val priorGroups = current.associateBy(RuntimeSelectorGroup::tag)
            groups.map { incoming ->
                val prior = priorGroups[incoming.tag]
                val priorItems = prior?.items?.associateBy(RuntimeOutboundItem::tag).orEmpty()
                incoming.copy(
                    probeProgress = prior?.probeProgress,
                    items = incoming.items.map { item ->
                        val previous = priorItems[item.tag] ?: return@map item
                        item.copy(
                            icmp = previous.icmp,
                            relay = when {
                                prior?.probeProgress?.running == true -> previous.relay
                                item.relay.lastSample()?.measuredAtEpochMillis
                                    ?.let { incomingTime ->
                                        incomingTime >
                                            (previous.relay.lastSample()?.measuredAtEpochMillis ?: 0L)
                                    } == true -> item.relay
                                previous.relay == LatencyProbeState.NotTested -> item.relay
                                else -> previous.relay
                            },
                        )
                    },
                )
            }
        }
    }

    internal fun beginLatencyProbe(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
        icmpTargets: Set<String>,
    ): Boolean {
        var started = false
        mutableGroups.update { groups ->
            if (generation != currentGeneration()) return@update groups
            val result = LatencyProbeReducer.begin(
                groups,
                requestId,
                groupTag,
                networkIdentity,
                icmpTargets,
            )
            started = result.started
            result.groups
        }
        return started
    }

    internal fun publishLatencyBatch(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
        relay: Map<String, LatencyProbeState> = emptyMap(),
        icmp: Map<String, LatencyProbeState> = emptyMap(),
    ) {
        if (relay.isEmpty() && icmp.isEmpty()) return
        mutableGroups.update { groups ->
            if (generation != currentGeneration()) return@update groups
            LatencyProbeReducer.publishBatch(
                groups,
                requestId,
                groupTag,
                networkIdentity,
                relay,
                icmp,
            )
        }
    }

    internal fun completeLatencyProbe(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
    ) {
        mutableGroups.update { groups ->
            if (generation != currentGeneration()) return@update groups
            LatencyProbeReducer.complete(groups, requestId, groupTag, networkIdentity)
        }
    }

    internal fun cancelLatencyProbe(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
    ) {
        mutableGroups.update { groups ->
            if (generation != currentGeneration()) return@update groups
            LatencyProbeReducer.cancel(groups, requestId, groupTag, networkIdentity)
        }
    }

    internal fun markLatencyStale(generation: Long) {
        mutableGroups.update { groups ->
            if (generation != currentGeneration()) return@update groups
            LatencyProbeReducer.markStale(groups)
        }
    }

    internal fun publishBackgroundIcmp(
        generation: Long,
        outboundTag: String,
        state: LatencyProbeState,
    ) {
        if (generation != currentGeneration() || outboundTag.isBlank()) return
        mutableGroups.update { groups ->
            if (generation != currentGeneration()) return@update groups
            groups.map { group ->
                group.copy(
                    items = group.items.map { item ->
                        if (item.tag == outboundTag && item.icmp !is LatencyProbeState.Running) {
                            item.copy(icmp = state)
                        } else {
                            item
                        }
                    },
                )
            }
        }
    }

    internal fun publishSelection(generation: Long, groupTag: String, outboundTag: String) {
        mutableGroups.update { groups ->
            if (generation != currentGeneration()) return@update groups
            groups.map { group ->
                if (group.tag == groupTag) group.copy(selected = outboundTag) else group
            }
        }
    }

    internal fun publishMessage(message: String) {
        val safe = sanitizeDiagnosticText(message, 360)
        mutableMessage.value = safe
        appendApplicationDiagnosticLog(level = 5, message = safe, generation = currentGeneration())
    }

    internal fun publishMessage(generation: Long, message: String) {
        if (generation == currentGeneration()) publishMessage(message)
    }

    internal fun publishDiagnosticWarning(message: String) {
        val safe = sanitizeDiagnosticText(message, 360)
        appendApplicationDiagnosticLog(level = 3, message = safe, generation = currentGeneration())
    }

    internal fun publishDiagnosticNetwork(generation: Long, state: UnderlyingNetworkState) {
        if (generation < latestGeneration.get()) return
        mutableDiagnostics.update {
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
        if (generation < latestGeneration.get()) return
        mutableDiagnostics.update {
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
        if (generation < latestGeneration.get()) return
        mutableDiagnostics.update { it.copy(generation = generation, effectiveOverlay = overlay) }
    }

    internal fun clearCoreDiagnosticLogs(generation: Long) {
        if (generation != currentGeneration()) return
        mutableDiagnostics.update {
            it.copy(
                coreLogs = emptyList(),
                coreLogStats = DiagnosticLogStats(),
            )
        }
    }

    internal fun publishCoreDiagnosticLog(generation: Long, level: Int, message: String) {
        publishCoreDiagnosticLogs(generation, listOf(level to message), ingressDropped = 0)
    }

    internal fun publishCoreDiagnosticLogs(
        generation: Long,
        entries: List<Pair<Int, String>>,
        ingressDropped: Int,
    ) {
        if (generation != currentGeneration()) return
        val attemptSnapshot = mutableDiagnostics.value.connectionAttempt
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
        mutableDiagnostics.update { current ->
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
        if (generation < latestGeneration.get()) return
        mutableDiagnostics.update { it.copy(logStreamActive = active) }
    }

    internal fun publishStatusStream(generation: Long, active: Boolean) {
        synchronized(trafficLock) {
            trafficAccumulator.setStatusStreamActive(generation, active)?.let {
                mutableSessionStats.value = it
            }
        }
    }

    internal fun publishTraffic(
        generation: Long,
        uploadDelta: Long,
        downloadDelta: Long,
        uploadTotal: Long,
        downloadTotal: Long,
    ) {
        synchronized(trafficLock) {
            trafficAccumulator.updateTraffic(
                generation,
                uploadDelta,
                downloadDelta,
                uploadTotal,
                downloadTotal,
            )?.let { mutableSessionStats.value = it }
        }
    }

    internal fun publishExternalIp(generation: Long, externalIp: String?) {
        synchronized(trafficLock) {
            trafficAccumulator.updateExternalIp(generation, externalIp)?.let {
                mutableSessionStats.value = it
            }
        }
    }

    internal fun clearConnectionIdentity(generation: Long) {
        synchronized(trafficLock) {
            trafficAccumulator.clearConnectionIdentity(generation)?.let {
                mutableSessionStats.value = it
            }
        }
    }

    fun consumeMessage() {
        mutableMessage.value = null
    }

    private fun updateDiagnosticConnectionState(
        generation: Long,
        state: VpnConnectionState,
    ) {
        when (state) {
            is VpnConnectionState.Starting -> mutableDiagnostics.update { current ->
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
        val runningAttempt = mutableDiagnostics.value.connectionAttempt
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
        mutableDiagnostics.update {
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
        mutableDiagnostics.update { current ->
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
    ): List<DiagnosticStageTiming> = map { stage ->
        if (stage.status == DiagnosticStageStatus.Running) {
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

    private fun appendApplicationDiagnosticLog(
        level: Int,
        message: String,
        generation: Long? = null,
    ) {
        if (message.isEmpty()) return
        val attempt = mutableDiagnostics.value.connectionAttempt
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
        mutableDiagnostics.update {
            val result = it.applicationLogs.appendPrioritizedBounded(
                line,
                it.applicationLogStats,
                MAX_DIAGNOSTIC_LOG_LINES,
            )
            it.copy(applicationLogs = result.lines, applicationLogStats = result.stats)
        }
    }

    private fun sanitizeDiagnosticText(message: String, maxLength: Int): String =
        DiagnosticReportRedactor.redact(message)
            .replace(ANSI_ESCAPE, "")
            .replace(NEW_LINES, " ")
            .trim()
            .take(maxLength)

    private companion object {
        const val MAX_DIAGNOSTIC_STAGE_DETAIL_CHARS = 160
        val ANSI_ESCAPE = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|[@-_])")
        val NEW_LINES = Regex("[\\r\\n]+")
    }
}

internal object VpnFailureCodeSanitizer {
    private val supportCode = Regex("[A-Z]{2,5}-\\d{3}")
    private val hysteriaCodes = HysteriaFailureCode.entries.mapTo(mutableSetOf()) { it.name }

    fun sanitize(value: String): String = value
        .trim()
        .uppercase()
        .takeIf { it.matches(supportCode) || it in hysteriaCodes }
        .orEmpty()
}
