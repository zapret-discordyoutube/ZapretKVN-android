package io.github.zapretkvn.runtimelog

import java.util.Locale

/** Один поток через прокси из снимка ядра (`ProxyFlows`): итоги за всё время жизни потока. */
data class FlowSnap(
    val id: String,
    val network: String,
    val domain: String,
    val destination: String,
    val packageName: String,
    val outbound: String,
    val createdAtEpochMillis: Long,
    val closedAtEpochMillis: Long,
    val uplink: Long,
    val downlink: Long,
)

/** Строка журнала, которую надо записать (уровень, текст). */
data class FlowLine(val level: Int, val text: String)

/**
 * Превращает снимки потоков ядра в компактные строки журнала. Нужен, чтобы видеть то, чего нет в счётчиках
 * всего трафика: какой поток сколько получил и сколько прожил.
 *
 * Снимок берётся прямым вызовом ядра в момент отсчёта трафика, без подписки на соединения (инвариант I6-05):
 * приращения считает трекер, сравнивая итоги потока с прошлым снимком.
 *
 * Строки:
 * - закрытый поток: имя, сеть, приложение, время жизни, байты вверх и вниз (итоги из ядра);
 * - на каждый снимок: сколько потоков живо, сколько пришло за окно и топ потоков по входящему;
 * - потоки старше [waitingAfterMillis], которые за окно отправили данные и ничего не получили («ждут ответ»).
 *   Это описание, а не приговор: молчащий поток может ждать, пока плеер доберёт буфер.
 */
class FlowTracker(
    private val topN: Int = 5,
    private val waitingAfterMillis: Long = 10_000,
    private val maxTracked: Int = 4_096,
) {
    private class State(var snap: FlowSnap) {
        var uplink = 0L
        var downlink = 0L
        var windowUp = 0L
        var windowDown = 0L
        val name: String get() = snap.domain.ifBlank { snap.destination }.ifBlank { "?" }
    }

    private val flows = LinkedHashMap<String, State>()
    private var lastSnapshotAt = -1L

    fun reset() {
        flows.clear()
        lastSnapshotAt = -1L
    }

    /** [closed] в порядке «самые новые первыми», как отдаёт ядро; [active] любой. */
    fun onSnapshot(nowMillis: Long, active: List<FlowSnap>, closed: List<FlowSnap>, emitWindow: Boolean = true): List<FlowLine> {
        val lines = ArrayList<FlowLine>()
        val activeIds = HashSet<String>(active.size * 2)
        for (snap in active) {
            activeIds += snap.id
            val state = flows.getOrPut(snap.id) {
                if (flows.size >= maxTracked) flows.remove(flows.keys.first())
                State(snap)
            }
            state.snap = snap
            val up = (snap.uplink - state.uplink).coerceAtLeast(0)
            val down = (snap.downlink - state.downlink).coerceAtLeast(0)
            state.uplink = maxOf(state.uplink, snap.uplink)
            state.downlink = maxOf(state.downlink, snap.downlink)
            state.windowUp += up
            state.windowDown += down
        }
        for (snap in closed.asReversed()) {
            val state = flows.remove(snap.id)
            val up = maxOf(snap.uplink, state?.uplink ?: 0)
            val down = maxOf(snap.downlink, state?.downlink ?: 0)
            val name = snap.domain.ifBlank { snap.destination }.ifBlank { state?.name ?: "?" }
            val livedMillis = if (snap.createdAtEpochMillis > 0 && snap.closedAtEpochMillis > 0) {
                (snap.closedAtEpochMillis - snap.createdAtEpochMillis).coerceAtLeast(0)
            } else {
                -1L
            }
            // Средняя скорость вниз за всю жизнь потока: по ней сравниваются кеш-узлы YouTube и серверы.
            val average = if (livedMillis >= 1_000 && down > 0) {
                ", ср. вниз ${formatBytes(down * 1_000 / livedMillis)}/с" +
                    (SpeedClass.ofFlow(down, livedMillis)?.let { " (${it.label})" } ?: "")
            } else {
                ""
            }
            lines += FlowLine(
                4,
                "закрыт ${snap.network.ifBlank { "?" }} $name (${snap.packageName.ifBlank { "?" }}, ${snap.outbound.ifBlank { "?" }}): " +
                    "жил ${if (livedMillis >= 0) formatDuration(livedMillis) else "?"}, вверх ${formatBytes(up)}, вниз ${formatBytes(down)}$average",
            )
        }
        // Потоки, которых нет ни среди живых, ни среди закрытых (запись закрытия вытеснена), забываем.
        flows.keys.retainAll(activeIds)
        // При частой выборке (видео идёт) строка окна пишется не на каждый снимок: счётчики окна копятся дальше.
        if (emitWindow) {
            val windowMillis = if (lastSnapshotAt > 0) nowMillis - lastSnapshotAt else 0L
            lastSnapshotAt = nowMillis
            lines += windowLines(nowMillis, windowMillis)
        }
        return lines
    }

    private fun windowLines(nowMillis: Long, windowMillis: Long): List<FlowLine> {
        val result = ArrayList<FlowLine>(2)
        val totalDown = flows.values.sumOf { it.windowDown }
        val totalUp = flows.values.sumOf { it.windowUp }
        val top = flows.values.filter { it.windowDown > 0 }.sortedByDescending { it.windowDown }.take(topN)
        result += FlowLine(
            4,
            "потоки: живых ${flows.size}, за окно вверх ${formatBytes(totalUp)}, вниз ${formatBytes(totalDown)}" +
                if (top.isEmpty()) "" else "; топ по входящему: " + top.joinToString(", ") {
                    val grade = SpeedClass.ofFlow(it.windowDown, windowMillis)?.let { speed ->
                        ", ${formatBytes(it.windowDown * 1_000 / windowMillis)}/с ${speed.label}"
                    }.orEmpty()
                    "${it.name} ${it.snap.network} ↓${formatBytes(it.windowDown)} (${ageOf(it, nowMillis)}$grade)"
                },
        )
        val waiting = flows.values.filter {
            it.windowUp > 0 && it.windowDown == 0L && it.snap.createdAtEpochMillis > 0 &&
                nowMillis - it.snap.createdAtEpochMillis >= waitingAfterMillis
        }.sortedByDescending { it.windowUp }
        if (waiting.isNotEmpty()) {
            result += FlowLine(
                3,
                "ждут ответ (${waiting.size}): " + waiting.take(topN).joinToString(", ") {
                    "${it.name} ${it.snap.network} жив ${formatDuration(nowMillis - it.snap.createdAtEpochMillis)}, " +
                        "вверх ${formatBytes(it.windowUp)}, всего вниз ${formatBytes(it.downlink)}"
                },
            )
        }
        flows.values.forEach {
            it.windowUp = 0
            it.windowDown = 0
        }
        return result
    }

    private fun ageOf(state: State, nowMillis: Long): String =
        if (state.snap.createdAtEpochMillis > 0) "жив " + formatDuration(nowMillis - state.snap.createdAtEpochMillis) else "?"

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes < 1_024 -> "${bytes}Б"
            bytes < 1_024 * 1_024 -> String.format(Locale.ROOT, "%.0fКБ", bytes / 1_024.0)
            else -> String.format(Locale.ROOT, "%.1fМБ", bytes / (1_024.0 * 1_024.0))
        }

        fun formatDuration(millis: Long): String = when {
            millis < 1_000 -> "${millis}мс"
            millis < 60_000 -> String.format(Locale.ROOT, "%.1fс", millis / 1_000.0)
            else -> String.format(Locale.ROOT, "%dм%02dс", millis / 60_000, (millis / 1_000) % 60)
        }
    }
}
