package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.OutboundDescription
import io.github.zapretkvn.android.config.SelectorGroup
import io.github.zapretkvn.android.diagnostics.CoreDiagnosticBatchCollector
import io.github.zapretkvn.android.diagnostics.VpnRuntimeMetrics
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.engines.failover.SlowThroughputDetector
import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.UnderlyingPolicyKey
import io.github.zapretkvn.android.network.probes.IcmpPingProbe
import io.github.zapretkvn.android.network.probes.LatencyProbeCoordinator
import io.github.zapretkvn.android.network.probes.ServerLatencyFingerprint
import io.github.zapretkvn.android.network.probes.ServerLatencyReducer
import io.github.zapretkvn.android.network.probes.ServerLatencyStore
import io.github.zapretkvn.android.network.probes.ServerPingTargetResolver
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
import kotlinx.coroutines.Job
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
    /** JSON профиля этой сессии: состав selector-групп не меняется, пока она жива. */
    val profileJson: String,
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
    scope: CoroutineScope,
    icmpPingProbe: IcmpPingProbe,
    private val latencyStore: ServerLatencyStore,
) : AutoCloseable {
    private val closing = AtomicBoolean(false)
    private val tunCloseStarted = AtomicBoolean(false)
    private val cleanupStarted = AtomicBoolean(false)
    private val cleanupComplete = CountDownLatch(1)
    private val libboxStarted = AtomicBoolean(false)
    private val resourceLock = Any()
    @Volatile private var platform: AndroidPlatformAdapter? = null
    @Volatile private var server: CommandServer? = null
    @Volatile private var groupClient: CommandClient? = null
    @Volatile private var selectorClient: CommandClient? = null
    private var networkObserver: AutoCloseable? = null
    private var statusObserver: Job? = null
    private var diagnosticsObserver: Job? = null
    private var identityJob: Job? = null
    private var statusClient: CommandClient? = null
    private var statusClientCounted = false
    private var logClient: CommandClient? = null
    private var logClientCounted = false
    private var speedMonitorClient: CommandClient? = null
    private var runtimeErrorClient: CommandClient? = null
    private val runtimeErrorClientAvailable = AtomicBoolean(false)
    @Volatile private var runtimeErrorClientFailure: String? = null
    @Volatile private var runtimeErrorClientUnavailableCallback: ((String) -> Unit)? = null
    @Volatile private var selectedOutboundTag: String? = initialSelectedOutboundTag
    @Volatile private var stopDiagnosticGeneration = Long.MIN_VALUE

    /** Фактический участник каждой selector-группы по сообщениям ядра. */
    private val runtimeSelection = ConcurrentHashMap<String, String>()

    /** Переключения селектора этой сессии идут строго по одному. */
    val switchLock = Mutex()

    /** Отпечатки адресов серверов профиля: ключ сохранённого пинга. */
    val serverFingerprints: Map<String, String> = ServerLatencyFingerprint.of(outboundDescriptions)
    private val pingTargetResolver = ServerPingTargetResolver(
        outboundDescriptions,
        selectorGroups,
        primaryGroupTag,
    )
    private val latencyProbeCoordinator = LatencyProbeCoordinator(
        generation = generation,
        scope = scope,
        networkMonitor = networkMonitor,
        targetResolver = pingTargetResolver,
        icmpProbe = icmpPingProbe,
        controller = controller,
        onResults = { relay, icmp ->
            latencyStore.record(profileId, serverFingerprints, relay, icmp)
        },
    )
    private val throughputDetector = SlowThroughputDetector()

    init {
        VpnRuntimeMetrics.sessionOpened()
        selectorGroups.forEach { group -> group.default?.let { runtimeSelection[group.tag] = it } }
    }

    val isClosing: Boolean get() = closing.get()

    fun toggleLatencyProbe(group: RuntimeSelectorGroup) = latencyProbeCoordinator.toggle(group)

    /**
     * Группы от ядра: запоминается фактический выбор, а сохранённый пинг
     * подставляется сразу, до новой проверки.
     */
    fun onRuntimeGroups(groups: List<RuntimeSelectorGroup>): List<RuntimeSelectorGroup> {
        groups.forEach { group ->
            if (group.selected.isNotBlank()) runtimeSelection[group.tag] = group.selected
        }
        return ServerLatencyReducer.hydrate(groups, latencyStore.forProfile(profileId), serverFingerprints)
    }

    /** Выбранный в ядре участник каждой группы (по последнему сообщению групп). */
    fun runtimeSelections(): Map<String, String> = HashMap(runtimeSelection)

    fun onNetworkChanged() {
        latencyProbeCoordinator.onNetworkChanged()
        throughputDetector.reset()
    }

    fun onThroughputSample(nowMillis: Long, downlinkTotal: Long, connections: Int): Boolean =
        throughputDetector.onSample(nowMillis, downlinkTotal, connections)

    fun resetThroughputWindow() = throughputDetector.reset()

    fun selectedOutboundTag(): String? = selectedOutboundTag

    fun outboundType(tag: String): String? = outboundDescriptions[tag]?.type

    /** Селектор уже переключён командой: зафиксировать выбор в сессии. */
    fun commitSelection(groupTag: String, outboundTag: String) {
        runtimeSelection[groupTag] = outboundTag
        if (groupTag == primaryGroupTag) selectedOutboundTag = outboundTag
    }

    /**
     * Отдельный CommandStatus «умной проверки» (раз в 5 с), пока сессия жива и
     * настройка включена. Главный status-клиент (1 Гц) живёт только при
     * видимой главной.
     */
    @Synchronized
    fun openSpeedMonitorClient(onSample: (Long, Int) -> Unit) {
        if (closing.get() || speedMonitorClient != null) return
        throughputDetector.reset()
        val candidate = Libbox.newCommandClient(
            SpeedMonitorClientHandler(controller, generation, onSample),
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

    @Synchronized
    fun closeSpeedMonitorClient() {
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

    fun attachSelectorClient(candidate: CommandClient) {
        val accepted = synchronized(resourceLock) {
            if (closing.get()) false else {
                check(selectorClient == null)
                selectorClient = candidate
                true
            }
        }
        if (!accepted) {
            runCatching { candidate.disconnect() }
            throw CancellationException("Клиент управления selector отменён.")
        }
    }

    fun selectorClient(): CommandClient? = selectorClient

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
            StatusClientHandler(controller, generation),
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
    }

    @Synchronized
    fun openLogClient() {
        if (closing.get() || logClient != null) return
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
        } catch (_: Throwable) {
            runCatching { candidate.disconnect() }
            controller.publishDiagnosticLogStream(generation, false)
        }
    }

    @Synchronized
    fun closeLogClient() {
        val current = logClient
        logClient = null
        runCatching { current?.disconnect() }
        if (logClientCounted) {
            logClientCounted = false
            VpnRuntimeMetrics.logClientClosed()
        }
        controller.publishDiagnosticLogStream(generation, false)
    }

    /**
     * Постоянный наблюдатель ошибок ядра. Без него сессия не публикуется как
     * подключённая: отказы серверов иначе некому было бы увидеть.
     */
    @Synchronized
    fun openRuntimeErrorClient(
        onLogs: (Long, List<String>) -> Unit,
        onUnavailable: (Long, String) -> Unit,
    ) {
        if (closing.get() || runtimeErrorClient != null) return
        if (VpnTestHooks.consumeHysteriaFailureObserverConnectFailure()) {
            throw RuntimeErrorObserverUnavailableException()
        }
        runtimeErrorClientUnavailableCallback = { message -> onUnavailable(generation, message) }
        val closeRequested = AtomicBoolean(false)
        val candidate = Libbox.newCommandClient(
            RuntimeErrorLogClientHandler(
                generation = generation,
                onLogs = onLogs,
                onEntry = { level, message -> controller.recordCoreFailure(generation, level, message) },
                onConnected = { runtimeErrorClientAvailable.set(true) },
                onDisconnected = { message ->
                    val expectedClose = closing.get() || closeRequested.get()
                    controller.recordCommandLogDisconnect(generation, message, expectedClose)
                    if (!expectedClose) runtimeErrorClientFailure = message
                    val wasAvailable = runtimeErrorClientAvailable.getAndSet(false)
                    if (wasAvailable && !expectedClose) runtimeErrorClientUnavailableCallback?.invoke(message)
                },
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
        } catch (error: Throwable) {
            closeRequested.set(true)
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
            }
            closeTun()
            timedStopStage("close_observers", "Остановка callback и фоновых задач") {
                val jobs = synchronized(resourceLock) {
                    listOfNotNull(statusObserver, diagnosticsObserver, identityJob).also {
                        statusObserver = null
                        diagnosticsObserver = null
                        identityJob = null
                    }
                }
                jobs.forEach(Job::cancel)
            }
            timedStopStage("close_clients", "Отключение клиентов libbox") {
                closeStatusClient()
                closeSpeedMonitorClient()
                closeLogClient()
                val failureClient = synchronized(resourceLock) {
                    runtimeErrorClient.also { runtimeErrorClient = null }
                }
                runtimeErrorClientAvailable.set(false)
                runtimeErrorClientUnavailableCallback = null
                runCatching { failureClient?.disconnect() }
                val groups = synchronized(resourceLock) { groupClient.also { groupClient = null } }
                runCatching { groups?.disconnect() }
                val selector = synchronized(resourceLock) { selectorClient.also { selectorClient = null } }
                runCatching { selector?.disconnect() }
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
        const val MAX_FAILURE_LOG_BATCH_LINES = 32
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
    }
}

private class SpeedMonitorClientHandler(
    private val controller: VpnController,
    private val generation: Long,
    private val onSample: (downlinkTotal: Long, connections: Int) -> Unit,
) : BaseClientHandler() {
    override fun writeStatus(message: StatusMessage) {
        if (generation != controller.currentGeneration() || !message.trafficAvailable) return
        // connectionsIn — трекеры traffic manager-а: каждое маршрутизированное
        // соединение; connectionsOut — только соединения route ConnectionManager.
        onSample(message.downlinkTotal, maxOf(message.connectionsIn, message.connectionsOut))
    }
}

private class DiagnosticLogClientHandler(
    private val controller: VpnController,
    private val generation: Long,
) : BaseClientHandler() {
    override fun disconnected(message: String) {
        controller.publishDiagnosticLogStream(generation, false)
    }

    override fun clearLogs() {
        controller.clearCoreDiagnosticLogs(generation)
    }

    override fun writeLogs(messageList: LogIterator) {
        val collector = CoreDiagnosticBatchCollector()
        while (messageList.hasNext()) {
            val entry = messageList.next()
            collector.add(entry.level, entry.message)
        }
        val batch = collector.result()
        controller.publishCoreDiagnosticLogs(generation, batch.entries, batch.droppedLines)
    }
}

/** Always-on bounded detector; raw messages are classified in memory only. */
internal class RuntimeErrorLogClientHandler(
    private val generation: Long,
    private val onLogs: (Long, List<String>) -> Unit,
    private val onEntry: (Int, String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String) -> Unit,
) : BaseClientHandler() {
    override fun connected() = onConnected()

    override fun disconnected(message: String) {
        onDisconnected(message)
    }

    override fun writeLogs(messageList: LogIterator) {
        val messages = ArrayList<String>(CoreSession.MAX_FAILURE_LOG_BATCH_LINES)
        while (messageList.hasNext()) {
            val entry = messageList.next()
            onEntry(entry.level, entry.message)
            messages += entry.message
            if (messages.size == CoreSession.MAX_FAILURE_LOG_BATCH_LINES) {
                onLogs(generation, messages.toList())
                messages.clear()
            }
        }
        if (messages.isNotEmpty()) onLogs(generation, messages)
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
