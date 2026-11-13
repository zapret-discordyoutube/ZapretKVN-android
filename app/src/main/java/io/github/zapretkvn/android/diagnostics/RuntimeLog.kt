package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.runtimelog.CoreLogLayers
import io.github.zapretkvn.runtimelog.DnsAddressBook
import io.github.zapretkvn.runtimelog.FlowSnap
import io.github.zapretkvn.runtimelog.FlowTracker
import io.github.zapretkvn.runtimelog.DeadVideoClusterTracker
import io.github.zapretkvn.runtimelog.StalledFlowPolicy
import io.github.zapretkvn.runtimelog.LogLayer
import io.github.zapretkvn.runtimelog.LogStoreInfo
import io.github.zapretkvn.runtimelog.RuntimeLogStore
import io.github.zapretkvn.runtimelog.SlowVideoDetector
import io.github.zapretkvn.runtimelog.SpeedHistory
import io.github.zapretkvn.runtimelog.TunnelGrade
import io.github.zapretkvn.runtimelog.TrafficStallTracker
import io.github.zapretkvn.runtimelog.ZipExtra
import java.io.File
import java.io.OutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Подробный журнал работы на диске (модуль `:runtime-log`), точка входа для приложения.
 *
 * Решения владельца 2026-11-08: ничего не выбрасывать, пока файл не вырос до 100 МБ; домены и
 * IP-адреса сохраняются как есть; писать на диск быстро и компактно; больше строк по слоям
 * (приложение, сеть, DNS, маршрутизация, TUN, outbound, трафик), чтобы быстрее находить ошибку
 * и место подвисания. Секреты (ссылки, ключи, токены, UUID) маскируются всегда: адреса сервера
 * и домены при этом остаются, в отличие от обычного JSON-отчёта диагностики.
 *
 * Все методы безопасны до [install] и после сбоя записи: журнал не должен мешать работе VPN.
 */
object RuntimeLog {
    @Volatile
    private var store: RuntimeLogStore? = null

    private val stall = TrafficStallTracker()
    private val flowTracker = FlowTracker()
    private val addressBook = DnsAddressBook()
    private val videoDetector = SlowVideoDetector()

    /** Мёртвые кеш-кластеры YouTube (поток к ним получает 0 байт): повод сменить сервер, см. [DeadVideoClusterTracker]. */
    private val deadClusters = DeadVideoClusterTracker()
    private var lastWindowLineAt = 0L
    private var lastStarving = false
    @Volatile
    private var history: SpeedHistory? = null
    private val stalledPolicy = StalledFlowPolicy(knownSlow = { server, domain -> history?.isKnownSlow(server, domain) == true })

    @Volatile
    private var resetStalledEnabled = true
    private var closedCursor = 0L
    private val stallLock = Any()
    private var lastNetworkLine: String? = null

    /** Каталог внутри `noBackupFilesDir`: журнал не должен уезжать в резервные копии Android. */
    fun install(directory: File, startupLine: String, historyFile: File? = null) {
        synchronized(this) {
            if (store != null) return
            history = SpeedHistory(historyFile)
            store = RuntimeLogStore(
                directory = directory,
                transform = { record -> record.copy(text = SecretRedactor.redactInline(record.text)) },
            )
        }
        app(4, startupLine)
        logHistorySummary()
    }

    /** Сводка истории скоростей в журнал: по серверу итог по YouTube, медленные и быстрые узлы кеша. */
    private fun logHistorySummary() {
        val target = store ?: return
        history?.summary()?.forEach { target.log(LogLayer.Traffic, 4, it) }
    }

    fun historySummary(): List<String> = history?.summary().orEmpty()

    fun info(): LogStoreInfo? = store?.info()

    /** Строка ядра как есть: слой определяется по телу строки, уровень приходит от ядра. */
    fun core(level: Int, line: String) {
        // Ответы DNS по googlevideo запоминаются: поток к такому адресу без имени получит имя из книги.
        addressBook.observe(line)
        store?.log(CoreLogLayers.classify(line), level, line)
    }

    fun app(level: Int, text: String) {
        store?.log(LogLayer.App, level, text)
    }

    fun log(layer: LogLayer, level: Int, text: String) {
        store?.log(layer, level, text)
    }

    /** Состояние сети Android; повтор той же строки подряд не пишется. */
    fun network(text: String) {
        synchronized(stallLock) {
            if (text == lastNetworkLine) return
            lastNetworkLine = text
        }
        store?.log(LogLayer.Network, 4, text)
    }

    /** Сколько потоков просить у ядра за один снимок. */
    const val FLOW_LIMIT = 300

    /** Курсор закрытых потоков: ядро отдаёт только закрытые позже этой метки (мс). */
    fun flowCursor(): Long = synchronized(stallLock) { closedCursor }

    /**
     * Снимок потоков через прокси из ядра (`CommandServer.proxyFlows`, JSON): закрытые потоки с байтами и временем
     * жизни, топ потоков по входящему, потоки, которые «ждут ответ». `null` и пустая строка (ядро не запущено)
     * пропускаются; битый JSON не должен ронять отсчёт трафика.
     */
    /** Итог одной выборки потоков: что закрыть как зависшее и вердикт по видео (для переключения сервера). */
    class FlowSample(val stalledIds: List<String>, val verdict: SlowVideoDetector.Verdict)

    private val emptySample = FlowSample(emptyList(), SlowVideoDetector.Verdict.Idle)

    fun flows(json: String?): FlowSample {
        val target = store ?: return emptySample
        if (json.isNullOrEmpty()) return emptySample
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptySample
        // Имя потока без имени берётся из ответов DNS (адрес -> googlevideo): иначе политика и история его не узнают.
        val active = root["active"].snaps().map(addressBook::enrich)
        val closed = root["closed"].snaps().map(addressBook::enrich)
        val now = System.currentTimeMillis()
        closed.forEach { flow ->
            val lived = if (flow.createdAtEpochMillis > 0 && flow.closedAtEpochMillis > 0) flow.closedAtEpochMillis - flow.createdAtEpochMillis else 0L
            history?.recordFlow(flow.outbound, flow.domain, flow.destination, flow.downlink, lived)
        }
        val (lines, stalled, sampled) = synchronized(stallLock) {
            closed.maxOfOrNull { it.closedAtEpochMillis }?.let { if (it > closedCursor) closedCursor = it }
            // Выборка при видео идёт каждые 1,5 с, а строку окна достаточно писать раз в ~5 с.
            val emitWindow = now - lastWindowLineAt >= WINDOW_LINE_MILLIS
            if (emitWindow) lastWindowLineAt = now
            Triple(
                flowTracker.onSnapshot(now, active, closed, emitWindow),
                if (resetStalledEnabled) stalledPolicy.pick(now, active) else emptyList(),
                // Вердикт по голоданию плюс мёртвые кластеры: у потоков к мёртвому кластеру нет ни байта,
                // голодание их не видит. События (признан мёртвым, ожил) идут в журнал ниже.
                run {
                    val events = deadClusters.onClosed(now, closed) + deadClusters.onActive(now, active)
                    videoDetector.onSnapshot(now, active).copy(deadClusters = deadClusters.activeDeadClusters(now)) to events
                },
            )
        }
        val (verdict, clusterEvents) = sampled
        logVideoVerdict(target, verdict)
        logClusterEvents(target, clusterEvents)
        lines.forEach { target.log(LogLayer.Traffic, it.level, it.text) }
        stalled.forEach { flow ->
            target.log(
                LogLayer.Traffic,
                3,
                "сброс зависшего потока ${flow.domain} tcp: жил ${FlowTracker.formatDuration(now - flow.createdAtEpochMillis)}, " +
                    "вверх ${FlowTracker.formatBytes(flow.uplink)}, вниз ${FlowTracker.formatBytes(flow.downlink)}; плеер откроет новое соединение",
            )
        }
        stalled.forEach { history?.recordReset(it.outbound, it.domain, it.destination) }
        history?.flushIfDirty()
        return FlowSample(stalled.map { it.id }, verdict)
    }

    private const val WINDOW_LINE_MILLIS = 4_000L

    /** Кластеры YouTube: когда кластер признан мёртвым и когда ожил (с числом потоков и сервером). */
    private fun logClusterEvents(target: RuntimeLogStore, events: List<DeadVideoClusterTracker.Event>) {
        for (event in events) {
            when (event) {
                is DeadVideoClusterTracker.Event.Dead -> target.log(
                    LogLayer.Traffic,
                    3,
                    "МЁРТВЫЙ КЛАСТЕР YouTube ${event.cluster}: ${event.flows} потока(ов) без единого байта ответа за " +
                        "${FlowTracker.formatDuration(event.spanMillis)} на ${event.outbound}; сброс потоков не помогает, " +
                        "плеер получает тот же кластер (другой сервер получает другой)",
                )
                is DeadVideoClusterTracker.Event.Recovered -> target.log(
                    LogLayer.Traffic,
                    4,
                    "кластер YouTube ${event.cluster} снова отвечает (был мёртв ${FlowTracker.formatDuration(event.deadForMillis)})",
                )
            }
        }
    }

    /** Границы голодания видео в журнал: когда началось и когда прошло. */
    private fun logVideoVerdict(target: RuntimeLogStore, verdict: SlowVideoDetector.Verdict) {
        val starving = verdict.starving
        val wasStarving = synchronized(stallLock) { lastStarving.also { lastStarving = starving } }
        if (starving && !wasStarving) {
            target.log(
                LogLayer.Traffic,
                3,
                "ВИДЕО ГОЛОДАЕТ: видеопотоков ${verdict.videoFlows}, медленных ${verdict.slowFlows.size}, " +
                    "суммарно ${FlowTracker.formatBytes(verdict.videoBytesPerSecond)}/с",
            )
        } else if (!starving && wasStarving) {
            target.log(LogLayer.Traffic, 3, "видео снова идёт (${if (verdict.healthy) "здорово" else "не голодает"})")
        }
    }

    /** Идёт ли видео googlevideo: тогда ядро опрашивается чаще. */
    fun videoActive(): Boolean = synchronized(stallLock) { videoDetector.videoActive }

    /** Идентификаторы видеопотоков последнего снимка (закрываются после смены сервера). */
    fun videoFlowIds(): List<String> = synchronized(stallLock) { videoDetector.videoFlowIds }

    /** Забыть накопленные отсчёты детектора: после смены сервера судить надо только по новым потокам. */
    fun resetVideoDetector() {
        synchronized(stallLock) {
            videoDetector.reset()
            deadClusters.reset()
            lastStarving = false
        }
    }

    /** Средняя скорость сервера по видео YouTube из истории; `null`, если оценок мало. */
    fun youtubeAverage(server: String): Long? = history?.youtubeAverage(server)

    /** Включает или выключает сброс зависших потоков YouTube; вызывается при старте сессии из настроек. */
    fun configure(resetStalledFlows: Boolean) {
        resetStalledEnabled = resetStalledFlows
    }

    /** Статистика QUIC-соединения туннеля из ядра (`CommandServer.tunnelStats`): по строке на сервер Hysteria2. */
    fun tunnelStats(text: String?) {
        val target = store ?: return
        if (text.isNullOrEmpty()) return
        text.lineSequence().filter(String::isNotBlank).forEach { line ->
            // К каждой строке добавляется оценка канала: что у туннеля нормально, а что плохо.
            target.log(LogLayer.Outbound, 4, TunnelGrade.of(line)?.let { "$line; канал: $it" } ?: line)
        }
    }

    private fun JsonElement?.snaps(): List<FlowSnap> =
        (this as? JsonArray).orEmpty().mapNotNull { element ->
            val flow = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (flow[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun number(key: String) = (flow[key] as? JsonPrimitive)?.longOrNull ?: 0L
            val id = text("id").ifEmpty { return@mapNotNull null }
            FlowSnap(
                id = id,
                network = text("net"),
                domain = text("domain"),
                destination = text("dest"),
                packageName = text("pkg"),
                outbound = text("out"),
                createdAtEpochMillis = number("created"),
                closedAtEpochMillis = number("closed"),
                uplink = number("up"),
                downlink = number("down"),
            )
        }

    /** Новая сессия VPN: счётчики идут с нуля, прежнее подвисание закрыто. */
    fun sessionStarted(generation: Long) {
        synchronized(stallLock) {
            stall.reset()
            flowTracker.reset()
            stalledPolicy.reset()
            videoDetector.reset()
            deadClusters.reset()
            lastStarving = false
            // Новая сессия начинает с чистого окна: первый снимок обязан дать строку окна.
            lastWindowLineAt = 0L
            closedCursor = 0L
        }
        logHistorySummary()
        store?.log(LogLayer.Traffic, 4, "сессия $generation: счётчики с нуля")
    }

    /**
     * Отсчёт трафика (раз в ~5 с): приращения за окно и границы подвисания. Подвисание считается по
     * счётчикам через прокси, если они есть: прямой трафик маскирует мёртвый туннель.
     */
    fun traffic(
        uplinkTotal: Long,
        downlinkTotal: Long,
        connections: Int,
        proxyUplinkTotal: Long?,
        proxyDownlinkTotal: Long?,
        proxyConnections: Int?,
    ) {
        val target = store ?: return
        val now = System.currentTimeMillis()
        val useProxy = proxyUplinkTotal != null && proxyDownlinkTotal != null
        val step = synchronized(stallLock) {
            stall.onSample(
                now,
                if (useProxy) proxyUplinkTotal!! else uplinkTotal,
                if (useProxy) proxyDownlinkTotal!! else downlinkTotal,
                if (useProxy) (proxyConnections ?: connections) else connections,
            )
        } ?: return
        target.log(
            LogLayer.Traffic,
            4,
            "за ${step.windowMillis}мс ${if (useProxy) "прокси" else "всего"}: вверх ${step.uplinkBytes}Б, вниз ${step.downlinkBytes}Б, " +
                "соединений ${step.connections}; всего вверх $uplinkTotal вниз $downlinkTotal соединений $connections",
        )
        when (val event = step.event) {
            is TrafficStallTracker.Event.Started -> target.log(
                LogLayer.Traffic,
                3,
                "НЕТ ВХОДЯЩЕГО: исходящий идёт, входящего меньше 8 КБ за окно с ${event.sinceEpochMillis}, соединений ${step.connections} (подвисание или пауза плеера)",
            )
            is TrafficStallTracker.Event.Ended -> target.log(
                LogLayer.Traffic,
                3,
                "НЕТ ВХОДЯЩЕГО закончилось через ${event.durationMillis}мс, " +
                    if (event.recovered) "поток вернулся" else "трафик затих",
            )
            null -> Unit
        }
    }

    /** Архив для выгрузки: сводка диагностики и все сегменты журнала. */
    fun writeZip(output: OutputStream, extraEntries: List<ZipExtra>) {
        history?.flushIfDirty(force = true)
        val withHistory = history?.render()?.let { extraEntries + ZipExtra("speed-history.txt", it.toByteArray(Charsets.UTF_8)) } ?: extraEntries
        val target = store
        if (target != null) {
            target.writeZip(output, withHistory)
        } else {
            // Журнал ещё не установлен (ранний вызов): архив всё равно получается, но без журнала.
            java.util.zip.ZipOutputStream(output).use { zip ->
                withHistory.forEach { extra ->
                    zip.putNextEntry(java.util.zip.ZipEntry(extra.name))
                    zip.write(extra.bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    fun clear(): Boolean = store?.clear() ?: false
}
