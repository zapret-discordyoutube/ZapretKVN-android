package io.github.zapretkvn.runtimelog

/**
 * Узнаёт «мёртвый» кеш-кластер YouTube: плеер раз за разом открывает соединения к одному и тому же кластеру
 * googlevideo, отправляет запрос и не получает ничего.
 *
 * Поле: архив 0.5.43 (2026-11-13). Кластер `sn-8ph2xajvh` за пять дней дал 146 потоков, из них 137 без единого
 * байта ответа, и ни одного здорового, на любом сервере. Сброс таких потоков (StalledFlowPolicy) только торопит
 * плеер повторить запрос к тому же кластеру: видео стоит в «буферизации» минутами. Детектор голодания
 * (SlowVideoDetector) молчал, потому что считает только потоки, уже получившие 16-256 КБ. Помогает смена сервера:
 * YouTube выбирает кластер по адресу, с которого пришёл запрос плеера, и другой сервер получает другой кластер.
 *
 * Мёртвым кластер считается, когда за [windowMillis] закрыто не меньше [minDeadFlows] разных потоков к нему, у
 * каждого: TCP, отправлено что-то (запрос ушёл), получено не больше [maxDownlinkBytes], прожил не меньше
 * [minLivedMillis] (мгновенный сброс соединения не считается: это отказ, а не молчание узла). Здоровый поток этого
 * кластера (получил от [healthyDownlinkBytes]) снимает приговор и обнуляет счёт.
 *
 * Кластер активен, пока после последнего «мёртвого» потока прошло не больше [windowMillis]: если плеер перестал
 * повторять запросы, вердикт гаснет сам и не толкает на смену сервера задним числом.
 *
 * Чистая логика без часов и ядра: вызывающий даёт время и потоки (с уже подставленными именами).
 */
class DeadVideoClusterTracker(
    private val minDeadFlows: Int = 3,
    private val windowMillis: Long = 90_000,
    private val maxDownlinkBytes: Long = 4_096,
    private val minLivedMillis: Long = 5_000,
    /** Поток без единого байта ответа считается молчаливым раньше: он сбрасывается через 3 с ([StalledFlowPolicy]). */
    private val silentMinLivedMillis: Long = 2_500,
    private val healthyDownlinkBytes: Long = 64L * 1024,
    private val maxClusters: Int = 64,
) {
    sealed interface Event {
        val cluster: String

        /** Кластер признан мёртвым: [flows] потоков без ответа за [spanMillis] на сервере [outbound]. */
        data class Dead(override val cluster: String, val flows: Int, val spanMillis: Long, val outbound: String) : Event

        /** Здоровый поток кластера снял приговор; мёртвым он считался [deadForMillis]. */
        data class Recovered(override val cluster: String, val deadForMillis: Long) : Event
    }

    private class State {
        val times = ArrayDeque<Long>()
        val ids = HashSet<String>()
        var dead = false
        var deadSince = 0L
        var lastDeadFlowAt = 0L
        var outbound = ""
    }

    private val clusters = LinkedHashMap<String, State>()

    fun reset() {
        clusters.clear()
    }

    /** Кластеры, признанные мёртвыми и ещё «активные» (плеер повторяет запросы) на момент [nowMillis]. */
    fun activeDeadClusters(nowMillis: Long): List<String> =
        clusters.filter { (_, state) -> state.dead && nowMillis - state.lastDeadFlowAt <= windowMillis }.keys.toList()

    /** Закрытые потоки снимка: считает молчаливые и здоровые. Возвращает события для журнала. */
    fun onClosed(nowMillis: Long, closed: List<FlowSnap>): List<Event> {
        val events = ArrayList<Event>()
        // Старые закрытые потоки первыми: порядок событий совпадает с порядком в жизни.
        for (flow in closed.sortedBy { it.closedAtEpochMillis }) {
            val cluster = clusterOf(flow.domain) ?: continue
            if (!flow.network.equals("tcp", ignoreCase = true)) continue
            if (flow.downlink >= healthyDownlinkBytes) {
                recover(nowMillis, cluster)?.let(events::add)
                continue
            }
            if (!isSilent(flow)) continue
            val state = clusters.getOrPut(cluster) { State() }
            if (state.ids.size > MAX_IDS) state.ids.clear()
            if (!state.ids.add(flow.id)) continue
            val at = if (flow.closedAtEpochMillis > 0) flow.closedAtEpochMillis else nowMillis
            state.times.addLast(at)
            state.lastDeadFlowAt = at
            state.outbound = flow.outbound
            prune(state, at)
            if (!state.dead && state.times.size >= minDeadFlows) {
                state.dead = true
                state.deadSince = at
                events += Event.Dead(cluster, state.times.size, at - state.times.first(), flow.outbound)
            }
        }
        trim()
        return events
    }

    /** Живые потоки: здоровый поток кластера тоже снимает приговор (закрытия можно ждать минуты). */
    fun onActive(nowMillis: Long, active: List<FlowSnap>): List<Event> {
        val events = ArrayList<Event>()
        for (flow in active) {
            val cluster = clusterOf(flow.domain) ?: continue
            if (flow.network.equals("tcp", ignoreCase = true) && flow.downlink >= healthyDownlinkBytes) {
                recover(nowMillis, cluster)?.let(events::add)
            }
        }
        return events
    }

    private fun isSilent(flow: FlowSnap): Boolean {
        val lived = if (flow.createdAtEpochMillis > 0 && flow.closedAtEpochMillis > 0) flow.closedAtEpochMillis - flow.createdAtEpochMillis else 0L
        return flow.uplink > 0 && flow.downlink <= maxDownlinkBytes &&
            lived >= (if (flow.downlink == 0L) silentMinLivedMillis else minLivedMillis)
    }

    private fun recover(nowMillis: Long, cluster: String): Event.Recovered? {
        val state = clusters[cluster] ?: return null
        val event = if (state.dead) Event.Recovered(cluster, (nowMillis - state.deadSince).coerceAtLeast(0)) else null
        clusters.remove(cluster)
        return event
    }

    private fun prune(state: State, nowMillis: Long) {
        while (state.times.isNotEmpty() && nowMillis - state.times.first() > windowMillis) state.times.removeFirst()
    }

    /** Память ограничена: самые давние кластеры забываются первыми. */
    private fun trim() {
        while (clusters.size > maxClusters) clusters.remove(clusters.keys.first())
    }

    companion object {
        private const val MAX_IDS = 512
        private val CLUSTER = Regex("""(?:---|\.)(sn-[a-z0-9]+)(?:-[a-z0-9]+)?\.googlevideo\.com$""")

        /**
         * Кластер из имени кеш-узла: `rr2---sn-8ph2xajvh-3a4l.googlevideo.com` даёт `sn-8ph2xajvh`.
         * Обе записи имени равноправны: плеер просит `rr2---sn-…`, а поток получает имя по ответу DNS, то есть
         * `rr2.sn-…` (с точкой) через CNAME. Поле: архив 0.5.47, детектор молчал, пока знал только форму с `---`.
         * Имена без метки `sn-` (`redirector.googlevideo.com` и другие) кластеру не принадлежат: `null`.
         */
        fun clusterOf(domain: String): String? =
            CLUSTER.find(domain.trimEnd('.').lowercase())?.groupValues?.get(1)
    }
}
