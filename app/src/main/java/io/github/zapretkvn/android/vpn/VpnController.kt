package io.github.zapretkvn.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import io.github.zapretkvn.android.diagnostics.AppCrashRecord
import io.github.zapretkvn.android.diagnostics.AppProcessExitReader
import io.github.zapretkvn.android.diagnostics.DiagnosticAttemptOutcome
import io.github.zapretkvn.android.diagnostics.DiagnosticFailureClassifier
import io.github.zapretkvn.android.diagnostics.DiagnosticRuntimeMap
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import io.github.zapretkvn.android.diagnostics.DiagnosticState
import io.github.zapretkvn.android.diagnostics.RuntimeErrorJournal
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.diagnostics.ServerAddressRedactor
import io.github.zapretkvn.android.diagnostics.SessionTrafficAccumulator
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.network.UnderlyingNetworkState
import io.github.zapretkvn.android.network.probes.ProbeLane
import io.github.zapretkvn.android.network.probes.services.ServiceAvailabilityState
import io.github.zapretkvn.android.network.probes.services.ServiceCatalog
import io.github.zapretkvn.android.network.probes.services.ServiceId
import io.github.zapretkvn.android.platform.VpnSystemPolicy
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Фасад состояния VPN для UI и сервиса: состояние подключения, статистика,
 * доступность сервисов и команды сервису. Диагностику подключения ведёт
 * [ConnectionDiagnosticsRecorder], публикацию групп и задержек — [LatencyPublisher];
 * здесь остаются только делегирующие вызовы и поколение сессии.
 */
class VpnController(
    private val context: Context,
    previousCrash: AppCrashRecord? = null,
) {
    internal val runtimeErrors = RuntimeErrorJournal()
    private val mutableState = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Stopped)
    private val mutableGroups = MutableStateFlow<List<RuntimeSelectorGroup>>(emptyList())
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val mutableHomeVisible = MutableStateFlow(false)
    private val mutableDiagnosticsVisible = MutableStateFlow(false)
    private val latestGeneration = AtomicLong(0)
    private val diagnosticsRecorder = ConnectionDiagnosticsRecorder(
        initial = DiagnosticState(
            previousCrash = previousCrash,
            previousProcessExit = AppProcessExitReader.read(context),
        ),
        currentGeneration = { latestGeneration.get() },
        runtimeErrors = runtimeErrors,
    )
    private val latencyPublisher = LatencyPublisher(mutableGroups) { latestGeneration.get() }
    private val mutableServiceAvailability = MutableStateFlow(ServiceAvailabilityState.Initial)
    private val mutableEnabledServices = MutableStateFlow(ServiceCatalog.defaultIds)
    @Volatile
    private var serviceCheckHandler: Pair<Long, () -> Unit>? = null
    private val trafficAccumulator = SessionTrafficAccumulator()
    private val mutableSessionStats = MutableStateFlow(trafficAccumulator.value)
    private val trafficLock = Any()

    val state: StateFlow<VpnConnectionState> = mutableState.asStateFlow()
    val selectorGroups: StateFlow<List<RuntimeSelectorGroup>> = mutableGroups.asStateFlow()
    val message: StateFlow<String?> = mutableMessage.asStateFlow()
    val sessionStats: StateFlow<VpnSessionStats> = mutableSessionStats.asStateFlow()
    val diagnostics: StateFlow<DiagnosticState> = diagnosticsRecorder.diagnostics

    internal val homeVisible: StateFlow<Boolean> = mutableHomeVisible.asStateFlow()
    internal val serviceAvailability: StateFlow<ServiceAvailabilityState> =
        mutableServiceAvailability.asStateFlow()
    /** Сервисы, выбранные в настройках: планировщик проверок строит запросы только для них. */
    internal val enabledServices: StateFlow<Set<ServiceId>> = mutableEnabledServices.asStateFlow()
    internal val diagnosticsVisible: StateFlow<Boolean> = mutableDiagnosticsVisible.asStateFlow()

    /** Общая полоса тяжёлых проб: проверка серверов и проверка сервисов не идут вместе. */
    internal val probeLane = ProbeLane()

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

    /** Останавливает массовую проверку группы; сервис не будится, если VPN не подключён. */
    fun stopMeasuring(groupTag: String) {
        val connected = mutableState.value as? VpnConnectionState.Connected ?: return
        if (groupTag.isBlank()) return
        ContextCompat.startForegroundService(
            context,
            ZapretVpnService.pingGroupIntent(context, connected.profileId, groupTag, stop = true),
        )
    }

    /** Нажатие на карточку «Доступность сервисов»; без подключённого VPN ничего не делает. */
    fun checkServices() {
        if (mutableState.value !is VpnConnectionState.Connected) return
        val handler = serviceCheckHandler ?: return
        if (handler.first == currentGeneration()) handler.second()
    }

    internal fun setEnabledServices(ids: Set<ServiceId>) {
        mutableEnabledServices.value = ids
    }

    internal fun registerServiceCheckHandler(generation: Long, handler: (() -> Unit)?) {
        serviceCheckHandler = handler?.let { generation to it }
    }

    internal fun updateServiceAvailability(
        generation: Long,
        transform: (ServiceAvailabilityState) -> ServiceAvailabilityState,
    ) {
        mutableServiceAvailability.update { current ->
            if (generation != currentGeneration()) current else transform(current)
        }
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
        if (!visible) diagnosticsRecorder.publishDiagnosticLogStream(currentGeneration(), false)
    }

    internal fun publish(generation: Long, state: VpnConnectionState) {
        while (true) {
            val previous = latestGeneration.get()
            if (generation < previous) return
            if (latestGeneration.compareAndSet(previous, generation)) break
        }
        val safeState = if (state is VpnConnectionState.Error) {
            val message = ServerAddressRedactor.redact(SecretRedactor.redactInline(state.message))
            val fallbackCode = DiagnosticFailureClassifier.classify(message).supportCode
            state.copy(
                message = message,
                code = VpnFailureCodeSanitizer.sanitize(state.code).ifBlank { fallbackCode },
                technicalDetail = state.technicalDetail
                    ?.let(SecretRedactor::redactInline)
                    ?.let(ServerAddressRedactor::redact)
                    ?.takeIf(String::isNotBlank),
            )
        } else {
            state
        }
        // Publish diagnostics first: StateFlow collectors may resume immediately on
        // the state write and terminal states must already have a terminal attempt.
        diagnosticsRecorder.updateDiagnosticConnectionState(generation, safeState)
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
            mutableServiceAvailability.value = ServiceAvailabilityState.Initial
        }
    }

    /**
     * Провал попытки, после которого сервис остаётся жив и переподключается сам.
     * Диагностика обязана зафиксировать отказ, а состоянием VPN станет
     * [VpnConnectionState.Reconnecting], а не терминальная ошибка.
     */
    internal fun publishRecoverableFailure(generation: Long, failure: VpnConnectionState.Error) =
        diagnosticsRecorder.recordRecoverableFailure(generation, failure)

    internal fun nextGeneration(): Long {
        val generation = latestGeneration.incrementAndGet()
        diagnosticsRecorder.resetRuntimeMap()
        // Clear the previous session once, before callbacks can publish data for
        // this generation. Repeated Starting progress updates must not erase the
        // initial CommandGroup snapshot when it arrives quickly.
        mutableGroups.value = emptyList()
        return generation
    }

    internal fun currentGeneration(): Long = latestGeneration.get()

    // ---- Диагностика подключения: ConnectionDiagnosticsRecorder. ----

    internal fun beginConnectionDiagnostic(
        generation: Long,
        trigger: String,
        profileId: String? = null,
    ) = diagnosticsRecorder.beginConnectionDiagnostic(generation, trigger, profileId)

    internal fun attachDiagnosticRuntimeMap(generation: Long, runtimeMap: DiagnosticRuntimeMap) =
        diagnosticsRecorder.attachDiagnosticRuntimeMap(generation, runtimeMap)

    internal fun startConnectionDiagnosticStage(generation: Long, key: String, label: String) =
        diagnosticsRecorder.startConnectionDiagnosticStage(generation, key, label)

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
    ) = diagnosticsRecorder.recordConnectionDiagnosticStage(
        generation,
        key,
        label,
        startedAtElapsedRealtimeMillis,
        startedAtEpochMillis,
        durationMillis,
        status,
        detail,
    )

    internal fun beginConnectionCandidate(generation: Long, candidateAttemptId: Int) =
        diagnosticsRecorder.beginConnectionCandidate(generation, candidateAttemptId)

    internal fun recordConnectionVpnNetwork(
        generation: Long,
        identity: String? = null,
        lost: Boolean = false,
    ) = diagnosticsRecorder.recordConnectionVpnNetwork(generation, identity, lost)

    internal fun finishConnectionDiagnosticStage(
        generation: Long,
        key: String,
        status: DiagnosticStageStatus,
        detail: String? = null,
    ) = diagnosticsRecorder.finishConnectionDiagnosticStage(generation, key, status, detail)

    internal fun cancelCurrentConnectionDiagnostic(reason: String = "superseded") =
        diagnosticsRecorder.cancelCurrentConnectionDiagnostic(reason)

    internal fun beginStopDiagnostic(generation: Long, trigger: String) =
        diagnosticsRecorder.beginStopDiagnostic(generation, trigger)

    internal fun startStopDiagnosticStage(generation: Long, key: String, label: String) =
        diagnosticsRecorder.startStopDiagnosticStage(generation, key, label)

    internal fun finishStopDiagnosticStage(generation: Long, key: String, error: Throwable? = null) =
        diagnosticsRecorder.finishStopDiagnosticStage(generation, key, error)

    internal fun completeStopDiagnostic(generation: Long) =
        diagnosticsRecorder.completeStopDiagnostic(generation)

    internal fun publishMessage(message: String) {
        mutableMessage.value = diagnosticsRecorder.logUserMessage(message)
    }

    internal fun publishMessage(generation: Long, message: String) {
        if (generation == currentGeneration()) publishMessage(message)
    }

    /** Решение автоматики без ошибки: только в диагностику, без snackbar. */
    internal fun publishDiagnosticInfo(message: String) =
        diagnosticsRecorder.publishDiagnosticInfo(message)

    internal fun publishDiagnosticWarning(message: String) =
        diagnosticsRecorder.publishDiagnosticWarning(message)

    internal fun publishDiagnosticNetwork(generation: Long, state: UnderlyingNetworkState) =
        diagnosticsRecorder.publishDiagnosticNetwork(generation, state)

    internal fun publishVpnSystemPolicy(generation: Long, policy: VpnSystemPolicy) =
        diagnosticsRecorder.publishVpnSystemPolicy(generation, policy)

    internal fun publishEffectiveOverlay(generation: Long, overlay: String) =
        diagnosticsRecorder.publishEffectiveOverlay(generation, overlay)

    internal fun clearCoreDiagnosticLogs(generation: Long) =
        diagnosticsRecorder.clearCoreDiagnosticLogs(generation)

    internal fun publishCoreDiagnosticLog(generation: Long, level: Int, message: String) =
        diagnosticsRecorder.publishCoreDiagnosticLog(generation, level, message)

    internal fun recordCoreFailure(generation: Long, level: Int, message: String) =
        diagnosticsRecorder.recordCoreFailure(generation, level, message)

    internal fun recordCommandLogDisconnect(generation: Long, message: String, expectedClose: Boolean) =
        diagnosticsRecorder.recordCommandLogDisconnect(generation, message, expectedClose)

    internal fun publishCoreDiagnosticLogs(
        generation: Long,
        entries: List<Pair<Int, String>>,
        ingressDropped: Int,
    ) = diagnosticsRecorder.publishCoreDiagnosticLogs(generation, entries, ingressDropped)

    internal fun publishDiagnosticLogStream(generation: Long, active: Boolean) =
        diagnosticsRecorder.publishDiagnosticLogStream(generation, active)

    // ---- Группы серверов и задержки: LatencyPublisher. ----

    internal fun publishGroups(generation: Long, groups: List<RuntimeSelectorGroup>) =
        latencyPublisher.publishGroups(generation, groups)

    internal fun beginLatencyProbe(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
        icmpTargets: Set<String>,
    ): Boolean = latencyPublisher.beginLatencyProbe(generation, requestId, groupTag, networkIdentity, icmpTargets)

    internal fun publishLatencyBatch(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
        relay: Map<String, LatencyProbeState> = emptyMap(),
        icmp: Map<String, LatencyProbeState> = emptyMap(),
    ) = latencyPublisher.publishLatencyBatch(generation, requestId, groupTag, networkIdentity, relay, icmp)

    internal fun completeLatencyProbe(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
    ) = latencyPublisher.completeLatencyProbe(generation, requestId, groupTag, networkIdentity)

    internal fun cancelLatencyProbe(
        generation: Long,
        requestId: Long,
        groupTag: String,
        networkIdentity: String,
    ) = latencyPublisher.cancelLatencyProbe(generation, requestId, groupTag, networkIdentity)

    internal fun markLatencyStale(generation: Long) = latencyPublisher.markLatencyStale(generation)

    internal fun publishBackgroundIcmp(generation: Long, outboundTag: String, state: LatencyProbeState) =
        latencyPublisher.publishBackgroundIcmp(generation, outboundTag, state)

    internal fun publishSelection(generation: Long, groupTag: String, outboundTag: String) =
        latencyPublisher.publishSelection(generation, groupTag, outboundTag)

    // ---- Трафик и статистика сессии. ----

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
