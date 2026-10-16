package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.OutboundDescription
import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.config.SelectorGroup
import io.github.zapretkvn.android.diagnostics.CoreDiagnosticBatchCollector
import io.github.zapretkvn.android.diagnostics.VpnRuntimeMetrics
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.engines.failover.SlowThroughputDetector
import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.UnderlyingPolicyKey
import io.github.zapretkvn.android.network.probes.LatencyProbeCoordinator
import io.github.zapretkvn.android.network.probes.ServerLatencyFingerprint
import io.github.zapretkvn.android.network.probes.ServerLatencyReducer
import io.github.zapretkvn.android.network.probes.ServerLatencyStore
import io.github.zapretkvn.android.network.probes.VpnExternalIpProbe
import io.github.zapretkvn.android.network.probes.services.AndroidServiceProbePaths
import io.github.zapretkvn.android.network.probes.services.ServiceAvailabilityCoordinator
import io.github.zapretkvn.android.platform.AndroidPlatformAdapter
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.android.vpn.VpnController
import io.github.zapretkvn.android.vpn.withRelayHistory
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Все ресурсы одного запуска ядра: Android-адаптер и TUN, command server,
 * клиенты libbox, наблюдатели и проверка задержек.
 *
 * Ресурс прикрепляется, только пока сессия не закрывается; иначе он сразу
 * освобождается. [close] идемпотентен, стадии остановки попадают в
 * диагностику остановки, счётчики [VpnRuntimeMetrics] всегда возвращаются к нулю.
 */
internal class CoreSession(
    val profileId: String,
    val profileName: String,
    /**
     * Профиль этой сессии, разобранный один раз при запуске: состав selector-групп
     * не меняется, пока она жива.
     */
    val profileConfig: ParsedConfig,
    val generation: Long,
    val networkMonitor: DefaultNetworkMonitor,
    val networkPolicyKey: UnderlyingPolicyKey,
    val outboundDescriptions: Map<String, OutboundDescription>,
    private val selectorGroups: List<SelectorGroup>,
    val primaryGroupTag: String?,
    initialSelectedOutboundTag: String?,
    val runtimeDnsMode: DnsMode,
    val updaterRouting: Boolean,
    private val controller: VpnController,
    private val scope: CoroutineScope,
    private val externalIpProbe: VpnExternalIpProbe,
    private val latencyStore: ServerLatencyStore,
    serviceProbePaths: AndroidServiceProbePaths,
) : AutoCloseable {
    /** JSON профиля этой сессии (текст [profileConfig]). */
    val profileJson: String get() = profileConfig.raw

    private val closing = AtomicBoolean(false)
    private val tunCloseStarted = AtomicBoolean(false)
    private val cleanupStarted = AtomicBoolean(false)
    private val cleanupComplete = CountDownLatch(1)
    private val libboxStarted = AtomicBoolean(false)
    private val resourceLock = Any()
    @Volatile private var platform: AndroidPlatformAdapter? = null
    @Volatile private var server: CommandServer? = null
    @Volatile private var groupClient: CommandClient? = null
    private var networkObserver: AutoCloseable? = null
    private var statusObserver: Job? = null
    private var diagnosticsObserver: Job? = null
    private var identityJob: Job? = null
    private var externalIpJob: Job? = null
    private var statusClient: CommandClient? = null
    private var statusClientCounted = false
    /** Отдельный поток CommandLog диагностики: нужен, только когда общий поток [runtimeErrorClient] её не питает. */
    private var logClient: CommandClient? = null

    /** Потребитель core-лога диагностики учтён в [VpnRuntimeMetrics] (отдельный поток или общий). */
    private var logClientCounted = false

    /**
     * Приёмник диагностики на общем потоке [runtimeErrorClient]: на старте оба
     * потребителя читают один CommandLog, отдельный поток открывается только при
     * повторном включении диагностики (ему нужен свежий backlog).
     */
    private val diagnosticLogTap = DiagnosticLogTap(controller, generation)

    /**
     * Единственный источник счётчиков трафика сессии. Его питает либо status-клиент
     * главной (1 Гц, прореживается до одного отсчёта в 5 с), либо отдельный
     * [speedMonitorClient] (5 с), но не оба сразу.
     */
    private val trafficFlow = MutableSharedFlow<TrafficSample>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val trafficLock = Any()
    private var lastTrafficEmitElapsed = Long.MIN_VALUE

    /** Экран включён: пока он горит, отсчёты идут и без главной (счётчики накопительные). */
    @Volatile private var screenInteractive = true

    /** Потребитель «умной проверки»: получает (downlinkTotal, connections) с каждым отсчётом. */
    @Volatile private var speedConsumer: ((Long, Int) -> Unit)? = null
    private var speedMonitorClient: CommandClient? = null
    private var runtimeErrorClient: CommandClient? = null
    private val runtimeErrorClientAvailable = AtomicBoolean(false)
    @Volatile private var runtimeErrorClientFailure: String? = null
    @Volatile private var runtimeErrorClientUnavailableCallback: ((String) -> Unit)? = null
    @Volatile private var selectedOutboundTag: String? = initialSelectedOutboundTag
    @Volatile private var stopDiagnosticGeneration = Long.MIN_VALUE

    /** Фактический участник каждой selector-группы по сообщениям ядра. */
    private val runtimeSelection = ConcurrentHashMap<String, String>()

    /**
     * Выбор, скомандованный приложением, и когда. Сообщение групп приходит с
     * задержкой: снимок, сделанный до команды, не должен вернуть старый сервер.
     */
    private val commandedSelection = ConcurrentHashMap<String, Pair<String, Long>>()

    /** Переключения селектора этой сессии идут строго по одному. */
    val switchLock = Mutex()

    /** Отпечатки адресов серверов профиля: ключ сохранённого пинга. */
    val serverFingerprints: Map<String, String> = ServerLatencyFingerprint.of(outboundDescriptions)
    private val latencyProbeCoordinator = LatencyProbeCoordinator(
        generation = generation,
        scope = scope,
        networkMonitor = networkMonitor,
        controller = controller,
        onResults = { relay -> latencyStore.record(profileId, serverFingerprints, relay) },
    )
    private val serviceAvailabilityCoordinator = ServiceAvailabilityCoordinator(
        generation = generation,
        scope = scope,
        networkMonitor = networkMonitor,
        controller = controller,
        paths = serviceProbePaths,
        profileJson = profileJson,
    )
    private val throughputDetector = SlowThroughputDetector()

    init {
        controller.registerServiceCheckHandler(generation, serviceAvailabilityCoordinator::requestCheck)
        controller.registerExternalIpHandler(generation, ::requestExternalIp)
        VpnRuntimeMetrics.sessionOpened()
        selectorGroups.forEach { group -> group.default?.let { runtimeSelection[group.tag] = it } }
    }

    val isClosing: Boolean get() = closing.get()

    fun startLatencyProbe(group: RuntimeSelectorGroup, only: Set<String>? = null) =
        latencyProbeCoordinator.start(group, only)

    fun stopLatencyProbe() = latencyProbeCoordinator.stop()

    /**
     * Группы от ядра: запоминается фактический выбор, а сохранённый пинг
     * подставляется сразу, до новой проверки.
     */
    fun onRuntimeGroups(groups: List<RuntimeSelectorGroup>): List<RuntimeSelectorGroup> {
        val now = android.os.SystemClock.elapsedRealtime()
        groups.forEach { group ->
            if (group.selected.isBlank()) return@forEach
            val commanded = commandedSelection[group.tag]
            if (commanded != null && commanded.first != group.selected &&
                now - commanded.second < COMMAND_SETTLE_MILLIS
            ) {
                return@forEach
            }
            runtimeSelection[group.tag] = group.selected
        }
        return ServerLatencyReducer.hydrate(groups, latencyStore.forProfile(profileId), serverFingerprints)
    }

    /** Выбранный в ядре участник каждой группы (по последнему сообщению групп). */
    fun runtimeSelections(): Map<String, String> = HashMap(runtimeSelection)

    /** Сервер или идентичность выхода сменились: результаты проверки сервисов устарели. */
    fun markServicesStale() = serviceAvailabilityCoordinator.markStale()

    /** Выход определён: после подключения один раз сама запустится проверка сервисов. */

    /**
     * Ручной запрос внешнего IP (кнопка на главном экране). Один запрос за раз;
     * автоматически, при подключении или переключении сервера, не вызывается.
     */
    fun requestExternalIp() {
        val job = synchronized(resourceLock) {
            if (closing.get() || externalIpJob?.isActive == true) return
            scope.launch(start = CoroutineStart.LAZY) {
                controller.publishExternalIpLoading(generation)
                val address = runCatching { externalIpProbe.fetch() }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                controller.publishExternalIp(generation, address)
            }.also { externalIpJob = it }
        }
        job.start()
    }

    /** Сервер сменился: идущий запрос относится к прежнему выходу, его ответ не нужен. */
    fun cancelExternalIpRequest() {
        val job = synchronized(resourceLock) { externalIpJob.also { externalIpJob = null } }
        job?.cancel()
    }

    fun onNetworkChanged() {
        latencyProbeCoordinator.onNetworkChanged()
        serviceAvailabilityCoordinator.markStale()
        throughputDetector.reset()
    }

    fun onThroughputSample(nowMillis: Long, downlinkTotal: Long, connections: Int): Boolean =
        throughputDetector.onSample(nowMillis, downlinkTotal, connections)

    fun resetThroughputWindow() = throughputDetector.reset()

    fun selectedOutboundTag(): String? = selectedOutboundTag

    fun outboundType(tag: String): String? = outboundDescriptions[tag]?.type

    /** Селектор переключён командой приложения: учесть сразу, не дожидаясь сообщения групп. */
    fun recordCommandedSelection(groupTag: String, outboundTag: String) {
        commandedSelection[groupTag] = outboundTag to android.os.SystemClock.elapsedRealtime()
        runtimeSelection[groupTag] = outboundTag
    }

    /** Селектор уже переключён командой и выбор сохранён: зафиксировать в сессии. */
    fun commitSelection(groupTag: String, outboundTag: String) {
        recordCommandedSelection(groupTag, outboundTag)
        if (groupTag == primaryGroupTag) selectedOutboundTag = outboundTag
    }

    /**
     * Отсчёты счётчиков ядра (CommandStatus), накопительные с начала сессии, не чаще
     * одного в ~5 с. Поток открыт, пока сессия живёт и экран включён (или открыты
     * главная/диагностика); при погашенном экране отсчёты не идут и клиент закрыт.
     * `replay = 1`: подписчик сразу получает последний отсчёт - сверяйте его
     * [TrafficSample.elapsedRealtimeMillis] с текущим временем, он может быть старым.
     * Подписка бесплатна: клиентов CommandStatus столько же, сколько без неё.
     */
    fun trafficSamples(): SharedFlow<TrafficSample> = trafficFlow.asSharedFlow()

    /** Последний отсчёт (или null, если ещё не было). */
    fun lastTrafficSample(): TrafficSample? = trafficFlow.replayCache.lastOrNull()

    /**
     * Принять отсчёт от любого из двух клиентов. [throttle] - источник чаще, чем раз в
     * 5 с (status главной): лишние отсчёты отбрасываются.
     */
    private fun acceptTraffic(uplinkTotal: Long, downlinkTotal: Long, connections: Int, throttle: Boolean) {
        if (closing.get()) return
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(trafficLock) {
            val minInterval = if (throttle) SAMPLE_MIN_INTERVAL_MILLIS else SAMPLE_DEDUP_MILLIS
            if (lastTrafficEmitElapsed != Long.MIN_VALUE && now - lastTrafficEmitElapsed < minInterval) return
            lastTrafficEmitElapsed = now
        }
        // Итоги только через прокси — прямой вызов в ядро в момент отсчёта (без подписки
        // на соединения, I6-05): прямой трафик иначе маскирует мёртвый прокси.
        val proxy = runCatching { server?.proxyTraffic() }.getOrNull()?.takeIf { it.available }
        trafficFlow.tryEmit(
            TrafficSample(
                now,
                uplinkTotal,
                downlinkTotal,
                connections,
                proxyUplinkTotal = proxy?.uplinkTotal,
                proxyDownlinkTotal = proxy?.downlinkTotal,
                proxyConnections = proxy?.connections,
            ),
        )
        controller.recordSessionTraffic(
            generation,
            now,
            uplinkTotal,
            downlinkTotal,
            proxy?.uplinkTotal,
            proxy?.downlinkTotal,
            proxy?.connections,
        )
        speedConsumer?.invoke(downlinkTotal, connections)
    }

    /**
     * Экран включился или погас. Идёт из потока Android: клиент открывается/закрывается
     * в [scope], а не на вызывающем потоке.
     */
    fun onScreenInteractive(interactive: Boolean) {
        screenInteractive = interactive
        if (closing.get()) return
        scope.launch { reconcileTrafficClient() }
    }

    /**
     * «Умная проверка»: подписка на отсчёты потока [trafficSamples] (раз в ~5 с).
     * Отдельного клиента CommandStatus она не открывает - им управляет экран и главная.
     */
    @Synchronized
    fun openSpeedMonitorClient(onSample: (Long, Int) -> Unit) {
        if (closing.get()) return
        throughputDetector.reset()
        speedConsumer = onSample
        reconcileTrafficClient()
    }

    @Synchronized
    fun closeSpeedMonitorClient() {
        if (speedConsumer == null) return
        speedConsumer = null
        throughputDetector.reset()
    }

    /**
     * Отдельный клиент CommandStatus (5 с) нужен, пока экран горит или открыта диагностика,
     * и только если главная не держит свой status-клиент: тот питает поток сам.
     */
    @Synchronized
    private fun reconcileTrafficClient() {
        val diagnosticsObserved = logClient != null || diagnosticLogTap.enabled
        val wanted = !closing.get() && statusClient == null && (screenInteractive || diagnosticsObserved)
        if (wanted) openTrafficClientLocked() else closeTrafficClientLocked()
    }

    private fun openTrafficClientLocked() {
        if (speedMonitorClient != null) return
        throughputDetector.reset()
        val candidate = Libbox.newCommandClient(
            SpeedMonitorClientHandler(controller, generation) { up, down, connections ->
                acceptTraffic(up, down, connections, throttle = false)
            },
            CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                statusInterval = SPEED_MONITOR_INTERVAL_NANOS
            },
        )
        try {
            candidate.connect()
            if (closing.get()) {
                runCatching { candidate.disconnect() }
                return
            }
            speedMonitorClient = candidate
            VpnRuntimeMetrics.speedMonitorClientOpened()
        } catch (_: Throwable) {
            runCatching { candidate.disconnect() }
        }
    }

    private fun closeTrafficClientLocked() {
        val current = speedMonitorClient ?: return
        speedMonitorClient = null
        runCatching { current.disconnect() }
        VpnRuntimeMetrics.speedMonitorClientClosed()
        throughputDetector.reset()
    }

    fun attachPlatform(candidate: AndroidPlatformAdapter) {
        val accepted = synchronized(resourceLock) {
            if (closing.get()) false else {
                check(platform == null)
                platform = candidate
                true
            }
        }
        if (!accepted) {
            candidate.close()
            throw CancellationException("Запуск отменён до создания TUN.")
        }
    }

    fun platform(): AndroidPlatformAdapter =
        platform ?: throw CancellationException("Android VPN adapter уже закрыт.")

    fun attachServer(candidate: CommandServer) {
        val accepted = synchronized(resourceLock) {
            if (closing.get()) false else {
                check(server == null)
                server = candidate
                true
            }
        }
        if (!accepted) {
            runCatching { candidate.close() }
            throw CancellationException("Запуск command server отменён.")
        }
    }

    fun attachGroupClient(candidate: CommandClient) {
        val accepted = synchronized(resourceLock) {
            if (closing.get()) false else {
                check(groupClient == null)
                groupClient = candidate
                true
            }
        }
        if (!accepted) {
            runCatching { candidate.disconnect() }
            throw CancellationException("Подключение command client отменено.")
        }
    }

    /**
     * Клиент группы: его стрим CommandGroup читает состояние, а команды
     * (`selectOutbound`) идут по тому же соединению. Отдельный клиент под команды
     * не нужен.
     */
    fun groupClient(): CommandClient? = groupClient

    fun attachNetworkObserver(candidate: AutoCloseable) {
        val accepted = synchronized(resourceLock) {
            if (closing.get()) false else {
                check(networkObserver == null)
                networkObserver = candidate
                true
            }
        }
        if (!accepted) runCatching { candidate.close() }
    }

    fun attachStatusObserver(candidate: Job) = attachJob(candidate) {
        check(statusObserver == null)
        statusObserver = candidate
    }

    fun attachDiagnosticsObserver(candidate: Job) = attachJob(candidate) {
        check(diagnosticsObserver == null)
        diagnosticsObserver = candidate
    }

    fun replaceIdentityJob(candidate: Job) {
        val previous = synchronized(resourceLock) {
            if (closing.get()) null else identityJob.also { identityJob = candidate }
        }
        previous?.cancel()
        if (closing.get()) candidate.cancel()
    }

    private inline fun attachJob(candidate: Job, crossinline attach: () -> Unit) {
        val accepted = synchronized(resourceLock) {
            if (closing.get()) false else {
                attach()
                true
            }
        }
        if (!accepted) candidate.cancel()
    }

    fun markLibboxStarted() {
        if (!closing.get() && libboxStarted.compareAndSet(false, true)) {
            VpnRuntimeMetrics.libboxOpened()
        }
    }

    fun enableStopDiagnostics(generation: Long) {
        stopDiagnosticGeneration = generation
    }

    /** Закрывает TUN немедленно; остальное освобождает [close]. */
    fun closeTun() {
        closing.set(true)
        if (!tunCloseStarted.compareAndSet(false, true)) return
        timedStopStage("close_tun", "Закрытие Android TUN") {
            val current = synchronized(resourceLock) { platform.also { platform = null } }
            current?.close()
        }
    }

    @Synchronized
    fun openStatusClient() {
        if (closing.get() || statusClient != null) return
        val candidate = Libbox.newCommandClient(
            StatusClientHandler(controller, generation) { up, down, connections ->
                acceptTraffic(up, down, connections, throttle = true)
            },
            CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                statusInterval = STATUS_INTERVAL_NANOS
            },
        )
        try {
            candidate.connect()
            if (closing.get()) {
                runCatching { candidate.disconnect() }
                return
            }
            statusClient = candidate
            statusClientCounted = true
            VpnRuntimeMetrics.statusClientOpened()
            controller.publishStatusStream(generation, true)
            // Главная питает поток сама: отдельный 5-секундный клиент больше не нужен.
            reconcileTrafficClient()
        } catch (_: Throwable) {
            runCatching { candidate.disconnect() }
            controller.publishStatusStream(generation, false)
        }
    }

    @Synchronized
    fun closeStatusClient() {
        val current = statusClient
        statusClient = null
        runCatching { current?.disconnect() }
        if (statusClientCounted) {
            statusClientCounted = false
            VpnRuntimeMetrics.statusClientClosed()
        }
        controller.publishStatusStream(generation, false)
        // Главная скрыта: если экран горит, поток продолжит отдельный 5-секундный клиент.
        if (current != null) reconcileTrafficClient()
    }

    @Synchronized
    fun openLogClient() {
        if (closing.get() || logClient != null || diagnosticLogTap.enabled) return
        val candidate = Libbox.newCommandClient(
            DiagnosticLogClientHandler(controller, generation),
            CommandClientOptions().apply { addCommand(Libbox.CommandLog) },
        )
        try {
            candidate.connect()
            if (closing.get()) {
                runCatching { candidate.disconnect() }
                return
            }
            logClient = candidate
            logClientCounted = true
            VpnRuntimeMetrics.logClientOpened()
            controller.publishDiagnosticLogStream(generation, true)
            reconcileTrafficClient()
        } catch (_: Throwable) {
            runCatching { candidate.disconnect() }
            controller.publishDiagnosticLogStream(generation, false)
        }
    }

    @Synchronized
    fun closeLogClient() {
        val current = logClient
        logClient = null
        diagnosticLogTap.enabled = false
        runCatching { current?.disconnect() }
        if (logClientCounted) {
            logClientCounted = false
            VpnRuntimeMetrics.logClientClosed()
        }
        controller.publishDiagnosticLogStream(generation, false)
        reconcileTrafficClient()
    }

    /**
     * Постоянный наблюдатель ошибок ядра. Без него сессия не публикуется как
     * подключённая: отказы серверов иначе некому было бы увидеть.
     *
     * [mirrorDiagnostics] — тот же стрим питает и журнал диагностики (вместо
     * второго CommandLog-клиента). Открывать нужно до запуска сервиса: так
     * диагностика получает стартовые строки с первой.
     */
    @Synchronized
    fun openRuntimeErrorClient(
        onLogs: (Long, List<String>) -> Unit,
        onUnavailable: (Long, String) -> Unit,
        mirrorDiagnostics: Boolean = false,
    ) {
        if (closing.get() || runtimeErrorClient != null) return
        if (VpnTestHooks.consumeHysteriaFailureObserverConnectFailure()) {
            throw RuntimeErrorObserverUnavailableException()
        }
        val mirror = mirrorDiagnostics && logClient == null && !diagnosticLogTap.enabled
        if (mirror) diagnosticLogTap.enabled = true
        runtimeErrorClientUnavailableCallback = { message -> onUnavailable(generation, message) }
        val closeRequested = AtomicBoolean(false)
        val candidate = Libbox.newCommandClient(
            RuntimeErrorLogClientHandler(
                generation = generation,
                onLogs = onLogs,
                onEntry = { level, message ->
                    controller.recordCoreFailure(generation, level, message)
                    // Важные строки копятся всегда, а не только пока открыта диагностика.
                    controller.recordCoreLine(generation, level, message)
                },
                onConnected = { runtimeErrorClientAvailable.set(true) },
                onDisconnected = { message ->
                    val expectedClose = closing.get() || closeRequested.get()
                    controller.recordCommandLogDisconnect(generation, message, expectedClose)
                    if (!expectedClose) runtimeErrorClientFailure = message
                    val wasAvailable = runtimeErrorClientAvailable.getAndSet(false)
                    if (wasAvailable && !expectedClose) runtimeErrorClientUnavailableCallback?.invoke(message)
                },
                diagnostics = diagnosticLogTap,
            ),
            CommandClientOptions().apply { addCommand(Libbox.CommandLog) },
        )
        try {
            candidate.connect()
            if (closing.get()) {
                runCatching { candidate.disconnect() }
                throw CancellationException("Подключение failure-log client отменено.")
            }
            if (!runtimeErrorClientAvailable.get()) throw RuntimeErrorObserverUnavailableException()
            runtimeErrorClient = candidate
            if (mirror) {
                logClientCounted = true
                VpnRuntimeMetrics.logClientOpened()
                controller.publishDiagnosticLogStream(generation, true)
            }
        } catch (error: Throwable) {
            closeRequested.set(true)
            if (mirror) diagnosticLogTap.enabled = false
            runtimeErrorClientAvailable.set(false)
            runCatching { candidate.disconnect() }
            if (error is CancellationException) throw error
            if (error is RuntimeErrorObserverUnavailableException) throw error
            throw RuntimeErrorObserverUnavailableException(error)
        }
    }

    fun requireRuntimeErrorClient() {
        if (closing.get()) throw CancellationException("VPN-сессия уже закрывается.")
        if (runtimeErrorClient == null || !runtimeErrorClientAvailable.get()) {
            throw RuntimeErrorObserverUnavailableException(runtimeErrorClientFailure?.let(::IllegalStateException))
        }
    }

    fun simulateFailureLogDisconnect() {
        if (!BuildConfig.DEBUG || closing.get()) return
        if (runtimeErrorClientAvailable.getAndSet(false)) {
            runtimeErrorClientUnavailableCallback?.invoke("CommandLog test disconnect")
        }
    }

    override fun close() {
        closing.set(true)
        if (!cleanupStarted.compareAndSet(false, true)) {
            cleanupComplete.await()
            return
        }
        try {
            timedStopStage("close_latency_probe", "Остановка проверки задержек") {
                latencyProbeCoordinator.close()
                serviceAvailabilityCoordinator.close()
            }
            closeTun()
            timedStopStage("close_observers", "Остановка callback и фоновых задач") {
                val jobs = synchronized(resourceLock) {
                    listOfNotNull(statusObserver, diagnosticsObserver, identityJob, externalIpJob).also {
                        statusObserver = null
                        diagnosticsObserver = null
                        identityJob = null
                        externalIpJob = null
                    }
                }
                jobs.forEach(Job::cancel)
            }
            timedStopStage("close_clients", "Отключение клиентов libbox") {
                closeStatusClient()
                closeSpeedMonitorClient()
                synchronized(this) { closeTrafficClientLocked() }
                closeLogClient()
                val failureClient = synchronized(resourceLock) {
                    runtimeErrorClient.also { runtimeErrorClient = null }
                }
                runtimeErrorClientAvailable.set(false)
                runtimeErrorClientUnavailableCallback = null
                runCatching { failureClient?.disconnect() }
                val groups = synchronized(resourceLock) { groupClient.also { groupClient = null } }
                runCatching { groups?.disconnect() }
            }
            timedStopStage("close_libbox_service", "Остановка сервиса libbox") {
                runCatching { server?.closeService() }.getOrThrow()
            }
            if (libboxStarted.compareAndSet(true, false)) VpnRuntimeMetrics.libboxClosed()
            timedStopStage("close_command_server", "Закрытие command server") {
                val current = synchronized(resourceLock) { server.also { server = null } }
                runCatching { current?.close() }.getOrThrow()
            }
            // Монитор принадлежит рантайму и переживает сессию: его наблюдатель
            // нужен восстановлению после отказа. Сессия снимает только свою подписку.
            timedStopStage("close_network", "Отписка от мониторинга сети") {
                val observer = synchronized(resourceLock) { networkObserver.also { networkObserver = null } }
                runCatching { observer?.close() }
            }
        } finally {
            VpnRuntimeMetrics.sessionClosed()
            cleanupComplete.countDown()
        }
    }

    private inline fun timedStopStage(key: String, label: String, action: () -> Unit) {
        val diagnosticGeneration = stopDiagnosticGeneration
        if (diagnosticGeneration != Long.MIN_VALUE) {
            controller.startStopDiagnosticStage(diagnosticGeneration, key, label)
        }
        val error = runCatching(action).exceptionOrNull()
        if (diagnosticGeneration != Long.MIN_VALUE) {
            controller.finishStopDiagnosticStage(diagnosticGeneration, key, error)
        }
    }

    internal companion object {
        const val STATUS_INTERVAL_NANOS = 1_000_000_000L

        /** Отсчёт «умной проверки»: окно 20 с — это 4 отсчёта. */
        const val SPEED_MONITOR_INTERVAL_NANOS = 5_000_000_000L

        /** Источник чаще 5 с (status главной, 1 Гц) прореживается до одного отсчёта в этот срок. */
        const val SAMPLE_MIN_INTERVAL_MILLIS = 4_500L

        /** Два клиента не должны дать два отсчёта подряд при переключении источника. */
        const val SAMPLE_DEDUP_MILLIS = 1_500L
        const val MAX_FAILURE_LOG_BATCH_LINES = 32

        /** Столько снимок групп, противоречащий команде, считается устаревшим. */
        const val COMMAND_SETTLE_MILLIS = 3_000L
    }
}

/** Команды ядра к Android-стороне: только остановка сервиса. */
internal class CoreServerHandler(
    private val onServiceStop: () -> Unit,
) : CommandServerHandler {
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun serviceReload() = throw UnsupportedOperationException("Reload выполняет Android-сервис.")
    override fun serviceStop() = onServiceStop()
    override fun setSystemProxyEnabled(enabled: Boolean) {
        check(!enabled) { "Системный proxy не поддерживается." }
    }
    override fun writeDebugMessage(message: String) = Unit
    override fun triggerNativeCrash() = throw UnsupportedOperationException("Отладочный крэш ядра отключён.")
    override fun connectSSHAgent(): Int = throw UnsupportedOperationException("SSH-агент недоступен на Android.")
}

internal abstract class BaseClientHandler : CommandClientHandler {
    override fun connected() = Unit
    override fun disconnected(message: String) = Unit
    override fun setDefaultLogLevel(level: Int) = Unit
    override fun clearLogs() = Unit
    override fun writeLogs(messageList: LogIterator) = Unit
    override fun writeStatus(message: StatusMessage) = Unit
    override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
    override fun updateClashMode(newMode: String) = Unit
    override fun writeConnectionEvents(events: ConnectionEvents) = Unit
    override fun writeGroups(message: OutboundGroupIterator) = Unit
    override fun writeOutbounds(message: OutboundGroupItemIterator) = Unit
}

private class StatusClientHandler(
    private val controller: VpnController,
    private val generation: Long,
    private val onSample: (uplinkTotal: Long, downlinkTotal: Long, connections: Int) -> Unit,
) : BaseClientHandler() {
    override fun writeStatus(message: StatusMessage) {
        if (generation != controller.currentGeneration() || !message.trafficAvailable) return
        VpnRuntimeMetrics.updateTraffic(message.uplinkTotal, message.downlinkTotal)
        controller.publishTraffic(
            generation = generation,
            uploadDelta = message.uplink,
            downloadDelta = message.downlink,
            uploadTotal = message.uplinkTotal,
            downloadTotal = message.downlinkTotal,
        )
        onSample(message.uplinkTotal, message.downlinkTotal, maxOf(message.connectionsIn, message.connectionsOut))
    }
}

private class SpeedMonitorClientHandler(
    private val controller: VpnController,
    private val generation: Long,
    private val onSample: (uplinkTotal: Long, downlinkTotal: Long, connections: Int) -> Unit,
) : BaseClientHandler() {
    override fun writeStatus(message: StatusMessage) {
        if (generation != controller.currentGeneration() || !message.trafficAvailable) return
        // connectionsIn — трекеры traffic manager-а: каждое маршрутизированное
        // соединение; connectionsOut — только соединения route ConnectionManager.
        onSample(message.uplinkTotal, message.downlinkTotal, maxOf(message.connectionsIn, message.connectionsOut))
    }
}

/** Публикация строк core-лога в диагностику; общая для отдельного и для общего потока CommandLog. */
internal class DiagnosticLogTap(
    private val controller: VpnController,
    private val generation: Long,
    @Volatile var enabled: Boolean = false,
) {
    fun clearLogs() {
        if (enabled) controller.clearCoreDiagnosticLogs(generation)
    }

    /** Сборщик пачки строк или `null`, если диагностика сейчас не читает лог. */
    fun newBatch(): CoreDiagnosticBatchCollector? = if (enabled) CoreDiagnosticBatchCollector() else null

    fun publish(collector: CoreDiagnosticBatchCollector) {
        val batch = collector.result()
        controller.publishCoreDiagnosticLogs(generation, batch.entries, batch.droppedLines, batch.noise, batch.failures)
    }

    fun disconnected() {
        if (enabled) controller.publishDiagnosticLogStream(generation, false)
    }
}

private class DiagnosticLogClientHandler(
    controller: VpnController,
    generation: Long,
) : BaseClientHandler() {
    private val tap = DiagnosticLogTap(controller, generation, enabled = true)

    override fun disconnected(message: String) = tap.disconnected()

    override fun clearLogs() = tap.clearLogs()

    override fun writeLogs(messageList: LogIterator) {
        val collector = tap.newBatch() ?: return
        while (messageList.hasNext()) {
            val entry = messageList.next()
            collector.add(entry.level, entry.message)
        }
        tap.publish(collector)
    }
}

/** Always-on bounded detector; raw messages are classified in memory only. */
internal class RuntimeErrorLogClientHandler(
    private val generation: Long,
    private val onLogs: (Long, List<String>) -> Unit,
    private val onEntry: (Int, String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String) -> Unit,
    /** Тот же стрим питает журнал диагностики, пока приёмник включён. */
    private val diagnostics: DiagnosticLogTap? = null,
) : BaseClientHandler() {
    override fun connected() = onConnected()

    override fun disconnected(message: String) {
        onDisconnected(message)
        diagnostics?.disconnected()
    }

    override fun clearLogs() {
        diagnostics?.clearLogs()
    }

    override fun writeLogs(messageList: LogIterator) {
        val messages = ArrayList<String>(CoreSession.MAX_FAILURE_LOG_BATCH_LINES)
        val batch = diagnostics?.newBatch()
        while (messageList.hasNext()) {
            val entry = messageList.next()
            batch?.add(entry.level, entry.message)
            onEntry(entry.level, entry.message)
            messages += entry.message
            if (messages.size == CoreSession.MAX_FAILURE_LOG_BATCH_LINES) {
                onLogs(generation, messages.toList())
                messages.clear()
            }
        }
        if (messages.isNotEmpty()) onLogs(generation, messages)
        if (batch != null) diagnostics?.publish(batch)
    }
}

internal class GroupClientHandler(
    private val controller: VpnController,
    private val generation: Long,
    private val descriptions: Map<String, OutboundDescription>,
    private val primaryGroupTag: String?,
    private val onGroups: (List<RuntimeSelectorGroup>) -> List<RuntimeSelectorGroup>,
) : BaseClientHandler() {
    override fun writeGroups(message: OutboundGroupIterator) {
        val groups = buildList {
            while (message.hasNext()) {
                val group = message.next()
                val items = buildList {
                    val iterator = group.items
                    while (iterator.hasNext()) {
                        val item = iterator.next()
                        val description = descriptions[item.tag]
                        add(
                            RuntimeOutboundItem(
                                tag = item.tag,
                                type = item.type.ifBlank { description?.type ?: "unknown" },
                                endpoint = description?.endpoint,
                            ).withRelayHistory(item.urlTestTime, item.urlTestDelay),
                        )
                    }
                }
                add(
                    RuntimeSelectorGroup(
                        tag = group.tag,
                        type = group.type,
                        selected = group.selected,
                        selectable = group.selectable,
                        items = items,
                        primary = group.tag == primaryGroupTag,
                    ),
                )
            }
        }
        controller.publishGroups(generation, onGroups(groups))
    }
}
