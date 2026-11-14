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
 * За один снимок сбрасывается не больше [maxPerSnapshot] потоков, самые старые первыми, а за скользящее окно
 * [capWindowMillis] не больше [maxPerWindow]: предохранитель, если признак ошибочно совпал со здоровыми потоками.
 * Раньше предохранитель был один на всю сессию (30): в поле (архив 0.5.43, 2026-11-13) он кончился через 40 минут,
 * и дальше зависшие потоки к мёртвому кластеру висели до долгого таймаута плеера (30 с). Окно возвращает сброс,
 * когда прежние сбросы вышли из окна, а ложное срабатывание по-прежнему ограничено числом в окне.
 */
class StalledFlowPolicy(
    private val hostSuffixes: List<String> = listOf("googlevideo.com"),
    private val minAgeMillis: Long = 8_000,
    /**
     * Поток, который отправил запрос и не получил от сервера НИ ОДНОГО байта (даже ServerHello): сбрасывается раньше.
     * Поле (архив 0.5.48 и замер узла 2026-11-14): кластер `sn-8ph2xajvh` принимает TCP и молчит, а здоровый поток
     * получает ServerHello за 0.1-0.3 с. Три секунды с запасом на плохую сеть; плеер получает отказ на 5 с раньше.
     */
    private val silentMinAgeMillis: Long = 3_000,
    private val maxDownlinkBytes: Long = 4_096,
    private val maxPerSnapshot: Int = 3,
    private val maxPerWindow: Int = 30,
    private val capWindowMillis: Long = 5 * 60_000,
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
    private val resetTimes = ArrayDeque<Long>()

    /** Итоги потока в прошлом снимке и число подряд «сочащихся» окон. */
    private class Seen(var uplink: Long, var downlink: Long, var trickleWindows: Int)

    private val seen = HashMap<String, Seen>()

    fun pick(nowMillis: Long, active: List<FlowSnap>): List<FlowSnap> {
        val trickling = trickleCandidates(nowMillis, active)
        while (resetTimes.isNotEmpty() && nowMillis - resetTimes.first() >= capWindowMillis) resetTimes.removeFirst()
        val room = maxPerWindow - resetTimes.size
        if (room <= 0) return emptyList()
        val picked = active.asSequence()
            .filter { it.id in trickling || isStalled(nowMillis, it) }
            .sortedBy { it.createdAtEpochMillis }
            .take(minOf(maxPerSnapshot, room))
            .toList()
        repeat(picked.size) { resetTimes.addLast(nowMillis) }
        return picked
    }

    /** Новая сессия VPN: счётчик предохранителя с нуля. */
    fun reset() {
        resetTimes.clear()
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
            nowMillis - flow.createdAtEpochMillis >= (if (flow.downlink == 0L) silentMinAgeMillis else minAgeMillis) &&
            flow.downlink <= maxDownlinkBytes &&
            flow.uplink > 0

    private fun matchesHost(domain: String): Boolean {
        val name = domain.trimEnd('.').lowercase()
        return name.isNotEmpty() && hostSuffixes.any { suffix -> name == suffix || name.endsWith(".$suffix") }
    }
}
