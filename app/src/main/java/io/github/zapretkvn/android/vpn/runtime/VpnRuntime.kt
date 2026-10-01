package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.diagnostics.RuntimeErrors
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.diagnostics.ServerAddressRedactor
import io.github.zapretkvn.android.diagnostics.VpnFailureStates
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.engines.failover.LatencyHint
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchEngine
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchPolicy
import io.github.zapretkvn.android.engines.failover.SlowSwitchGate
import io.github.zapretkvn.android.engines.failover.SlowSwitchSnapshot
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureClassifier
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.network.CurrentWifiSsidReader
import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.NetworkAutomationDecision
import io.github.zapretkvn.android.network.NetworkAutomationSettings
import io.github.zapretkvn.android.network.NetworkPauseReason
import io.github.zapretkvn.android.network.NetworkRestartDecision
import io.github.zapretkvn.android.network.NetworkRestartPolicy
import io.github.zapretkvn.android.network.UnderlyingNetworkState
import io.github.zapretkvn.android.network.isSettledForConnect
import io.github.zapretkvn.android.network.isUsableForConnect
import io.github.zapretkvn.android.network.policyKey
import io.github.zapretkvn.android.network.probes.LivenessResult
import io.github.zapretkvn.android.network.userMessage
import io.github.zapretkvn.android.platform.VpnSystemPolicy
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnRecoveryDecision
import io.github.zapretkvn.android.vpn.VpnRecoveryPolicy
import io.github.zapretkvn.android.vpn.ZapretVpnService
import io.github.zapretkvn.networkbootstrap.BootstrapFailureException
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Android-сторона, которой рантайм управляет: уведомление и жизнь сервиса. */
internal interface RuntimeHost {
    fun showForeground(state: ForegroundState, detail: String? = null)
    fun finishForeground()
    fun stopSelfResult(startId: Int)
    fun stopSelf()
}

/**
 * Жизненный цикл VPN — единственный владелец его состояния.
 *
 * Все команды пользователя, события Android и ядра приходят сообщениями в
 * одну очередь и обрабатываются строго по одному на одном потоке. Долгая
 * работа (запуск, проба, переключение, закрытие ресурсов) выполняется в
 * дочерних задачах и возвращается сообщением с поколением; устаревший
 * результат отбрасывается. Поколение меняет только этот актор, поэтому
 * гонок «стоп против перезапуска» нет по построению — блокировок тоже.
 */
internal class VpnRuntime(
    private val service: ZapretVpnService,
    private val container: AppContainer,
    private val host: RuntimeHost,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<Message>(Channel.UNLIMITED)
    private val controller get() = container.vpnController
    private val sessions = SessionSlot { controller.currentGeneration() }
    private val switcher = ServerSwitcher(container.profileStore, container.vpnController)
    private val wifiSsidReader by lazy { CurrentWifiSsidReader(service) }

    private enum class Phase { Idle, Starting, Running, Recovering, Paused, Stopping }

    private data class PausedSession(
        val profileId: String,
        val updaterRouting: Boolean,
        val generation: Long,
        val reason: NetworkPauseReason,
    )

    private enum class Operation { Select, Probe, Failover, Smart }

    // ---- Состояние актора: читается и меняется только в handle(). ----
    private var phase = Phase.Idle
    private var stopInProgress = false
    private var lifecycleJob: Job? = null
    private var recoveryJob: Job? = null
    private var debounceJob: Job? = null
    private var operationJob: Job? = null
    private var operationKind: Operation? = null
    private var probeCode = HysteriaFailureCode.TARGET_NETWORK_TIMEOUT
    private var networkChangeSince = 0L
    private var recoveryAttempt = 0
    private var recoveryTotalAttempts = 0
    private var attemptNetworkIdentity: String? = null
    private var startupReplacement: PendingReplacement? = null
    private var startupReplacementUsed = false
    private var automationSettings = NetworkAutomationSettings()
    private var automationOverrideIdentity: String? = null
    private var paused: PausedSession? = null
    @Volatile private var pausedObserver: AutoCloseable? = null
    private val supervisor = PathSupervisor { monotonicNow() }

    // ---- Читаются из потоков libbox/Android: только volatile-флаги. ----
    @Volatile private var smartSwitchEnabled = true
    @Volatile private var screenInteractive = true
    @Volatile var terminalError = false
        private set

    /** Антифлап «умной проверки» переживает переподключения. */
    private val smartPolicy = SlowServerSwitchPolicy { monotonicNow() }

    private val networkMonitorLock = Any()
    @Volatile private var networkMonitorInstance: DefaultNetworkMonitor? = null

    /**
     * Монитор сети живёт с рантаймом, а не с сессией: восстановление после
     * отказа должно увидеть появление рабочей сети. Закрытие терминально,
     * поэтому экземпляр пересоздаётся при следующем подключении.
     */
    private fun monitor(): DefaultNetworkMonitor = synchronized(networkMonitorLock) {
        networkMonitorInstance ?: DefaultNetworkMonitor(service).also { networkMonitorInstance = it }
    }

    private fun closeNetworkMonitor() {
        val current = synchronized(networkMonitorLock) { networkMonitorInstance.also { networkMonitorInstance = null } }
        runCatching { current?.close() }
    }

    private val starter = SessionStarter(
        service = service,
        container = container,
        sessions = sessions,
        networkMonitor = ::monitor,
        foreground = { state -> host.showForeground(state) },
        events = Events(),
        switcher = switcher,
        scope = scope,
    )

    init {
        scope.launch {
            for (message in inbox) {
                try {
                    handle(message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    controller.publishDiagnosticWarning(
                        "Внутренняя ошибка VPN-рантайма: ${SecretRedactor.redactInline(RuntimeErrors.describe(error))}",
                    )
                }
            }
        }
        runCatching { monitor().start() }
        scope.launch {
            container.uiSettingsStore.settings
                .map { it.networkAutomation }
                .distinctUntilChanged()
                .collect { post(Message.AutomationSettings(it)) }
        }
        scope.launch {
            container.uiSettingsStore.settings
                .map { it.slowServerSwitch }
                .distinctUntilChanged()
                .collect { post(Message.SmartSwitchSetting(it)) }
        }
    }

    // ---- Внешний API: потокобезопасная постановка в очередь. ----

    fun start(profileId: String, startId: Int, updaterRouting: Boolean) =
        post(Message.Start(profileId, startId, updaterRouting))

    fun restart(profileId: String, reason: String, startId: Int, updaterRouting: Boolean?) =
        post(Message.Restart(profileId, reason, startId, noCacheLookup = false, updaterRouting = updaterRouting))

    fun stop(startId: Int, errorMessage: String?, systemPolicy: VpnSystemPolicy? = null, trigger: String? = null) {
        synchronized(stopRequests) {
            stopRequests.requested += 1
            stopRequests.errorMessage = errorMessage
        }
        post(
            Message.Stop(
                startId,
                errorMessage,
                systemPolicy,
                trigger ?: if (errorMessage == null) "user_stop" else "policy_stop",
            ),
        )
    }

    fun select(profileId: String, groupTag: String, outboundTag: String, startId: Int) =
        post(Message.Select(profileId, groupTag, outboundTag, startId))

    fun pingGroup(profileId: String, groupTag: String, startId: Int) =
        post(Message.PingGroup(profileId, groupTag, startId))

    fun clearDnsCache(startId: Int) = post(Message.ClearDnsCache(startId))

    fun resume(startId: Int) = post(Message.Resume(startId, manual = true))

    fun onScreenInteractive(interactive: Boolean) {
        screenInteractive = interactive
        post(Message.ApplySpeedMonitor)
    }

    /** Запросы остановки фиксируются при вызове, а не при обработке актором. */
    private class StopRequests {
        var requested = 0L
        var completed = 0L
        var errorMessage: String? = null
    }

    private val stopRequests = StopRequests()

    /**
     * Синхронное освобождение при уничтожении сервиса: TUN и ядро закрываются
     * до возврата, как требует Android.
     *
     * @return итоговое состояние для публикации. Android уничтожает сервис
     * сразу после onRevoke — раньше, чем актор завершит остановку, поэтому
     * незавершённая остановка доводится здесь.
     */
    fun destroy(): VpnConnectionState? {
        inbox.close()
        scope.cancel()
        controller.cancelCurrentConnectionDiagnostic()
        runCatching { pausedObserver?.close() }
        val remaining = (sessions.detachAll() + closingSessions).distinct()
        remaining.forEach(CoreSession::closeTun)
        runBlocking(Dispatchers.IO) { remaining.forEach(CoreSession::close) }
        closeNetworkMonitor()
        val pendingStop = synchronized(stopRequests) {
            (stopRequests.requested > stopRequests.completed).also { stopRequests.completed = stopRequests.requested }
        }
        val pendingError = synchronized(stopRequests) { stopRequests.errorMessage }
        return when {
            pendingStop && pendingError != null -> VpnConnectionState.Error(pendingError)
            pendingStop || !terminalError -> VpnConnectionState.Stopped
            else -> null
        }
    }

    /**
     * Сессии, отданные на закрытие, но ещё не закрытые. Закрытие стартует
     * атомарно, а [destroy] дозакрывает всё, что успело отцепиться: Android
     * вызывает onDestroy сразу после onRevoke, раньше фоновой задачи.
     */
    private val closingSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<CoreSession>()

    private fun closeSessions(detached: List<CoreSession>): Job {
        closingSessions += detached
        return scope.launch(start = CoroutineStart.ATOMIC) {
            detached.forEach { session ->
                session.close()
                closingSessions -= session
            }
        }
    }

    private fun post(message: Message) {
        inbox.trySend(message)
    }

    /** Выполнить блок в акторе и дождаться результата (для задач «умной проверки»). */
    private suspend fun <T> ask(block: () -> T): T {
        val reply = CompletableDeferred<T>()
        post(Message.Call { reply.complete(block()) })
        return reply.await()
    }

    private sealed interface Message {
        data class Start(val profileId: String, val startId: Int, val updaterRouting: Boolean) : Message
        data class Restart(
            val profileId: String,
            val reason: String,
            val startId: Int,
            val noCacheLookup: Boolean,
            val updaterRouting: Boolean?,
            val resetRecovery: Boolean = true,
            val expectedGeneration: Long? = null,
        ) : Message
        data class Stop(
            val startId: Int,
            val errorMessage: String?,
            val systemPolicy: VpnSystemPolicy?,
            val trigger: String,
        ) : Message
        data class StopCompleted(val token: Long, val startId: Int, val errorMessage: String?) : Message
        data class Select(val profileId: String, val groupTag: String, val outboundTag: String, val startId: Int) : Message
        data class SelectDone(
            val session: CoreSession,
            val outcome: SwitchOutcome,
            val startId: Int,
        ) : Message
        data class PingGroup(val profileId: String, val groupTag: String, val startId: Int) : Message
        data class ClearDnsCache(val startId: Int) : Message
        data class ClearDnsCacheDone(val startId: Int, val expectedGeneration: Long) : Message
        data class Resume(val startId: Int, val manual: Boolean) : Message
        data object CoreStop : Message
        data class AutomationSettings(val settings: NetworkAutomationSettings) : Message
        data class SmartSwitchSetting(val enabled: Boolean) : Message
        data object ApplySpeedMonitor : Message
        data class AttemptNetwork(val token: Long, val identity: String?) : Message
        data class PauseAtStart(
            val token: Long,
            val profileId: String,
            val updaterRouting: Boolean,
            val reason: NetworkPauseReason,
        ) : Message
        data class StartFinished(
            val token: Long,
            val profileId: String,
            val updaterRouting: Boolean,
            val startId: Int,
            val error: Throwable?,
        ) : Message
        data class RecoveryDue(
            val token: Long,
            val profileId: String,
            val updaterRouting: Boolean,
            val failedOnIdentity: String?,
            val readyIdentity: String?,
        ) : Message
        data class SessionNetwork(val session: CoreSession, val state: UnderlyingNetworkState) : Message
        data class PausedNetwork(val state: UnderlyingNetworkState) : Message
        data class NetworkRestartDue(val session: CoreSession) : Message
        data class AutomationPauseDue(val session: CoreSession) : Message
        data class AutomationResumeDue(val generation: Long) : Message
        data class CoreHints(val generation: Long, val hints: List<PathHint>) : Message
        data class ObserverUnavailable(val generation: Long, val message: String) : Message
        data class InjectedFailure(val session: CoreSession, val code: HysteriaFailureCode) : Message
        data class InjectedHint(val session: CoreSession, val code: HysteriaFailureCode) : Message
        data class StartupFallback(
            val session: CoreSession,
            val deadIds: List<String>,
            val toId: String,
            val code: HysteriaFailureCode,
        ) : Message
        data class ProbeDone(val session: CoreSession, val episode: Long, val result: LivenessResult) : Message
        data class SwitchDone(
            val session: CoreSession,
            val episode: Long,
            val fromId: String,
            val toId: String,
            val code: HysteriaFailureCode,
            val startedAt: Long,
            val outcome: SwitchOutcome,
        ) : Message
        data class SpeedSample(val session: CoreSession, val total: Long, val connections: Int) : Message
        data class SessionsClosed(val token: Long, val then: () -> Unit) : Message
        class Call(val block: () -> Unit) : Message
    }

    private suspend fun handle(message: Message) {
        when (message) {
            is Message.Start -> onStart(message)
            is Message.Restart -> onRestart(message)
            is Message.Stop -> onStop(message)
            is Message.StopCompleted -> onStopCompleted(message)
            is Message.Select -> onSelect(message)
            is Message.SelectDone -> onSelectDone(message)
            is Message.PingGroup -> onPingGroup(message)
            is Message.ClearDnsCache -> onClearDnsCache(message)
            is Message.ClearDnsCacheDone -> onClearDnsCacheDone(message)
            is Message.Resume -> onResume(message)
            Message.CoreStop -> onCoreStop()
            is Message.AutomationSettings -> onAutomationSettings(message.settings)
            is Message.SmartSwitchSetting -> onSmartSwitchSetting(message.enabled)
            Message.ApplySpeedMonitor -> sessions.active()?.let(::applySpeedMonitor)
            is Message.AttemptNetwork -> if (message.token == controller.currentGeneration()) {
                attemptNetworkIdentity = message.identity
            }
            is Message.PauseAtStart -> if (message.token == controller.currentGeneration()) {
                enterAutomationPause(message.token, message.profileId, message.updaterRouting, message.reason)
            }
            is Message.StartFinished -> onStartFinished(message)
            is Message.RecoveryDue -> onRecoveryDue(message)
            is Message.SessionNetwork -> onSessionNetwork(message.session, message.state)
            is Message.PausedNetwork -> onPausedNetwork(message.state)
            is Message.NetworkRestartDue -> onNetworkRestartDue(message.session)
            is Message.AutomationPauseDue -> onAutomationPauseDue(message.session)
            is Message.AutomationResumeDue -> onAutomationResumeDue(message.generation)
            is Message.CoreHints -> onCoreHints(message)
            is Message.ObserverUnavailable -> onObserverUnavailable(message)
            is Message.InjectedFailure -> onInjectedFailure(message)
            is Message.InjectedHint -> currentPlan(message.session)?.let { plan ->
                onCoreHints(
                    Message.CoreHints(
                        message.session.generation,
                        listOf(PathHint(plan.currentId, plan.currentType, message.code)),
                    ),
                )
            }
            is Message.StartupFallback -> onStartupFallback(message)
            is Message.ProbeDone -> onProbeDone(message)
            is Message.SwitchDone -> onSwitchDone(message)
            is Message.SpeedSample -> onSpeedSample(message)
            is Message.SessionsClosed -> if (message.token == controller.currentGeneration()) message.then()
            is Message.Call -> message.block()
        }
    }

    // ================= Запуск, перезапуск, остановка =================

    private fun onStart(message: Message.Start) {
        stopInProgress = false
        val token = controller.nextGeneration()
        cancelRecovery()
        resetRecoveryCounters()
        forgetAutomaticReplacement()
        supervisor.reset()
        terminalError = false
        controller.beginConnectionDiagnostic(token, "user_start", message.profileId)
        controller.startConnectionDiagnosticStage(token, "profile", "Профиль и область приложений")
        controller.publish(
            token,
            VpnConnectionState.Starting(message.profileId, "Проверка профиля", message.updaterRouting),
        )
        host.showForeground(ForegroundState.ValidatingProfile)
        automationOverrideIdentity = null
        clearPaused()
        launchLifecycle(token, message.profileId, message.updaterRouting, noCacheLookup = false, message.startId)
    }

    private fun onRestart(message: Message.Restart) {
        val expected = message.expectedGeneration
        if (expected != null && (expected != controller.currentGeneration() || stopInProgress)) return
        stopInProgress = false
        val token = controller.nextGeneration()
        if (message.resetRecovery) {
            cancelRecovery()
            resetRecoveryCounters()
            forgetAutomaticReplacement()
        }
        val active = sessions.active()
        val profileId = active?.profileId ?: message.profileId
        val updaterRouting = message.updaterRouting ?: active?.updaterRouting ?: false
        if (profileId.isBlank()) {
            controller.publishMessage("VPN выключен; перезапуск не требуется.")
            stopIfIdle(message.startId)
            return
        }
        terminalError = false
        supervisor.reset()
        controller.beginConnectionDiagnostic(token, restartDiagnosticTrigger(message.reason), profileId)
        controller.startConnectionDiagnosticStage(token, "profile", "Профиль и область приложений")
        controller.publish(token, VpnConnectionState.Starting(profileId, message.reason, updaterRouting))
        host.showForeground(ForegroundState.Restarting)
        clearPaused()
        launchLifecycle(token, profileId, updaterRouting, message.noCacheLookup, message.startId)
    }

    /**
     * Одна попытка подключения. Предыдущая попытка и её сессии завершаются
     * до начала новой; решение по итогу принимает актор ([onStartFinished]).
     */
    private fun launchLifecycle(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
        noCacheLookup: Boolean,
        startId: Int,
    ) {
        phase = Phase.Starting
        cancelOperation()
        val previous = lifecycleJob
        previous?.cancel(CancellationException("VPN lifecycle заменён."))
        val closing = closeSessions(sessions.detachAll())
        val overrideIdentity = automationOverrideIdentity
        val settingsSnapshot = automationSettings
        val replacement = startupReplacement?.takeIf { it.profileId == profileId }
        lifecycleJob = scope.launch {
            previous?.join()
            closing.join()
            if (token != controller.currentGeneration()) return@launch
            try {
                val identity = starter.awaitConnectableNetwork(token, profileId, updaterRouting)
                post(Message.AttemptNetwork(token, identity))
                val latestSettings = runCatching {
                    container.uiSettingsStore.settings.first().networkAutomation
                }.getOrDefault(settingsSnapshot)
                val decision = NetworkAutomationRules.decide(
                    monitor().current,
                    updaterRouting,
                    latestSettings,
                    overrideIdentity,
                    wifiSsidReader::read,
                )
                if (decision is NetworkAutomationDecision.PauseVpn) {
                    post(Message.PauseAtStart(token, profileId, updaterRouting, decision.reason))
                    return@launch
                }
                starter.startWithDeadline(StartRequest(token, profileId, noCacheLookup, updaterRouting, replacement))
                post(Message.StartFinished(token, profileId, updaterRouting, startId, null))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                post(Message.StartFinished(token, profileId, updaterRouting, startId, error))
            }
        }
    }

    private fun onStop(message: Message.Stop) {
        if (stopInProgress) return
        stopInProgress = true
        val token = controller.nextGeneration()
        cancelDebounce()
        cancelRecovery()
        resetRecoveryCounters()
        forgetAutomaticReplacement()
        automationOverrideIdentity = null
        controller.cancelCurrentConnectionDiagnostic(
            if (message.trigger == "user_stop") "user_cancelled" else message.trigger,
        )
        controller.beginStopDiagnostic(token, message.trigger)
        message.systemPolicy?.let { controller.publishVpnSystemPolicy(token, it) }
        terminalError = message.errorMessage != null
        val pausedProfile = clearPaused()?.profileId
        cancelOperation()
        supervisor.abandon()
        val detached = sessions.detachAll()
        detached.forEach { it.enableStopDiagnostics(token) }
        val profileId = detached.firstOrNull()?.profileId ?: pausedProfile

        controller.startStopDiagnosticStage(token, "cancel_run", "Отмена текущего запуска")
        lifecycleJob?.cancel(CancellationException("VPN lifecycle отменён."))
        lifecycleJob = null
        controller.finishStopDiagnosticStage(token, "cancel_run")

        phase = Phase.Stopping
        controller.publish(token, VpnConnectionState.Stopping(profileId))
        host.showForeground(ForegroundState.Stopping)
        detached.forEach(CoreSession::closeTun)
        val closing = closeSessions(detached)
        scope.launch(start = CoroutineStart.ATOMIC) {
            closing.join()
            if (detached.isEmpty()) {
                listOf(
                    "close_tun" to "Закрытие Android TUN",
                    "close_clients" to "Отключение клиентов libbox",
                    "close_libbox_service" to "Остановка сервиса libbox",
                ).forEach { (key, label) ->
                    controller.startStopDiagnosticStage(token, key, label)
                    controller.finishStopDiagnosticStage(token, key)
                }
            }
            controller.completeStopDiagnostic(token)
            post(Message.StopCompleted(token, message.startId, message.errorMessage))
        }
    }

    private fun onStopCompleted(message: Message.StopCompleted) {
        synchronized(stopRequests) { stopRequests.completed = stopRequests.requested }
        if (message.token != controller.currentGeneration()) return
        phase = Phase.Idle
        closeNetworkMonitor()
        host.finishForeground()
        if (message.startId > 0) host.stopSelfResult(message.startId) else host.stopSelf()
        controller.publish(
            message.token,
            message.errorMessage?.let { VpnConnectionState.Error(it) } ?: VpnConnectionState.Stopped,
        )
    }

    /** Ядро само остановило сервис. Hysteria получает одну попытку резервного сервера. */
    private suspend fun onCoreStop() {
        val session = sessions.active()
        val selected = session?.let { currentPlan(it)?.currentId ?: it.selectedOutboundTag() }
        if (session == null || stopInProgress || session.generation != controller.currentGeneration() ||
            selected == null || session.outboundType(selected) != PathSupervisor.HYSTERIA_TYPE
        ) {
            onStop(Message.Stop(0, "sing-box остановил VPN-сервис.", null, "policy_stop"))
            return
        }
        val failure = VpnConnectionState.Error(
            message = "Hysteria2 runtime неожиданно остановился.",
            code = HysteriaFailureCode.LOCAL_PROCESS_EXITED.name,
            technicalDetail = "hysteria_failure=LOCAL_PROCESS_EXITED",
        )
        if (scheduleStartupReplacement(session.generation, session.profileId, session.updaterRouting, failure,
                HysteriaFailureCode.LOCAL_PROCESS_EXITED)
        ) {
            return
        }
        terminateSession(
            session,
            HysteriaFailureCode.NO_COMPATIBLE_FALLBACK,
            "Hysteria2 runtime остановился; совместимый резервный target не найден.",
        )
    }

    // ================= Итог попытки и восстановление =================

    private suspend fun onStartFinished(message: Message.StartFinished) {
        if (message.token != controller.currentGeneration()) return
        lifecycleJob = null
        val error = message.error
        if (error == null) {
            phase = Phase.Running
            resetRecoveryCounters()
            networkChangeSince = 0L
            startupReplacement = null
            startupReplacementUsed = false
            // Супервизор сброшен при старте попытки: подсказка, пришедшая между
            // Connected и этим сообщением, уже могла открыть эпизод.
            return
        }
        handleStartFailure(message.token, message.profileId, message.updaterRouting, message.startId, error)
    }

    /**
     * Отказ попытки. Порядок решений: провал резервного сервера → его код;
     * отказ сервера → одна немедленная замена; иначе — политика повторов.
     * Типизированный код ошибки (NET-/DNS-/VPN-) никогда не заменяется
     * угаданным по тексту: иначе «i/o timeout» в описании NET-102 превращал
     * восстановимую ошибку в смену сервера.
     */
    private suspend fun handleStartFailure(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
        startId: Int,
        error: Throwable,
    ) {
        cancelDebounce()
        val evidence = RuntimeErrors.startupEvidence(error, controller.runtimeErrors.forGeneration(token))
        val baseFailure = VpnFailureStates.from(error).let { fallback ->
            if (evidence == null) fallback else fallback.copy(
                message = "[${evidence.component}][${evidence.stage}] ${evidence.message}",
                code = evidence.code,
                technicalDetail = evidence.message,
            )
        }
        val stored = runCatching { container.profileStore.read(profileId) }.getOrNull()
        val plan = stored?.let { FailoverPlanner.plan(it.json, emptyMap(), primaryGroupTag = null) }
        val currentIsHysteria = plan?.currentType == PathSupervisor.HYSTERIA_TYPE

        val failedReplacement = startupReplacement?.takeIf { it.profileId == profileId }
        if (failedReplacement != null) {
            startupReplacement = null
            val replacementWasHysteria = stored?.let { profile ->
                ConfigAnalyzer.outboundDescriptions(profile.json)[failedReplacement.targetTag]?.type
            } == PathSupervisor.HYSTERIA_TYPE
            if (replacementWasHysteria) {
                val code = HysteriaFailureClassifier.classify(baseFailure.message) ?: HysteriaFailureCode.CORE_UNCLASSIFIED
                publishTerminalFailure(token, baseFailure.copy(code = code.name))
                stopService(startId)
                return
            }
            // Другие протоколы после неудачной замены идут обычным путём повторов.
        }

        val textCode = if (!error.hasTypedFailureCode() && plan != null) {
            HysteriaFailureClassifier.classify(baseFailure.message)
        } else {
            null
        }
        val failure = textCode?.let { baseFailure.copy(code = it.name) } ?: baseFailure
        if (textCode != null && textCode in PathSupervisor.SWITCHABLE_FAILURES) {
            if (failedReplacement == null &&
                scheduleStartupReplacement(token, profileId, updaterRouting, failure, textCode)
            ) {
                return
            }
            if (currentIsHysteria && startupReplacementUsed) {
                publishTerminalFailure(
                    token,
                    failure.copy(
                        message = "Hysteria2: единственный replacement target не готов.",
                        code = HysteriaFailureCode.NO_COMPATIBLE_FALLBACK.name,
                        technicalDetail = "hysteria_failure=${HysteriaFailureCode.NO_COMPATIBLE_FALLBACK.name}",
                    ),
                )
                stopService(startId)
                return
            }
        }
        val decision = recoveryDecision(profileId, failure)
        if (decision == VpnRecoveryDecision.Terminal) {
            publishTerminalFailure(token, failure)
            stopService(startId)
            return
        }
        scheduleRecovery(token, profileId, updaterRouting, failure, decision)
    }

    /**
     * Одна немедленная замена сервера после отказа запуска или остановки ядра:
     * перезапуск с кандидатом, который сохраняется в профиль только после
     * полной готовности.
     */
    private suspend fun scheduleStartupReplacement(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
        failure: VpnConnectionState.Error,
        code: HysteriaFailureCode,
    ): Boolean {
        if (startupReplacementUsed) return false
        startupReplacementUsed = true
        val stored = runCatching { container.profileStore.read(profileId) }.getOrNull() ?: return false
        val active = sessions.active()?.takeIf { it.profileId == profileId }
        val plan = FailoverPlanner.plan(
            stored.json,
            active?.runtimeSelections().orEmpty(),
            active?.primaryGroupTag,
        ) ?: return false
        val candidate = supervisor.candidates(plan, latencyHints(profileId)).firstOrNull() ?: return false
        val candidateJson = try {
            ConfigAnalyzer.selectServer(stored.json, plan.groupTag, candidate.id).also { json ->
                withContext(Dispatchers.Default) { Libbox.checkConfig(json) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return false
        }
        if (token != controller.currentGeneration() || stopInProgress) return false
        startupReplacement = PendingReplacement(profileId, candidateJson, candidate.id, plan.currentId)
        timeline("Запуск: отказ ${code.name} на ${server(plan.currentId)}; перезапуск на резервном ${server(candidate.id)}.")
        controller.publishRecoverableFailure(token, failure)
        controller.publish(
            token,
            VpnConnectionState.Reconnecting(
                profileId = profileId,
                message = "Немедленное переключение на резервный сервер",
                code = code.name,
                attempt = 1,
                maxAttempts = 1,
                updaterRouting = updaterRouting,
            ),
        )
        onRestart(
            Message.Restart(
                profileId = profileId,
                reason = REPLACEMENT_REASON,
                startId = 0,
                noCacheLookup = false,
                updaterRouting = updaterRouting,
                resetRecovery = false,
                expectedGeneration = token,
            ),
        )
        return true
    }

    private fun recoveryDecision(profileId: String, failure: VpnConnectionState.Error): VpnRecoveryDecision {
        if (stopInProgress || profileId.isBlank()) return VpnRecoveryDecision.Terminal
        val startedOn = attemptNetworkIdentity
        return VpnRecoveryPolicy.decide(
            failureCode = failure.code,
            attempt = recoveryAttempt,
            totalAttempts = recoveryTotalAttempts,
            networkChangedDuringAttempt = startedOn != null && startedOn != monitor().current.identity,
        )
    }

    /**
     * Держит сервис живым между попытками. Наблюдатель сети переживает отказ,
     * поэтому появление рабочей сети само поднимает VPN.
     */
    private fun scheduleRecovery(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
        failure: VpnConnectionState.Error,
        decision: VpnRecoveryDecision,
    ) {
        recoveryAttempt += 1
        recoveryTotalAttempts += 1
        val attempt = recoveryAttempt
        val failedOn = attemptNetworkIdentity
        phase = Phase.Recovering
        controller.publishRecoverableFailure(token, failure)
        cancelRecovery()
        recoveryJob = scope.launch {
            if (decision is VpnRecoveryDecision.RetryAfter) {
                publishRecovering(token, profileId, updaterRouting, failure,
                    "Повтор подключения через ${decision.delayMillis / 1_000} с", attempt)
                host.showForeground(ForegroundState.Retrying)
                delay(decision.delayMillis)
            }
            if (!monitor().current.isSettledForConnect()) {
                publishRecovering(token, profileId, updaterRouting, failure, "Ожидание сети Android", attempt)
                host.showForeground(ForegroundState.AwaitingNetwork)
            }
            val ready = awaitRecoveryNetwork()
            post(Message.RecoveryDue(token, profileId, updaterRouting, failedOn, ready.identity))
        }
    }

    private fun onRecoveryDue(message: Message.RecoveryDue) {
        if (message.token != controller.currentGeneration() || stopInProgress) return
        recoveryJob = null
        if (message.failedOnIdentity != null && message.readyIdentity != message.failedOnIdentity) {
            recoveryAttempt = 0
        }
        onRestart(
            Message.Restart(
                profileId = message.profileId,
                reason = "Автоматическое переподключение",
                startId = 0,
                noCacheLookup = false,
                updaterRouting = message.updaterRouting,
                resetRecovery = false,
                expectedGeneration = message.token,
            ),
        )
    }

    /**
     * Ждёт сеть столько, сколько нужно: выключенный на ночь Wi-Fi не должен
     * превращаться в ошибку. Это ожидание события ConnectivityManager, без
     * таймера и опроса; границы применяются к числу попыток, а не к простою.
     */
    private suspend fun awaitRecoveryNetwork(): UnderlyingNetworkState {
        while (true) {
            awaitNetworkStage(RECOVERY_SETTLE_WAIT_MILLIS) { it.isSettledForConnect() }?.let { return it }
            awaitNetworkStage(RECOVERY_USABLE_WAIT_MILLIS) { it.isUsableForConnect() }?.let { return it }
        }
    }

    private suspend fun awaitNetworkStage(
        timeoutMillis: Long,
        accept: (UnderlyingNetworkState) -> Boolean,
    ): UnderlyingNetworkState? = try {
        monitor().awaitUnderlying(timeoutMillis, accept)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: BootstrapFailureException) {
        null
    }

    private fun publishRecovering(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
        failure: VpnConnectionState.Error,
        message: String,
        attempt: Int,
    ) {
        controller.publish(
            token,
            VpnConnectionState.Reconnecting(
                profileId = profileId,
                message = message,
                code = failure.code,
                attempt = attempt,
                maxAttempts = VpnRecoveryPolicy.MAX_ATTEMPTS,
                updaterRouting = updaterRouting,
            ),
        )
    }

    /** Терминальное состояние публикуется только после освобождения ресурсов. */
    private fun publishTerminalFailure(token: Long, failure: VpnConnectionState.Error) {
        phase = Phase.Idle
        resetRecoveryCounters()
        terminalError = true
        closeNetworkMonitor()
        host.finishForeground()
        controller.publish(token, failure)
    }

    private fun stopService(startId: Int) {
        if (startId > 0) host.stopSelfResult(startId) else host.stopSelf()
    }

    /** Команда пришла, когда VPN выключен: сервис, поднятый ради неё, завершается. */
    private fun stopIfIdle(startId: Int) {
        if (phase != Phase.Idle) return
        host.finishForeground()
        host.stopSelfResult(startId)
    }

    /** Отказ работающей сессии, который автоматика исправить не может. */
    private fun terminateSession(session: CoreSession, code: HysteriaFailureCode, message: String) {
        if (sessions.active() !== session || session.generation != controller.currentGeneration()) return
        val token = controller.nextGeneration()
        timeline("Сессия закрыта: ${code.name}.")
        cancelOperation()
        supervisor.abandon()
        val detached = sessions.detachAll()
        detached.forEach(CoreSession::closeTun)
        closeThen(detached, token) {
            publishTerminalFailure(
                token,
                VpnConnectionState.Error(
                    message = message,
                    code = code.name,
                    technicalDetail = "runtime_failure=${code.name}",
                ),
            )
            host.stopSelf()
        }
    }

    private fun closeThen(detached: List<CoreSession>, token: Long, then: () -> Unit) {
        val closing = closeSessions(detached)
        scope.launch {
            closing.join()
            post(Message.SessionsClosed(token, then))
        }
    }

    private fun resetRecoveryCounters() {
        recoveryAttempt = 0
        recoveryTotalAttempts = 0
    }

    private fun forgetAutomaticReplacement() {
        startupReplacement = null
        startupReplacementUsed = false
    }

    private fun cancelRecovery() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    // ================= Выбор сервера, пинг, DNS-кэш =================

    private fun onSelect(message: Message.Select) {
        if (stopInProgress) return
        val session = sessions.active()
        // Connected публикуется стартером чуть раньше, чем актор узнаёт об итоге
        // попытки: активной сессии текущего поколения для выбора достаточно.
        if (session == null || session.profileId != message.profileId ||
            session.generation != controller.currentGeneration()
        ) {
            controller.publishMessage(
                if (phase == Phase.Idle) "Активный VPN-профиль не найден." else "Сервер можно выбрать после подключения.",
            )
            stopIfIdle(message.startId)
            return
        }
        supervisor.reset()
        startupReplacement = null
        session.resetThroughputWindow()
        // Ручной выбор фиксируется на 30 минут; failover это не затрагивает.
        smartPolicy.onManualSelection()
        timeline("Сервер выбран вручную: ${server(message.outboundTag)}.")
        runOperation(Operation.Select) { previous ->
            previous?.cancelAndJoin()
            val outcome = try {
                switcher.switch(session, message.groupTag, message.outboundTag)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SwitchOutcome.RolledBack(error)
            }
            post(Message.SelectDone(session, outcome, message.startId))
        }
    }

    private fun onSelectDone(message: Message.SelectDone) {
        val session = message.session
        if (sessions.active() !== session || session.generation != controller.currentGeneration()) return
        when (val outcome = message.outcome) {
            SwitchOutcome.Committed -> {
                host.showForeground(ForegroundState.Connected)
                controller.clearConnectionIdentity(session.generation)
                starter.startIdentityProbe(session)
            }
            is SwitchOutcome.RolledBack -> {
                controller.publishMessage(VpnFailureStates.from(outcome.error).message)
                host.showForeground(ForegroundState.Connected)
            }
            is SwitchOutcome.CoreUnreachable -> {
                // Команда в ядро не прошла: состояние селектора неизвестно — полный перезапуск.
                val token = controller.nextGeneration()
                controller.beginConnectionDiagnostic(token, "server_switch_restart", session.profileId)
                controller.startConnectionDiagnosticStage(token, "profile", "Профиль и область приложений")
                controller.publish(
                    token,
                    VpnConnectionState.Starting(session.profileId, "Перезапуск после смены сервера", session.updaterRouting),
                )
                host.showForeground(ForegroundState.Restarting)
                supervisor.reset()
                launchLifecycle(token, session.profileId, session.updaterRouting, false, message.startId)
            }
        }
    }

    private fun onPingGroup(message: Message.PingGroup) {
        val session = sessions.active()
        if (session == null || session.profileId != message.profileId) {
            controller.publishMessage("Активный VPN-профиль не найден.")
            stopIfIdle(message.startId)
            return
        }
        val group = controller.selectorGroups.value.firstOrNull { it.tag == message.groupTag }
        if (group == null || group.items.isEmpty()) {
            controller.publishMessage(session.generation, "Группа серверов не найдена в sing-box.")
            return
        }
        session.toggleLatencyProbe(group)
    }

    private fun onClearDnsCache(message: Message.ClearDnsCache) {
        val expected = controller.currentGeneration()
        scope.launch {
            container.bootstrapCache.clear()
            post(Message.ClearDnsCacheDone(message.startId, expected))
        }
    }

    private fun onClearDnsCacheDone(message: Message.ClearDnsCacheDone) {
        val profileId = sessions.active()?.profileId
        if (profileId == null) {
            controller.publishMessage("Bootstrap cache очищен; системный DNS-кэш Android не изменён.")
            stopIfIdle(message.startId)
            return
        }
        onRestart(
            Message.Restart(
                profileId = profileId,
                reason = "Сброс DNS-состояния",
                startId = message.startId,
                noCacheLookup = true,
                updaterRouting = null,
                expectedGeneration = message.expectedGeneration,
            ),
        )
    }

    // ================= Сеть и автоматизация по сети =================

    private fun onSessionNetwork(session: CoreSession, state: UnderlyingNetworkState) {
        if (sessions.active() !== session) return
        controller.publishDiagnosticNetwork(session.generation, state)
        if (state.identity != session.networkPolicyKey.identity) {
            session.onNetworkChanged()
            // Новая сеть: прежние выводы о серверах и пробы «умной проверки» устарели.
            cancelOperation()
            supervisor.reset()
        }
        if (automationDecision(state, session.updaterRouting) is NetworkAutomationDecision.PauseVpn) {
            debounce(debounceMillis(state), Message.AutomationPauseDue(session))
            return
        }
        val now = monotonicNow()
        val waited = if (networkChangeSince == 0L) 0L else now - networkChangeSince
        val plan = NetworkRestartPolicy.plan(
            sessionBaseline = session.networkPolicyKey,
            observed = state.policyKey(),
            observedSettled = state.isSettledForConnect(),
            waitedMillis = waited,
        )
        if (plan.decision == NetworkRestartDecision.KeepSession) {
            cancelDebounce()
            return
        }
        if (networkChangeSince == 0L) networkChangeSince = now
        debounce(plan.debounceMillis, Message.NetworkRestartDue(session))
    }

    private fun onNetworkRestartDue(session: CoreSession) {
        if (sessions.active() !== session || session.generation != controller.currentGeneration()) return
        if (NetworkRestartPolicy.decide(session.networkPolicyKey, monitor().current.policyKey()) ==
            NetworkRestartDecision.KeepSession
        ) {
            return
        }
        onRestart(
            Message.Restart(
                profileId = session.profileId,
                reason = "Смена сети Android",
                startId = 0,
                noCacheLookup = false,
                updaterRouting = null,
                expectedGeneration = session.generation,
            ),
        )
    }

    private fun onAutomationPauseDue(session: CoreSession) {
        if (sessions.active() !== session || session.generation != controller.currentGeneration()) return
        requestAutomationPause(session)
    }

    private fun requestAutomationPause(session: CoreSession) {
        val decision = automationDecision(monitor().current, session.updaterRouting)
        if (decision !is NetworkAutomationDecision.PauseVpn) return
        cancelRecovery()
        resetRecoveryCounters()
        cancelOperation()
        supervisor.abandon()
        val token = controller.nextGeneration()
        lifecycleJob?.cancel()
        lifecycleJob = null
        val detached = sessions.detachAll()
        detached.forEach(CoreSession::closeTun)
        closeThen(detached, token) {
            enterAutomationPause(token, session.profileId, session.updaterRouting, decision.reason)
        }
    }

    private fun enterAutomationPause(
        generation: Long,
        profileId: String,
        updaterRouting: Boolean,
        reason: NetworkPauseReason,
    ) {
        clearPaused()
        val current = PausedSession(profileId, updaterRouting, generation, reason)
        paused = current
        phase = Phase.Paused
        lifecycleJob = null
        terminalError = false
        controller.publish(generation, VpnConnectionState.Paused(profileId, reason.userMessage()))
        host.showForeground(ForegroundState.Paused)
        pausedObserver = monitor().observe { state -> post(Message.PausedNetwork(state)) }
        onPausedNetwork(monitor().current)
    }

    private fun onPausedNetwork(state: UnderlyingNetworkState) {
        val current = paused ?: return
        when (val decision = automationDecision(state, current.updaterRouting)) {
            is NetworkAutomationDecision.PauseVpn -> {
                cancelDebounce()
                updatePauseReason(current, decision.reason)
            }
            NetworkAutomationDecision.WaitForNetwork -> cancelDebounce()
            NetworkAutomationDecision.RunVpn -> debounce(debounceMillis(state), Message.AutomationResumeDue(current.generation))
        }
    }

    private fun updatePauseReason(current: PausedSession, reason: NetworkPauseReason) {
        if (reason == current.reason) return
        paused = current.copy(reason = reason)
        controller.publish(current.generation, VpnConnectionState.Paused(current.profileId, reason.userMessage()))
        host.showForeground(ForegroundState.Paused)
    }

    private fun onAutomationResumeDue(generation: Long) {
        if (paused?.generation != generation) return
        onResume(Message.Resume(0, manual = false))
    }

    private fun onResume(message: Message.Resume) {
        val current = paused ?: return
        if (!message.manual &&
            automationDecision(monitor().current, current.updaterRouting) != NetworkAutomationDecision.RunVpn
        ) {
            return
        }
        clearPaused()
        if (message.manual) automationOverrideIdentity = monitor().current.identity
        onRestart(
            Message.Restart(
                profileId = current.profileId,
                reason = if (message.manual) "Подключение до смены сети" else "Автоматическое подключение по сети",
                startId = message.startId,
                noCacheLookup = false,
                updaterRouting = current.updaterRouting,
            ),
        )
    }

    private fun onAutomationSettings(settings: NetworkAutomationSettings) {
        val changed = automationSettings != settings
        automationSettings = settings
        if (!changed) return
        sessions.active()?.let { session ->
            if (phase == Phase.Running) requestAutomationPause(session)
            return
        }
        val current = paused ?: return
        when (val decision = automationDecision(monitor().current, current.updaterRouting)) {
            is NetworkAutomationDecision.PauseVpn -> updatePauseReason(current, decision.reason)
            NetworkAutomationDecision.RunVpn -> onResume(Message.Resume(0, manual = false))
            NetworkAutomationDecision.WaitForNetwork -> Unit
        }
    }

    private fun clearPaused(): PausedSession? {
        val current = paused
        paused = null
        runCatching { pausedObserver?.close() }
        pausedObserver = null
        if (current != null && phase == Phase.Paused) phase = Phase.Idle
        return current
    }

    private fun automationDecision(
        state: UnderlyingNetworkState,
        updaterRouting: Boolean,
    ): NetworkAutomationDecision {
        val decision = NetworkAutomationRules.decide(
            state,
            updaterRouting,
            automationSettings,
            automationOverrideIdentity,
            wifiSsidReader::read,
        )
        if (automationOverrideIdentity != null && state.identity != automationOverrideIdentity) {
            automationOverrideIdentity = null
        }
        return decision
    }

    private fun debounceMillis(state: UnderlyingNetworkState): Long {
        val now = monotonicNow()
        val waited = if (networkChangeSince == 0L) 0L else now - networkChangeSince
        if (networkChangeSince == 0L) networkChangeSince = now
        return NetworkAutomationRules.debounceMillis(state, waited)
    }

    /**
     * Единственный дебаунс сети: перезапуск, пауза и возобновление по правилам
     * сети не бывают активны одновременно, новое событие заменяет старое.
     */
    private fun debounce(millis: Long, then: Message) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(millis)
            post(then)
        }
    }

    private fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
        networkChangeSince = 0L
    }

    // ================= Надзор за сервером работающей сессии =================

    private fun currentPlan(session: CoreSession, serverId: String? = null): FailoverPlan? {
        return FailoverPlanner.plan(session.profileJson, session.runtimeSelections(), session.primaryGroupTag, serverId)
    }

    private fun latencyHints(profileId: String): Map<String, LatencyHint> =
        container.serverLatencyStore.failoverHints(profileId)

    private fun isCurrent(session: CoreSession): Boolean =
        sessions.active() === session && session.generation == controller.currentGeneration() && !stopInProgress

    private fun onCoreHints(message: Message.CoreHints) {
        val session = sessions.active() ?: return
        if (session.generation != message.generation || !isCurrent(session)) return
        for (hint in message.hints) {
            val decision = supervisor.onHint(hint) { currentPlan(session, hint.outboundTag) }
            when (decision) {
                is PathDecision.Ignore -> Unit
                is PathDecision.Probe -> {
                    timeline("${server(decision.serverId)}: ${hint.code.name} в логе ядра — проверка связи.")
                    probeCode = hint.code
                    runOperation(Operation.Probe) { previous ->
                        previous?.cancelAndJoin()
                        val result = container.vpnLivenessProbe.check()
                        post(Message.ProbeDone(session, decision.episode, result))
                    }
                    return
                }
                else -> {
                    apply(session, decision, hint.code)
                    return
                }
            }
        }
    }

    private fun onProbeDone(message: Message.ProbeDone) {
        val session = message.session
        if (!isCurrent(session)) {
            supervisor.abandon()
            return
        }
        val result = message.result
        val decision = supervisor.onProbeResult(
            episode = message.episode,
            alive = result !is LivenessResult.Dead,
            plan = currentPlan(session),
            hints = latencyHints(session.profileId),
        )
        when (result) {
            is LivenessResult.Alive -> timeline(
                "Сервер отвечает (${result.endpoint}, ${result.elapsedMillis} мс): ложная тревога, переключения нет.",
            )
            LivenessResult.Unknown -> timeline("VPN-сеть Android недоступна для проверки; переключения нет.")
            is LivenessResult.Dead -> if (decision is PathDecision.Ignore) {
                timeline("Сервер не отвечает (${result.detail}); подходящей замены нет, остаёмся.")
            }
        }
        apply(session, decision, probeCode, (result as? LivenessResult.Dead)?.detail)
    }

    /**
     * Запуск закончился на резервном сервере. Сообщение приходит раньше итога
     * попытки, поэтому сверяется поколение, а не активная сессия.
     */
    private fun onStartupFallback(message: Message.StartupFallback) {
        val session = message.session
        if (session.generation != controller.currentGeneration() || stopInProgress) return
        supervisor.markDead(message.deadIds)
        timeline(
            "Запуск: ${message.deadIds.joinToString { server(it) }} не прошли проверку (${message.code.name}); " +
                "подключение через ${server(message.toId)}, ядро не перезапускалось.",
        )
    }

    private fun onInjectedFailure(message: Message.InjectedFailure) {
        val session = message.session
        if (!isCurrent(session)) return
        val plan = currentPlan(session) ?: return
        val decision = if (message.code in PathSupervisor.SWITCHABLE_FAILURES) {
            supervisor.onConfirmedFailure(plan, latencyHints(session.profileId))
        } else {
            supervisor.onHint(PathHint(plan.currentId, plan.currentType, message.code)) { plan }
        }
        apply(session, decision, message.code)
    }

    private fun apply(
        session: CoreSession,
        decision: PathDecision,
        code: HysteriaFailureCode,
        detail: String? = null,
    ) {
        when (decision) {
            is PathDecision.Ignore, is PathDecision.Probe -> Unit
            is PathDecision.Terminate -> terminateSession(session, decision.code, decision.message)
            is PathDecision.Switch -> {
                timeline(
                    "${server(decision.fromId)} не отвечает${detail?.let { " ($it)" }.orEmpty()}: " +
                        "переключение на ${server(decision.toId)}.",
                )
                val startedAt = monotonicNow()
                runOperation(Operation.Failover) { previous ->
                    previous?.cancelAndJoin()
                    val outcome = try {
                        switcher.switch(
                            session,
                            decision.groupTag,
                            decision.toId,
                            SwitchVerifier { candidateSession, json -> verifyCandidate(candidateSession, json) },
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        SwitchOutcome.RolledBack(error)
                    }
                    post(Message.SwitchDone(session, decision.episode, decision.fromId, decision.toId, code, startedAt, outcome))
                }
            }
        }
    }

    private fun onSwitchDone(message: Message.SwitchDone) {
        val session = message.session
        if (!isCurrent(session)) {
            supervisor.abandon()
            return
        }
        when (val outcome = message.outcome) {
            SwitchOutcome.Committed -> {
                supervisor.onSwitchResult(message.episode, committed = true)
                controller.publishDiagnosticWarning(
                    "Выполнено одно автоматическое переключение сервера (${message.code.name}) за " +
                        "${monotonicNow() - message.startedAt} мс; ядро не перезапускалось.",
                )
                session.resetThroughputWindow()
                controller.clearConnectionIdentity(session.generation)
                starter.startIdentityProbe(session)
            }
            is SwitchOutcome.RolledBack -> {
                supervisor.onSwitchResult(message.episode, committed = false)
                timeline(
                    "Резервный ${server(message.toId)} тоже не прошёл проверку " +
                        "(${VpnFailureStates.from(outcome.error).code}): вероятно, сбой сети. " +
                        "Остаёмся на ${server(message.fromId)}, автоматика ждёт восстановления.",
                )
            }
            is SwitchOutcome.CoreUnreachable -> {
                supervisor.onSwitchResult(message.episode, committed = false)
                timeline("Ядро не приняло команду переключения; перезапуск VPN.")
                onRestart(
                    Message.Restart(
                        profileId = session.profileId,
                        reason = "Перезапуск после сбоя управления ядром",
                        startId = 0,
                        noCacheLookup = false,
                        updaterRouting = null,
                        expectedGeneration = session.generation,
                    ),
                )
            }
        }
    }

    /** Проверка кандидата после команды селектора: тот же DNS/HTTPS, что при подключении. */
    private suspend fun verifyCandidate(session: CoreSession, candidateJson: String) {
        if (VpnTestHooks.consumeHysteriaReplacementFailure()) {
            throw IllegalStateException("Injected Hysteria replacement readiness failure.")
        }
        if (VpnTestHooks.consumeSwitchVerificationSuccess()) return
        val dnsServer = session.platform().internalDnsServer
            ?: throw IllegalStateException("libbox не передал внутренний DNS TUN.")
        container.vpnHealthPipeline.verify(
            mode = session.runtimeDnsMode,
            internalDnsServer = dnsServer,
            proxyIpFamily = io.github.zapretkvn.android.config.BootstrapConfig.selectedProxyIpFamily(candidateJson),
            onNetworkLease = { identity -> controller.recordConnectionVpnNetwork(session.generation, identity.toString()) },
            onNetworkLost = { controller.recordConnectionVpnNetwork(session.generation, lost = true) },
        )
    }

    private fun onObserverUnavailable(message: Message.ObserverUnavailable) {
        val session = sessions.active() ?: return
        if (session.generation != message.generation || message.generation != controller.currentGeneration() ||
            stopInProgress
        ) {
            return
        }
        terminateSession(
            session,
            HysteriaFailureCode.LOCAL_CONTROL_PLANE_UNAVAILABLE,
            SecretRedactor.redactInline(message.message),
        )
    }

    /**
     * Одна операция над селектором за раз: новая сначала отменяет предыдущую и
     * дожидается её отката. Приоритет задаёт порядок: ручной выбор и failover
     * вытесняют пробу «умной проверки», а не наоборот.
     */
    private fun runOperation(kind: Operation, block: suspend (previous: Job?) -> Unit) {
        val previous = operationJob
        previous?.cancel()
        operationKind = kind
        operationJob = scope.launch { block(previous) }
    }

    private fun cancelOperation() {
        operationJob?.cancel()
        operationJob = null
        operationKind = null
    }

    private fun operationActive(): Boolean = operationJob?.isActive == true

    // ================= «Умная проверка» скорости =================

    private fun onSmartSwitchSetting(enabled: Boolean) {
        smartSwitchEnabled = enabled
        // Выключение и включение настройки сбрасывают фиксацию ручного выбора.
        smartPolicy.resetManualHold()
        if (!enabled && operationKind == Operation.Smart) cancelOperation()
        sessions.active()?.let(::applySpeedMonitor)
    }

    /**
     * Монитор скорости нужен, только пока настройка включена и экран горит:
     * при погашенном экране и простое у сервиса нет периодической работы.
     */
    private fun applySpeedMonitor(session: CoreSession) {
        scope.launch {
            if (smartSwitchEnabled && screenInteractive) {
                session.openSpeedMonitorClient { total, connections ->
                    post(Message.SpeedSample(session, total, connections))
                }
            } else {
                session.closeSpeedMonitorClient()
            }
        }
    }

    private fun onSpeedSample(message: Message.SpeedSample) {
        val session = message.session
        if (!smartSwitchEnabled || !isCurrent(session)) return
        if (!session.onThroughputSample(monotonicNow(), message.total, message.connections)) return
        if (smartPolicy.gate(enabled = true, busy = false) != SlowSwitchGate.Ready) return
        if (operationActive() || supervisor.busy) return
        session.resetThroughputWindow()
        runOperation(Operation.Smart) { previous ->
            previous?.cancelAndJoin()
            SlowServerSwitchEngine(
                policy = smartPolicy,
                host = SmartSwitchHost(session, SmartContext(), switcher, container.vpnThroughputProbe, controller),
                enabled = { smartSwitchEnabled },
            ).runEpisode()
        }
    }

    private fun smartBusy(): Boolean =
        phase != Phase.Running || supervisor.busy || recoveryJob?.isActive == true || debounceJob?.isActive == true

    /** Доступ эпизода «умной проверки» к состоянию рантайма — только через актор. */
    private inner class SmartContext : SmartSwitchContext {
        override suspend fun snapshot(session: CoreSession) = ask {
            if (!this@VpnRuntime.isCurrent(session)) return@ask null
            val plan = currentPlan(session)
            plan to SlowSwitchSnapshot(
                currentId = plan?.currentId ?: session.selectedOutboundTag().orEmpty(),
                targets = plan?.targets.orEmpty(),
                hints = latencyHints(session.profileId),
                busy = smartBusy(),
            )
        }

        override suspend fun isCurrent(session: CoreSession) = ask { this@VpnRuntime.isCurrent(session) }

        override suspend fun canTrial(session: CoreSession, currentId: String) = ask {
            this@VpnRuntime.isCurrent(session) && !smartBusy() && currentPlan(session)?.currentId == currentId
        }

        override suspend fun canCommit(session: CoreSession) = ask {
            this@VpnRuntime.isCurrent(session) && operationKind == Operation.Smart && !supervisor.busy
        }

        override suspend fun onCommitted(session: CoreSession) = ask {
            controller.clearConnectionIdentity(session.generation)
            starter.startIdentityProbe(session)
        }

        override fun announce(session: CoreSession, message: String, notificationDetail: String) {
            controller.publishMessage(session.generation, message)
            post(Message.Call {
                if (this@VpnRuntime.isCurrent(session)) {
                    host.showForeground(ForegroundState.Connected, detail = notificationDetail)
                }
            })
        }

        override fun timeline(message: String) = this@VpnRuntime.timeline(message)
    }

    // ================= События сессии из потоков libbox/Android =================

    private inner class Events : SessionEvents {
        override fun onCoreLogs(generation: Long, messages: List<String>) {
            val hints = CoreLogHints.from(messages)
            if (hints.isNotEmpty()) post(Message.CoreHints(generation, hints))
        }

        override fun onObserverUnavailable(generation: Long, message: String) =
            post(Message.ObserverUnavailable(generation, message))

        override fun onCoreServiceStop() = post(Message.CoreStop)

        override fun onUnderlyingNetwork(session: CoreSession, state: UnderlyingNetworkState) =
            post(Message.SessionNetwork(session, state))

        override fun onSessionActivated(session: CoreSession) {
            session.attachStatusObserver(scope.launch {
                controller.homeVisible.collect { visible ->
                    if (sessions.active() !== session) return@collect
                    if (visible) session.openStatusClient() else session.closeStatusClient()
                }
            })
            session.attachDiagnosticsObserver(scope.launch {
                controller.diagnosticsVisible.collect { visible ->
                    if (sessions.active() !== session) return@collect
                    if (visible) session.openLogClient() else session.closeLogClient()
                }
            })
            smartPolicy.bindProfile(session.profileId)
            if (smartSwitchEnabled && screenInteractive) {
                session.openSpeedMonitorClient { total, connections ->
                    post(Message.SpeedSample(session, total, connections))
                }
            }
            if (!controller.diagnosticsVisible.value) session.closeLogClient()
        }

        override fun onInjectedFailure(session: CoreSession, code: HysteriaFailureCode) =
            post(Message.InjectedFailure(session, code))

        override fun onInjectedHint(session: CoreSession, code: HysteriaFailureCode) =
            post(Message.InjectedHint(session, code))

        override fun onStartupFallback(
            session: CoreSession,
            deadIds: List<String>,
            toId: String,
            code: HysteriaFailureCode,
        ) = post(Message.StartupFallback(session, deadIds.toList(), toId, code))
    }

    // ================= Вспомогательное =================

    /** Решения автоматики — в журнал диагностики, с замаскированными адресами. */
    private fun timeline(message: String) {
        controller.publishDiagnosticInfo(ServerAddressRedactor.redact(SecretRedactor.redactInline("Путь: $message")))
    }

    private fun server(tag: String): String = ServerAddressRedactor.redact(SecretRedactor.redactInline(tag))

    private fun restartDiagnosticTrigger(reason: String): String = when (reason) {
        "Смена сети Android" -> "network_change"
        "Автоматическое переподключение" -> "auto_recovery"
        "Сброс DNS-состояния" -> "dns_cache_clear"
        "Изменение маршрутизации" -> "routing_change"
        "Подписка обновлена пользователем" -> "subscription_refresh"
        "Смена режима DNS" -> "dns_mode_change"
        "Смена IP-стратегии DNS" -> "dns_strategy_change"
        "Смена защиты от localhost-чекеров" -> "endpoint_policy_change"
        "Смена имени VPN-сессии" -> "session_name_change"
        "Смена MTU для скрытия VPN" -> "mtu_change"
        REPLACEMENT_REASON -> "server_replacement"
        else -> "restart"
    }

    private companion object {
        const val REPLACEMENT_REASON = "Переключение на резервный сервер"

        /** Бюджеты ступеней ожидания сети при восстановлении; само ожидание не ограничено. */
        const val RECOVERY_SETTLE_WAIT_MILLIS = 30_000L
        const val RECOVERY_USABLE_WAIT_MILLIS = 180_000L

        fun monotonicNow(): Long = android.os.SystemClock.elapsedRealtime()
    }
}
