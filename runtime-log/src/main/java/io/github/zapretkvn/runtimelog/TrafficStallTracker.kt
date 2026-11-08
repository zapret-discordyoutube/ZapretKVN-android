package io.github.zapretkvn.runtimelog

/**
 * Находит «подвисание» по отсчётам счётчиков: соединения есть, исходящий трафик идёт, входящего
 * почти нет несколько окон подряд. Это ровно то, что пользователь видит как замерзшее видео, и
 * в журнале нужны точные границы: когда началось, сколько длилось, вернулся ли поток.
 *
 * Чистая логика без часов и журнала: вызывающий передаёт время и счётчики, получает шаг.
 */
class TrafficStallTracker(
    private val stallDownBytes: Long = 2_048,
    private val resumeDownBytes: Long = 8_192,
    private val windowsToStall: Int = 2,
) {
    data class Step(
        val windowMillis: Long,
        val uplinkBytes: Long,
        val downlinkBytes: Long,
        val connections: Int,
        val event: Event?,
    )

    sealed interface Event {
        /** Подвисание подтверждено; [sinceEpochMillis] — начало первого плохого окна. */
        data class Started(val sinceEpochMillis: Long) : Event

        /** Подвисание кончилось; [recovered] — поток вернулся (иначе трафик просто затих). */
        data class Ended(val durationMillis: Long, val recovered: Boolean) : Event
    }

    private var hasBaseline = false
    private var lastEpochMillis = 0L
    private var lastUplink = 0L
    private var lastDownlink = 0L
    private var badWindows = 0
    private var badSince = 0L
    private var stalledSince: Long? = null

    /** Первый отсчёт только запоминается; счётчик, пошедший назад (перезапуск сессии), начинает с нуля. */
    fun onSample(epochMillis: Long, uplinkTotal: Long, downlinkTotal: Long, connections: Int): Step? {
        if (!hasBaseline || uplinkTotal < lastUplink || downlinkTotal < lastDownlink) {
            rebase(epochMillis, uplinkTotal, downlinkTotal)
            return null
        }
        val window = epochMillis - lastEpochMillis
        val up = uplinkTotal - lastUplink
        val down = downlinkTotal - lastDownlink
        val windowStart = lastEpochMillis
        lastEpochMillis = epochMillis
        lastUplink = uplinkTotal
        lastDownlink = downlinkTotal

        val bad = connections > 0 && up > 0 && down < stallDownBytes
        var event: Event? = null
        val since = stalledSince
        if (bad) {
            if (badWindows == 0) badSince = windowStart
            badWindows++
            if (since == null && badWindows >= windowsToStall) {
                stalledSince = badSince
                event = Event.Started(badSince)
            }
        } else {
            badWindows = 0
            if (since != null && (down >= resumeDownBytes || up == 0L)) {
                stalledSince = null
                event = Event.Ended(epochMillis - since, recovered = down >= resumeDownBytes)
            }
        }
        return Step(window, up, down, connections, event)
    }

    /** Новая сессия: счётчики пойдут с нуля, открытое подвисание считается закрытым без события. */
    fun reset() {
        hasBaseline = false
        badWindows = 0
        stalledSince = null
    }

    private fun rebase(epochMillis: Long, uplinkTotal: Long, downlinkTotal: Long) {
        hasBaseline = true
        lastEpochMillis = epochMillis
        lastUplink = uplinkTotal
        lastDownlink = downlinkTotal
        badWindows = 0
        stalledSince = null
    }
}
