package io.github.zapretkvn.runtimelog

/**
 * Какие потоки считать зависшими и сбросить сразу (решение владельца 2026-11-08: «обрывать зависшие соединения
 * YouTube, чтобы плеер не ждал свой долгий таймаут»).
 *
 * Поток сбрасывается, только если выполнено всё: TCP, имя из [hostSuffixes] (по умолчанию googlevideo.com),
 * возраст не меньше [minAgeMillis], получено не больше [maxDownlinkBytes] (то есть за это время данных нет,
 * только рукопожатие), и что-то отправлено (запрос ушёл). Остальные потоки и адреса без имени не трогаются.
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
) {
    private var resetInSession = 0

    fun pick(nowMillis: Long, active: List<FlowSnap>): List<FlowSnap> {
        val room = maxPerSession - resetInSession
        if (room <= 0) return emptyList()
        val picked = active.asSequence()
            .filter { isStalled(nowMillis, it) }
            .sortedBy { it.createdAtEpochMillis }
            .take(minOf(maxPerSnapshot, room))
            .toList()
        resetInSession += picked.size
        return picked
    }

    /** Новая сессия VPN: счётчик предохранителя с нуля. */
    fun reset() {
        resetInSession = 0
    }

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
