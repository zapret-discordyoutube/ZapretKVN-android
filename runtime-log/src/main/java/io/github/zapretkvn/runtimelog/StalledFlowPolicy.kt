package io.github.zapretkvn.runtimelog

/**
 * Какие потоки считать зависшими и сбросить сразу (решение владельца 2026-11-08: «обрывать зависшие соединения
 * YouTube, чтобы плеер не ждал свой долгий таймаут»).
 *
 * Поток сбрасывается, только если выполнено всё: TCP, имя из [hostSuffixes] (по умолчанию googlevideo.com),
 * возраст не меньше [minAgeMillis], получено не больше [maxDownlinkBytes] (то есть за это время данных нет,
 * только рукопожатие), и что-то отправлено (запрос ушёл). Остальные потоки и адреса без имени не трогаются.
 *
 * Второй признак, «сочится» (по данным диагностики 0.5.38, поток к кеш-узлу `74.125.162.225`): поток к тем же
 * именам живёт не меньше [trickleMinAgeMillis], получил меньше [trickleMaxTotalDownlinkBytes] и в двух снимках
 * подряд отправил не меньше [trickleMinWindowUplinkBytes] за окно, получив не больше, чем отправил. Здоровая
 * загрузка видео устроена наоборот (получает много, отправляет мало), поэтому «отправляет больше, чем получает»
 * на видео-узле значит, что узел не отдаёт данные, а плеер напрасно шлёт запросы. Пауза плеера (буфер полон) в
 * это не попадает: запросов в паузе нет.
 *
 * За один снимок сбрасывается не больше [maxPerSnapshot] потоков, самые старые первыми, а за сессию не больше
 * [maxPerSession]: предохранитель, если признак ошибочно совпал со здоровыми потоками.
 */
class StalledFlowPolicy(
    private val hostSuffixes: List<String> = listOf("googlevideo.com"),
    private val minAgeMillis: Long = 8_000,
    private val maxDownlinkBytes: Long = 4_096,
    private val maxPerSnapshot: Int = 3,
    private val maxPerSession: Int = 30,
    private val trickleMinAgeMillis: Long = 10_000,
    private val trickleMaxTotalDownlinkBytes: Long = 3L * 1024 * 1024,
    private val trickleMinWindowUplinkBytes: Long = 8_192,
    private val trickleWindowsRequired: Int = 2,
    /**
     * История: этот узел у этого сервера уже был медленным. Тогда «сочащийся» поток режется раньше: от
     * [knownSlowMinAgeMillis] и после одного окна (чем дольше работает приложение, тем быстрее оно уходит с плохих узлов).
     */
    private val knownSlow: (server: String, domain: String) -> Boolean = { _, _ -> false },
    private val knownSlowMinAgeMillis: Long = 6_000,
) {
    private var resetInSession = 0

    /** Итоги потока в прошлом снимке и число подряд «сочащихся» окон. */
    private class Seen(var uplink: Long, var downlink: Long, var trickleWindows: Int)

    private val seen = HashMap<String, Seen>()

    fun pick(nowMillis: Long, active: List<FlowSnap>): List<FlowSnap> {
        val trickling = trickleCandidates(nowMillis, active)
        val room = maxPerSession - resetInSession
        if (room <= 0) return emptyList()
        val picked = active.asSequence()
            .filter { it.id in trickling || isStalled(nowMillis, it) }
            .sortedBy { it.createdAtEpochMillis }
            .take(minOf(maxPerSnapshot, room))
            .toList()
        resetInSession += picked.size
        return picked
    }

    /** Новая сессия VPN: счётчик предохранителя с нуля. */
    fun reset() {
        resetInSession = 0
        seen.clear()
    }

    /** Обновляет память о потоках и возвращает идентификаторы потоков, «сочащихся» уже [trickleWindowsRequired] окон. */
    private fun trickleCandidates(nowMillis: Long, active: List<FlowSnap>): Set<String> {
        val result = HashSet<String>()
        val activeIds = HashSet<String>(active.size * 2)
        for (flow in active) {
            activeIds += flow.id
            val previous = seen[flow.id]
            val entry = previous ?: Seen(flow.uplink, flow.downlink, 0).also { seen[flow.id] = it }
            val known = knownSlow(flow.outbound, flow.domain)
            val minAge = if (known) knownSlowMinAgeMillis else trickleMinAgeMillis
            if (previous != null && isTrickleWindow(nowMillis, flow, flow.uplink - entry.uplink, flow.downlink - entry.downlink, minAge)) {
                entry.trickleWindows++
            } else if (previous != null) {
                entry.trickleWindows = 0
            }
            entry.uplink = flow.uplink
            entry.downlink = flow.downlink
            if (entry.trickleWindows >= (if (known) 1 else trickleWindowsRequired)) result += flow.id
        }
        seen.keys.retainAll(activeIds)
        return result
    }

    private fun isTrickleWindow(nowMillis: Long, flow: FlowSnap, windowUplink: Long, windowDownlink: Long, minAgeMillis: Long): Boolean =
        flow.network.equals("tcp", ignoreCase = true) &&
            matchesHost(flow.domain) &&
            flow.createdAtEpochMillis > 0 &&
            nowMillis - flow.createdAtEpochMillis >= minAgeMillis &&
            flow.downlink < trickleMaxTotalDownlinkBytes &&
            windowUplink >= trickleMinWindowUplinkBytes &&
            windowUplink >= windowDownlink

    private fun isStalled(nowMillis: Long, flow: FlowSnap): Boolean =
        flow.network.equals("tcp", ignoreCase = true) &&
            matchesHost(flow.domain) &&
            flow.createdAtEpochMillis > 0 &&
            nowMillis - flow.createdAtEpochMillis >= minAgeMillis &&
            flow.downlink <= maxDownlinkBytes &&
            flow.uplink > 0

    private fun matchesHost(domain: String): Boolean {
        val name = domain.trimEnd('.').lowercase()
        return name.isNotEmpty() && hostSuffixes.any { suffix -> name == suffix || name.endsWith(".$suffix") }
    }
}
