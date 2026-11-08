package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.runtimelog.CoreLogLayers
import io.github.zapretkvn.runtimelog.FlowSnap
import io.github.zapretkvn.runtimelog.FlowTracker
import io.github.zapretkvn.runtimelog.LogLayer
import io.github.zapretkvn.runtimelog.LogStoreInfo
import io.github.zapretkvn.runtimelog.RuntimeLogStore
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
    private var closedCursor = 0L
    private val stallLock = Any()
    private var lastNetworkLine: String? = null

    /** Каталог внутри `noBackupFilesDir`: журнал не должен уезжать в резервные копии Android. */
    fun install(directory: File, startupLine: String) {
        synchronized(this) {
            if (store != null) return
            store = RuntimeLogStore(
                directory = directory,
                transform = { record -> record.copy(text = SecretRedactor.redactInline(record.text)) },
            )
        }
        app(4, startupLine)
    }

    fun info(): LogStoreInfo? = store?.info()

    /** Строка ядра как есть: слой определяется по телу строки, уровень приходит от ядра. */
    fun core(level: Int, line: String) {
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
    fun flows(json: String?) {
        val target = store ?: return
        if (json.isNullOrEmpty()) return
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        val active = root["active"].snaps()
        val closed = root["closed"].snaps()
        val lines = synchronized(stallLock) {
            closed.maxOfOrNull { it.closedAtEpochMillis }?.let { if (it > closedCursor) closedCursor = it }
            flowTracker.onSnapshot(System.currentTimeMillis(), active, closed)
        }
        lines.forEach { target.log(LogLayer.Traffic, it.level, it.text) }
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
            closedCursor = 0L
        }
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
        val target = store
        if (target != null) {
            target.writeZip(output, extraEntries)
        } else {
            // Журнал ещё не установлен (ранний вызов): архив всё равно получается, но без журнала.
            java.util.zip.ZipOutputStream(output).use { zip ->
                extraEntries.forEach { extra ->
                    zip.putNextEntry(java.util.zip.ZipEntry(extra.name))
                    zip.write(extra.bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    fun clear(): Boolean = store?.clear() ?: false
}
