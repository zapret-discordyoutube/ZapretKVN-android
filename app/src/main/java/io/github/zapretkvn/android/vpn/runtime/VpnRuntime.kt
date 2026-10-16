package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.CoreConfigCheck
import io.github.zapretkvn.android.diagnostics.RuntimeErrors
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.diagnostics.VpnFailureStates
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureClassifier
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.network.CurrentWifiSsidReader
import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.NetworkAutomationDecision
import io.github.zapretkvn.android.network.NetworkAutomationSettings
import io.github.zapretkvn.android.network.NetworkPauseReason
import io.github.zapretkvn.android.network.UnderlyingNetworkState
import io.github.zapretkvn.android.platform.VpnSystemPolicy
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnRecoveryDecision
import io.github.zapretkvn.android.vpn.ZapretVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
 *
 * Политики вынесены в координаторы, которые живут внутри актора и меняют своё
 * состояние только из него: [NetworkAutomationCoordinator] (пауза и перезапуск
 * по сети), [RecoveryCoordinator] (повтор после отказа попытки),
 * [ServerSwitchCoordinator] (выбор сервера, failover, «умная проверка»).
 * Здесь остаются фаза, поколение, запуск и остановка сессий.
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

    // ---- Состояние актора: читается и меняется только в handle(). ----
    private var phase = RuntimePhase.Idle
    private var stopInProgress = false
    private var lifecycleJob: Job? = null
    private var startupReplacement: PendingReplacement? = null
    private var startupReplacementUsed = false
    @Volatile var terminalError = false
        private set
    private val supervisor = PathSupervisor { monotonicNow() }

    private val log = RuntimeTimeline(container.vpnController)
    private val ops = ActorOps()

    private val automation = NetworkAutomationCoordinator(
        scope = scope,
        sessions = sessions,
        controller = container.vpnController,
        host = host,
        ops = ops,
        monitor = ::monitor,
        readWifiSsid = { wifiSsidReader.read() },
        postToActor = ::postCall,
        clock = { monotonicNow() },
    )
    private val recovery = RecoveryCoordinator(
        scope = scope,
        controller = container.vpnController,
        host = host,
        ops = ops,
        monitor = ::monitor,
        postToActor = ::postCall,
    )
    private val switching = ServerSwitchCoordinator(
        scope = scope,
        container = container,
        sessions = sessions,
        controller = container.vpnController,
        host = host,
        ops = ops,
        switcher = switcher,
        supervisor = supervisor,
        log = log,
        onExitChanged = { session -> starter.onExitChanged(session) },
        postToActor = ::postCall,
        clock = { monotonicNow() },
    )

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

    fun pingGroup(
        profileId: String,
        groupTag: String,
        startId: Int,
        stop: Boolean = false,
        only: List<String> = emptyList(),
    ) = post(Message.PingGroup(profileId, groupTag, startId, stop, only))

    fun clearDnsCache(startId: Int) = post(Message.ClearDnsCache(startId))

    fun resume(startId: Int) = post(Message.Resume(startId, manual = true))

    /** Экран включён/погашен: «умная проверка» и поток счётчиков трафика сессии следуют за ним. */
    @Volatile private var screenInteractive = true

    fun onScreenInteractive(interactive: Boolean) {
        screenInteractive = interactive
        switching.onScreenInteractive(interactive)
        postCall { sessions.active()?.onScreenInteractive(interactive) }
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
        automation.close()
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

    private fun postCall(block: () -> Unit) {
        post(Message.Call(block))
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
        data class PingGroup(
            val profileId: String,
            val groupTag: String,
            val startId: Int,
            val stop: Boolean = false,
            /** Только эти серверы группы; пусто — вся группа. */
            val only: List<String> = emptyList(),
        ) : Message
        data class ClearDnsCache(val startId: Int) : Message
        data class ClearDnsCacheDone(val startId: Int, val expectedGeneration: Long) : Message
        data class Resume(val startId: Int, val manual: Boolean) : Message
        data object CoreStop : Message
        data class AutomationSettings(val settings: NetworkAutomationSettings) : Message
        data class SmartSwitchSetting(val enabled: Boolean) : Message
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
        data class SessionNetwork(val session: CoreSession, val state: UnderlyingNetworkState) : Message
        data class CoreHints(val generation: Long, val hints: List<PathHint>) : Message
        data class DnsFailures(val generation: Long, val hint: DnsFailureHint) : Message
        data class ObserverUnavailable(val generation: Long, val message: String) : Message
        data class InjectedFailure(val session: CoreSession, val code: HysteriaFailureCode) : Message
        data class InjectedHint(val session: CoreSession, val code: HysteriaFailureCode) : Message
        data class StartupFallback(
            val session: CoreSession,
            val deadIds: List<String>,
            val toId: String,
            val code: HysteriaFailureCode,
        ) : Message
        data class SessionsClosed(val token: Long, val then: () -> Unit) : Message
        class Call(val block: () -> Unit) : Message
    }

    private suspend fun handle(message: Message) {
        when (message) {
            is Message.Start -> onStart(message)
            is Message.Restart -> onRestart(message)
            is Message.Stop -> onStop(message)
            is Message.StopCompleted -> onStopCompleted(message)
            is Message.Select -> switching.onSelect(
                message.profileId,
                message.groupTag,
                message.outboundTag,
                message.startId,
            )
            is Message.PingGroup -> onPingGroup(message)
            is Message.ClearDnsCache -> onClearDnsCache(message)
            is Message.ClearDnsCacheDone -> onClearDnsCacheDone(message)
            is Message.Resume -> automation.onResume(message.startId, message.manual)
            Message.CoreStop -> onCoreStop()
            is Message.AutomationSettings -> automation.onSettingsChanged(message.settings)
            is Message.SmartSwitchSetting -> switching.onSmartSwitchSetting(message.enabled)
            is Message.AttemptNetwork -> if (message.token == controller.currentGeneration()) {
                recovery.onAttemptNetwork(message.identity)
            }
            is Message.PauseAtStart -> if (message.token == controller.currentGeneration()) {
                automation.enterPause(message.token, message.profileId, message.updaterRouting, message.reason)
            }
            is Message.StartFinished -> onStartFinished(message)
            is Message.SessionNetwork -> automation.onSessionNetwork(message.session, message.state)
            is Message.CoreHints -> switching.onCoreHints(message.generation, message.hints)
            is Message.DnsFailures -> switching.onDnsFailures(message.generation, message.hint)
            is Message.ObserverUnavailable -> onObserverUnavailable(message)
            is Message.InjectedFailure -> switching.onInjectedFailure(message.session, message.code)
            is Message.InjectedHint -> switching.onInjectedHint(message.session, message.code)
            is Message.StartupFallback -> switching.onStartupFallback(
                message.session,
                message.deadIds,
                message.toId,
                message.code,
            )
            is Message.SessionsClosed -> if (message.token == controller.currentGeneration()) message.then()
            is Message.Call -> message.block()
        }
    }

    // ================= Запуск, перезапуск, остановка =================

    private fun onStart(message: Message.Start) {
        stopInProgress = false
        val token = controller.nextGeneration()
        recovery.cancel()
        recovery.resetCounters()
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
        automation.clearOverride()
        automation.clearPaused()
        launchLifecycle(token, message.profileId, message.updaterRouting, noCacheLookup = false, message.startId)
    }

    private fun onRestart(message: Message.Restart) {
        val expected = message.expectedGeneration
        if (expected != null && (expected != controller.currentGeneration() || stopInProgress)) return
        stopInProgress = false
        val token = controller.nextGeneration()
        if (message.resetRecovery) {
            recovery.cancel()
            recovery.resetCounters()
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
        val trigger = restartDiagnosticTrigger(message.reason)
        // Причина - в журнал приложения и в события завершающейся сессии: до открытия новой.
        timeline("Перезапуск ($trigger): ${message.reason}.")
        controller.beginConnectionDiagnostic(token, trigger, profileId)
        controller.startConnectionDiagnosticStage(token, "profile", "Профиль и область приложений")
        controller.publish(token, VpnConnectionState.Starting(profileId, message.reason, updaterRouting))
        host.showForeground(ForegroundState.Restarting)
        automation.clearPaused()
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
        phase = RuntimePhase.Starting
        switching.cancelOperation()
        val previous = lifecycleJob
        previous?.cancel(CancellationException("VPN lifecycle заменён."))
        val closing = closeSessions(sessions.detachAll())
        val overrideIdentity = automation.overrideIdentity
        val settingsSnapshot = automation.settings
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
        automation.cancelDebounce()
        recovery.cancel()
        recovery.resetCounters()
        forgetAutomaticReplacement()
        automation.clearOverride()
        controller.cancelCurrentConnectionDiagnostic(
            if (message.trigger == "user_stop") "user_cancelled" else message.trigger,
        )
        controller.beginStopDiagnostic(token, message.trigger)
        message.systemPolicy?.let { controller.publishVpnSystemPolicy(token, it) }
        terminalError = message.errorMessage != null
        val pausedProfile = automation.clearPaused()
        switching.cancelOperation()
        supervisor.abandon()
        val detached = sessions.detachAll()
        detached.forEach { it.enableStopDiagnostics(token) }
        val profileId = detached.firstOrNull()?.profileId ?: pausedProfile

        controller.startStopDiagnosticStage(token, "cancel_run", "Отмена текущего запуска")
        lifecycleJob?.cancel(CancellationException("VPN lifecycle отменён."))
        lifecycleJob = null
        controller.finishStopDiagnosticStage(token, "cancel_run")

        phase = RuntimePhase.Stopping
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
        phase = RuntimePhase.Idle
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
        val selected = session?.let { switching.currentPlan(it)?.currentId ?: it.selectedOutboundTag() }
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

    // ================= Итог попытки и восстановление =================

    private suspend fun onStartFinished(message: Message.StartFinished) {
        if (message.token != controller.currentGeneration()) return
        lifecycleJob = null
        val error = message.error
        if (error == null) {
            phase = RuntimePhase.Running
            recovery.resetCounters()
            automation.resetChangeWindow()
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
        automation.cancelDebounce()
        val typed = error.hasTypedFailureCode()
        val evidence = RuntimeErrors.startupEvidence(
            error,
            controller.runtimeErrors.forGeneration(token),
            hasTypedFailure = typed,
        )
        val baseFailure = VpnFailureStates.from(error).let { fallback ->
            when {
                evidence == null -> fallback
                // Типизированный код и сообщение остаются: свидетельство ядра только справка.
                typed -> fallback.copy(
                    technicalDetail = listOfNotNull(
                        fallback.technicalDetail,
                        "core_evidence=${evidence.code}: ${evidence.message}".take(400),
                    ).joinToString("; "),
                )
                else -> fallback.copy(
                    message = "[${evidence.component}][${evidence.stage}] ${evidence.message}",
                    code = evidence.code,
                    technicalDetail = evidence.message,
                )
            }
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
                val code = HysteriaFailureClassifier.classify(baseFailure.message)
                    ?: evidence?.let { HysteriaFailureClassifier.classify(it.message) }
                    ?: HysteriaFailureCode.CORE_UNCLASSIFIED
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
        val decision = recovery.decide(profileId, failure)
        if (decision == VpnRecoveryDecision.Terminal) {
            publishTerminalFailure(token, failure)
            stopService(startId)
            return
        }
        recovery.schedule(token, profileId, updaterRouting, failure, decision)
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
        val candidate = supervisor.candidates(plan, switching.latencyHints(profileId)).firstOrNull() ?: return false
        val candidateJson = try {
            ConfigAnalyzer.selectServer(stored.json, plan.groupTag, candidate.id).also { json ->
                withContext(Dispatchers.Default) { CoreConfigCheck.check(json) }
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

    /** Терминальное состояние публикуется только после освобождения ресурсов. */
    private fun publishTerminalFailure(token: Long, failure: VpnConnectionState.Error) {
        phase = RuntimePhase.Idle
        recovery.resetCounters()
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
        if (phase != RuntimePhase.Idle) return
        host.finishForeground()
        host.stopSelfResult(startId)
    }

    /** Отказ работающей сессии, который автоматика исправить не может. */
    private fun terminateSession(session: CoreSession, code: HysteriaFailureCode, message: String) {
        if (sessions.active() !== session || session.generation != controller.currentGeneration()) return
        val token = controller.nextGeneration()
        timeline("Сессия закрыта: ${code.name}.")
        switching.cancelOperation()
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

    private fun forgetAutomaticReplacement() {
        startupReplacement = null
        startupReplacementUsed = false
    }


    // ================= Пинг группы, DNS-кэш =================

    private fun onPingGroup(message: Message.PingGroup) {
        val session = sessions.active()
        if (session == null || session.profileId != message.profileId) {
            controller.publishMessage("Активный VPN-профиль не найден.")
            stopIfIdle(message.startId)
            return
        }
        if (message.stop) {
            session.stopLatencyProbe()
            return
        }
        val group = controller.selectorGroups.value.firstOrNull { it.tag == message.groupTag }
        if (group == null || group.items.isEmpty()) {
            controller.publishMessage(session.generation, "Группа серверов не найдена в sing-box.")
            return
        }
        val only = message.only.toSet().takeIf(Set<String>::isNotEmpty)
        if (only != null && group.items.none { it.tag in only }) {
            controller.publishMessage(session.generation, "Этих серверов уже нет в группе: список обновился.")
            return
        }
        session.startLatencyProbe(group, only)
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

    // ================= События сессии из потоков libbox/Android =================

    // ================= События сессии из потоков libbox/Android =================

    private inner class Events : SessionEvents {
        override fun onCoreLogs(generation: Long, messages: List<String>) {
            val hints = CoreLogHints.from(messages)
            if (hints.isNotEmpty()) post(Message.CoreHints(generation, hints))
            CoreLogHints.dnsFailures(messages)?.let { post(Message.DnsFailures(generation, it)) }
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
            switching.onSessionActivated(session)
            if (!controller.diagnosticsVisible.value) session.closeLogClient()
            // Поток счётчиков трафика сессии идёт, пока экран горит (или открыты главная/диагностика).
            session.onScreenInteractive(screenInteractive)
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
    private fun timeline(message: String) = log.note(message)

    private fun server(tag: String): String = log.server(tag)

    private fun restartDiagnosticTrigger(reason: String): String = RestartTriggers.fromReason(reason)

    /** Операции актора для координаторов политик; вызываются только внутри актора. */
    private inner class ActorOps : NetworkAutomationOps, ServerSwitchOps {
        override var phase: RuntimePhase
            get() = this@VpnRuntime.phase
            set(value) {
                this@VpnRuntime.phase = value
            }

        override val stopInProgress: Boolean get() = this@VpnRuntime.stopInProgress

        override fun restart(
            profileId: String,
            reason: String,
            startId: Int,
            updaterRouting: Boolean?,
            expectedGeneration: Long?,
            noCacheLookup: Boolean,
            resetRecovery: Boolean,
        ) = onRestart(
            Message.Restart(
                profileId = profileId,
                reason = reason,
                startId = startId,
                noCacheLookup = noCacheLookup,
                updaterRouting = updaterRouting,
                resetRecovery = resetRecovery,
                expectedGeneration = expectedGeneration,
            ),
        )

        override fun suspendForPause(then: (token: Long) -> Unit) {
            recovery.cancel()
            recovery.resetCounters()
            switching.cancelOperation()
            supervisor.abandon()
            val token = controller.nextGeneration()
            lifecycleJob?.cancel()
            lifecycleJob = null
            val detached = sessions.detachAll()
            detached.forEach(CoreSession::closeTun)
            closeThen(detached, token) { then(token) }
        }

        override fun onPauseEntered() {
            lifecycleJob = null
            terminalError = false
        }

        override fun onUnderlyingNetworkReplaced() {
            switching.cancelOperation()
            supervisor.reset()
            switching.onNetworkReplaced()
        }

        override fun terminateSession(session: CoreSession, code: HysteriaFailureCode, message: String) =
            this@VpnRuntime.terminateSession(session, code, message)

        override fun stopIfIdle(startId: Int) = this@VpnRuntime.stopIfIdle(startId)

        override fun clearPendingReplacement() {
            startupReplacement = null
        }

        override fun relaunchAfterSelectFailure(session: CoreSession, startId: Int) {
            val token = controller.nextGeneration()
            controller.beginConnectionDiagnostic(token, "server_switch_restart", session.profileId)
            controller.startConnectionDiagnosticStage(token, "profile", "Профиль и область приложений")
            controller.publish(
                token,
                VpnConnectionState.Starting(session.profileId, "Перезапуск после смены сервера", session.updaterRouting),
            )
            host.showForeground(ForegroundState.Restarting)
            supervisor.reset()
            launchLifecycle(token, session.profileId, session.updaterRouting, false, startId)
        }

        override fun lifecycleBusy(): Boolean =
            this@VpnRuntime.phase != RuntimePhase.Running || recovery.active || automation.debouncing
    }

    private companion object {
        const val REPLACEMENT_REASON = RestartTriggers.REPLACEMENT_REASON

        fun monotonicNow(): Long = android.os.SystemClock.elapsedRealtime()
    }
}
