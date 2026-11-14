package io.github.zapretkvn.runtimelog

/**
 * Узнаёт «голодающий» видеопоток YouTube по цифрам потоков, быстро (владелец 2026-11-08: «медленный видеопоток
 * можно узнавать сразу, ещё быстрее» и «перенаправлять на более быстрый сервер»).
 *
 * Признак взят из полевых архивов (0.5.38 и 0.5.39). Здоровый поток googlevideo за первые 3 секунды берёт
 * 550-600 КБ. Поток через плохой узел кеша живёт 25-50 секунд на 4-7 КБ/с, уже получив 20-100 КБ, и отправляет столько
 * же, сколько получает. Поэтому «медленный видеопоток» это поток googlevideo (не `redirector`), TCP, не моложе
 * [minAgeMillis], получивший от [minTotalBytes] до [maxTotalBytes], с отправкой не меньше [minUplinkBytes] и со скоростью
 * за последнее окно ниже [slowBytesPerSecond]. «Здоровым» считается поток от [healthyBytesPerSecond] (120 КБ/с: набор буфера идёт,
 * для 360p нужно около 90 КБ/с; здоровый поток из архива дал 555 КБ за 3 с, то есть 185 КБ/с в среднем). Рукопожатие и простаивающее соединение (меньше [minTotalBytes]) сюда не
 * попадают: их отдельно сбрасывает [StalledFlowPolicy].
 *
 * «Голодание» это когда есть медленный видеопоток, а суммарная скорость всех видеопотоков окна ниже
 * [starvingBytesPerSecond], и так в [observationsRequired] отсчётах подряд. Тогда плеер ждёт данные, а здоровых потоков,
 * которые его кормят, нет: можно менять сервер. Если рядом идёт здоровый поток, голодания нет. Нет его и тогда, когда
 * за последние [deliveredWindowMillis] видео получило не меньше [deliveredHoldBytes]: плеер сыт и просто не берёт данные.
 *
 * Чистая логика без часов и ядра: вызывающий даёт время и снимок потоков (с уже подставленными именами).
 */
class SlowVideoDetector(
    private val minAgeMillis: Long = 4_000,
    private val minTotalBytes: Long = 16L * 1024,
    private val maxTotalBytes: Long = 256L * 1024,
    private val minUplinkBytes: Long = 3L * 1024,
    private val slowBytesPerSecond: Long = 40L * 1024,
    private val healthyBytesPerSecond: Long = 120L * 1024,
    private val starvingBytesPerSecond: Long = 60L * 1024,
    private val observationsRequired: Int = 2,
    private val deliveredWindowMillis: Long = 30_000,
    private val deliveredHoldBytes: Long = 3L * 1024 * 1024,
) {
    data class Verdict(
        /** Видеопотоков googlevideo в снимке. */
        val videoFlows: Int,
        val slowFlows: List<FlowSnap>,
        /** Потоков, которые в этом окне шли здорово (от [healthyBytesPerSecond]). */
        val healthyFlows: Int,
        /** Суммарная скорость видеопотоков за окно, байт/с. */
        val videoBytesPerSecond: Long,
        /** Голодание подтверждено: можно менять сервер. */
        val starving: Boolean,
        /** Видео сейчас идёт здорово. */
        val healthy: Boolean,
        /**
         * Кеш-кластеры YouTube, признанные мёртвыми ([DeadVideoClusterTracker]): плеер повторяет запросы к ним и не
         * получает ничего. Заполняет вызывающий: детектор по потокам этого не видит (у них нет ни байта).
         */
        val deadClusters: List<String> = emptyList(),
    ) {
        /** Есть мёртвый кластер: это повод сменить сервер так же, как голодание. */
        val deadCluster: Boolean get() = deadClusters.isNotEmpty()

        companion object {
            val Idle = Verdict(0, emptyList(), 0, 0, starving = false, healthy = false)
        }
    }

    private class Seen(var uplink: Long, var downlink: Long)

    private val seen = HashMap<String, Seen>()
    private var lastAtMillis = -1L
    private var starvingObservations = 0

    /** Байты видеопотоков по снимкам за последние [deliveredWindowMillis]: пара (время, байт за окно снимка). */
    private val delivered = ArrayDeque<Pair<Long, Long>>()

    fun reset() {
        delivered.clear()
        seen.clear()
        lastAtMillis = -1L
        starvingObservations = 0
        videoActive = false
        videoFlowIds = emptyList()
    }

    /** Есть ли видеопотоки в последнем снимке: тогда стоит опрашивать ядро чаще. */
    var videoActive: Boolean = false
        private set

    /** Идентификаторы видеопотоков последнего снимка: их закрывают после смены сервера. */
    var videoFlowIds: List<String> = emptyList()
        private set

    fun onSnapshot(nowMillis: Long, active: List<FlowSnap>): Verdict {
        val windowSeconds = if (lastAtMillis > 0) ((nowMillis - lastAtMillis) / 1000.0).coerceAtLeast(0.5) else 0.0
        lastAtMillis = nowMillis
        val video = active.filter(::isVideo)
        videoActive = video.isNotEmpty()
        videoFlowIds = video.map { it.id }
        val ids = HashSet<String>(active.size * 2)
        var windowDownTotal = 0.0
        var windowBytes = 0L
        var healthy = 0
        val slow = ArrayList<FlowSnap>()
        for (flow in video) {
            ids += flow.id
            val previous = seen[flow.id]
            val ageSeconds = ((nowMillis - flow.createdAtEpochMillis) / 1000.0).coerceAtLeast(1.0)
            // Первое появление: окна ещё нет, скорость это среднее за жизнь потока.
            val seconds = if (previous != null && windowSeconds > 0) windowSeconds else ageSeconds
            val windowDown = if (previous != null) (flow.downlink - previous.downlink).coerceAtLeast(0) else flow.downlink
            seen[flow.id] = Seen(flow.uplink, flow.downlink)
            windowBytes += windowDown
            val rate = windowDown / seconds
            windowDownTotal += windowDown / seconds
            if (rate >= healthyBytesPerSecond) healthy++
            if (isSlow(nowMillis, flow, rate)) slow += flow
        }
        seen.keys.retainAll(ids)
        val videoRate = windowDownTotal.toLong()
        delivered.addLast(nowMillis to windowBytes)
        while (delivered.isNotEmpty() && nowMillis - delivered.first().first > deliveredWindowMillis) delivered.removeFirst()
        // Плеер с полным буфером сам не берёт данные: окно без байт и один медленный хвост ещё не голод. Если за
        // последние секунды видео получило мегабайты, плеер сыт (поле, 0.5.48: 5 МБ за 30 с, затем две паузы по 5 с
        // дали «ВИДЕО ГОЛОДАЕТ» и ненужную смену рабочего сервера).
        val recentlyFed = delivered.sumOf { it.second } >= deliveredHoldBytes
        val starvingNow = slow.isNotEmpty() && healthy == 0 && videoRate < starvingBytesPerSecond && !recentlyFed
        starvingObservations = if (starvingNow) starvingObservations + 1 else 0
        return Verdict(
            videoFlows = video.size,
            slowFlows = slow,
            healthyFlows = healthy,
            videoBytesPerSecond = videoRate,
            starving = starvingObservations >= observationsRequired,
            healthy = healthy > 0,
        )
    }

    private fun isVideo(flow: FlowSnap): Boolean {
        if (!flow.network.equals("tcp", ignoreCase = true)) return false
        val name = flow.domain.trimEnd('.').lowercase()
        if (!(name == "googlevideo.com" || name.endsWith(".googlevideo.com"))) return false
        // redirector.googlevideo.com отвечает короткими ответами по замыслу: это не видеопоток.
        return !name.startsWith("redirector.")
    }

    private fun isSlow(nowMillis: Long, flow: FlowSnap, rate: Double): Boolean =
        flow.createdAtEpochMillis > 0 &&
            nowMillis - flow.createdAtEpochMillis >= minAgeMillis &&
            flow.downlink in minTotalBytes until maxTotalBytes &&
            flow.uplink >= minUplinkBytes &&
            rate < slowBytesPerSecond
}
