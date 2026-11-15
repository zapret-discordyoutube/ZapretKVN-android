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
 * Пауза после видео (Android 0.6.24, поле 2026-11-15 21:06 UTC, Rusina): кластер `sn-ajcvh5` только что отдал 78 МБ, два
 * длинных потока закрылись, а плеер с полным буфером открывал короткие потоки, получавшие по 2-3 КБ. Сброс зависших
 * потоков закрывал их через 9 с, третий такой поток дал вердикт «мёртвый кластер», и слот YouTube ушёл с рабочего
 * закреплённого сервера, хотя видео на экране шло. Поэтому кластер, который за последние [healthyGraceMillis] отдал
 * здоровый поток, тихими потоками не судится: событие [Event.Excused] пишется один раз на такую паузу. Живой поток
 * считается свежим доказательством (и снимает приговор), только пока его входящий счётчик растёт: старый подвисший поток
 * не продлевает паузу и не снимает приговор.
 * `healthyGraceMillis = 0` выключает правило.
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
    /** Сколько после здорового потока кластера его тихие потоки не считаются (плеер с полным буфером); 0 выключает. */
    private val healthyGraceMillis: Long = 90_000,
) {
    sealed interface Event {
        val cluster: String

        /** Кластер признан мёртвым: [flows] потоков без ответа за [spanMillis] на сервере [outbound]. */
        data class Dead(override val cluster: String, val flows: Int, val spanMillis: Long, val outbound: String) : Event

        /** Здоровый поток кластера снял приговор; мёртвым он считался [deadForMillis]. */
        data class Recovered(override val cluster: String, val deadForMillis: Long) : Event

        /** Тихий поток кластера пришёл через [sinceHealthyMillis] после здорового и не засчитан (пауза плеера). */
        data class Excused(override val cluster: String, val sinceHealthyMillis: Long, val outbound: String) : Event
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

    /** Когда кластер последний раз отдал здоровый поток. */
    private val lastHealthyAt = LinkedHashMap<String, Long>()

    /** Для какого момента здоровья пауза уже записана в журнал (одно событие [Event.Excused] на паузу). */
    private val excusedFor = HashMap<String, Long>()

    /** Входящий счётчик живых видеопотоков на прошлом снимке: растёт ли поток. */
    private val activeDownlink = HashMap<String, Long>()

    fun reset() {
        clusters.clear()
        lastHealthyAt.clear()
        excusedFor.clear()
        activeDownlink.clear()
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
            val at = if (flow.closedAtEpochMillis > 0) flow.closedAtEpochMillis else nowMillis
            if (flow.downlink >= healthyDownlinkBytes) {
                recover(nowMillis, cluster)?.let(events::add)
                noteHealthy(cluster, at)
                continue
            }
            if (!isSilent(flow)) continue
            excuse(cluster, at, flow.outbound)?.let {
                events += it
                continue
            }
            if (lastHealthyAt[cluster]?.let { at - it <= healthyGraceMillis } == true) continue
            val state = clusters.getOrPut(cluster) { State() }
            if (state.ids.size > MAX_IDS) state.ids.clear()
            if (!state.ids.add(flow.id)) continue
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
        val seen = HashMap<String, Long>()
        for (flow in active) {
            val cluster = clusterOf(flow.domain) ?: continue
            if (!flow.network.equals("tcp", ignoreCase = true)) continue
            seen[flow.id] = flow.downlink
            // Доказательство здоровья только от растущего потока: подвисший старый поток не снимает приговор и не
            // продлевает паузу после видео.
            if (flow.downlink >= healthyDownlinkBytes && flow.downlink - (activeDownlink[flow.id] ?: 0L) >= healthyDownlinkBytes) {
                recover(nowMillis, cluster)?.let(events::add)
                noteHealthy(cluster, nowMillis)
            }
        }
        activeDownlink.clear()
        activeDownlink.putAll(seen)
        return events
    }

    private fun noteHealthy(cluster: String, at: Long) {
        if (healthyGraceMillis <= 0) return
        if ((lastHealthyAt[cluster] ?: Long.MIN_VALUE) < at) {
            lastHealthyAt.remove(cluster)
            lastHealthyAt[cluster] = at
        }
    }

    /** Тихий поток в паузе после здорового: событие один раз на паузу, иначе `null` (вызывающий решает, считать ли). */
    private fun excuse(cluster: String, at: Long, outbound: String): Event.Excused? {
        val healthyAt = lastHealthyAt[cluster] ?: return null
        val since = at - healthyAt
        if (since > healthyGraceMillis || excusedFor[cluster] == healthyAt) return null
        excusedFor[cluster] = healthyAt
        return Event.Excused(cluster, since.coerceAtLeast(0), outbound)
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
        while (lastHealthyAt.size > maxClusters) {
            val oldest = lastHealthyAt.keys.first()
            lastHealthyAt.remove(oldest)
            excusedFor.remove(oldest)
        }
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
