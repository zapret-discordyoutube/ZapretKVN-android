package io.github.zapretkvn.android.vpn.runtime

/** Почему детектор заподозрил «подключено, но данные не идут». */
internal enum class StallKind {
    /** Исходящий трафик растёт, входящий нет, соединения открыты: чёрная дыра. */
    Traffic,

    /** Серия таймаутов DNS в логе ядра при неросшем входящем трафике. */
    Dns,
}

internal sealed interface StallEvent {
    /** Накопились пассивные признаки: пора сделать одну проверку через текущий сервер. */
    data class Suspected(val kind: StallKind, val detail: String) : StallEvent

    /** Входящий трафик заметно пошёл: прежняя тревога о потере связи больше не нужна. */
    data object Recovered : StallEvent
}

/**
 * Пассивный детектор «связь пропала при статусе Подключено».
 *
 * Своих таймеров и запросов нет: вход — отсчёты счётчиков ядра
 * ([TrafficSample]) и строки core-лога об отказе DNS, выход — решение
 * «подозрение». Сам детектор ничего не проверяет: подозрение отдаётся в
 * [PathSupervisor], который делает не более одной проверки через текущий сервер.
 *
 * Сигнал трафика: отсчёты складываются в окна по [windowMillis]. Окно
 * подозрительное, если исходящий трафик вырос не меньше чем на
 * [UPLINK_MIN_BYTES], входящий почти не вырос (меньше [DOWNLINK_FLAT_MAX_BYTES])
 * и в конце окна есть открытые соединения. Подозрение возникает после
 * [stalledWindows] таких окон подряд. Тишина (исходящий не растёт) — не смерть:
 * она обрывает серию, а не продлевает её. Серию обрывает и любой разрыв между
 * отсчётами больше [maxGapMillis] (погашенный экран, doze), и откат счётчиков
 * (новая сессия).
 *
 * Сигнал DNS: [dnsFailures] различных имён с таймаутом за [dnsWindowMillis] при
 * том, что входящий трафик за это время не рос. Без свежих отсчётов трафика
 * за последние [dnsWindowMillis] (источник выключен, наблюдение только началось) DNS-сигнал молчит: по одним строкам лога нельзя отличить
 * мёртвый путь от одного плохого домена.
 *
 * Антифлап: не чаще одной тревоги за [alarmCooldownMillis]. Класс не
 * потокобезопасен: им владеет актор [VpnRuntime].
 */
internal class StallDetector(
    private val windowMillis: Long = WINDOW_MILLIS,
    private val stalledWindows: Int = STALLED_WINDOWS,
    private val maxGapMillis: Long = MAX_GAP_MILLIS,
    private val dnsFailures: Int = DNS_FAILURES,
    private val dnsWindowMillis: Long = DNS_WINDOW_MILLIS,
    private val alarmCooldownMillis: Long = ALARM_COOLDOWN_MILLIS,
) {
    private var windowStartAt = Long.MIN_VALUE
    private var windowStartUplink = 0L
    private var windowStartDownlink = 0L
    private var stalledStreak = 0

    private var lastSampleAt = Long.MIN_VALUE
    private var lastSampleDownlink = 0L
    private var lastDownlinkGrowthAt = Long.MIN_VALUE

    /** С какого момента отсчёты идут без разрывов: раньше судить о «не росло» нельзя. */
    private var observedSince = Long.MIN_VALUE

    private val dnsFailureAt = LinkedHashMap<String, Long>()
    private var lastAlarmAt = Long.MIN_VALUE

    /** Серия подряд идущих подозрительных окон (для тестов и журнала). */
    val streak: Int get() = stalledStreak

    /**
     * Новое окно наблюдения: смена сети, ручной выбор сервера, новая сессия,
     * погасший экран. [keepCooldown] — оставить антифлап тревог (смена сети
     * и экран); новая сессия и ручной выбор начинают с чистого листа.
     */
    fun reset(keepCooldown: Boolean = true) {
        windowStartAt = Long.MIN_VALUE
        stalledStreak = 0
        lastSampleAt = Long.MIN_VALUE
        lastDownlinkGrowthAt = Long.MIN_VALUE
        observedSince = Long.MIN_VALUE
        dnsFailureAt.clear()
        if (!keepCooldown) lastAlarmAt = Long.MIN_VALUE
    }

    fun onSample(sample: TrafficSample): StallEvent? {
        val now = sample.elapsedRealtimeMillis
        val previousAt = lastSampleAt
        val previousDownlink = lastSampleDownlink
        lastSampleAt = now
        lastSampleDownlink = sample.downlinkTotal

        if (previousAt == Long.MIN_VALUE) {
            observedSince = now
            startWindow(sample)
            return null
        }
        val gap = now - previousAt
        if (gap <= 0 || gap > maxGapMillis ||
            sample.downlinkTotal < previousDownlink || sample.uplinkTotal < windowStartUplink
        ) {
            // Пауза в отсчётах или откат счётчиков: прежние выводы устарели.
            stalledStreak = 0
            dnsFailureAt.clear()
            lastDownlinkGrowthAt = Long.MIN_VALUE
            observedSince = now
            startWindow(sample)
            return null
        }
        if (sample.downlinkTotal - previousDownlink >= DOWNLINK_FLAT_MAX_BYTES) lastDownlinkGrowthAt = now

        if (now - windowStartAt < windowMillis) return null

        val uplink = sample.uplinkTotal - windowStartUplink
        val downlink = sample.downlinkTotal - windowStartDownlink
        val elapsed = now - windowStartAt
        startWindow(sample)

        if (downlink >= RECOVERY_DOWNLINK_BYTES) {
            stalledStreak = 0
            return StallEvent.Recovered
        }
        val stalled = uplink >= UPLINK_MIN_BYTES && downlink < DOWNLINK_FLAT_MAX_BYTES && sample.connections > 0
        if (!stalled) {
            stalledStreak = 0
            return null
        }
        stalledStreak += 1
        if (stalledStreak < stalledWindows) return null
        stalledStreak = 0
        return suspect(
            now,
            StallKind.Traffic,
            "исходящий трафик растёт, входящий нет ${stalledWindows} окон подряд " +
                "(последнее: +${uplink} Б вверх, +${downlink} Б вниз за ${elapsed / 1_000} с, соединений: ${sample.connections})",
        )
    }

    /**
     * Строка core-лога об отказе DNS по таймауту. [key] — имя запроса (только в
     * памяти, наружу не выходит); повторы одного имени считаются один раз.
     */
    fun onDnsFailure(nowMillis: Long, key: String): StallEvent? {
        dnsFailureAt.remove(key)
        dnsFailureAt[key] = nowMillis
        val iterator = dnsFailureAt.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMillis - iterator.next().value > dnsWindowMillis) iterator.remove()
        }
        if (dnsFailureAt.size < dnsFailures) return null
        // Нужны свежие отсчёты трафика и ни одного заметного роста входящего за окно.
        if (lastSampleAt == Long.MIN_VALUE || nowMillis - lastSampleAt > maxGapMillis) return null
        if (observedSince == Long.MIN_VALUE || nowMillis - observedSince < dnsWindowMillis) return null
        if (lastDownlinkGrowthAt != Long.MIN_VALUE && nowMillis - lastDownlinkGrowthAt <= dnsWindowMillis) return null
        val count = dnsFailureAt.size
        dnsFailureAt.clear()
        return suspect(
            nowMillis,
            StallKind.Dns,
            "$count DNS-отказов по таймауту за ${dnsWindowMillis / 1_000} с, входящий трафик не растёт",
        )
    }

    private fun suspect(now: Long, kind: StallKind, detail: String): StallEvent.Suspected? {
        if (lastAlarmAt != Long.MIN_VALUE && now - lastAlarmAt < alarmCooldownMillis) return null
        lastAlarmAt = now
        stalledStreak = 0
        dnsFailureAt.clear()
        return StallEvent.Suspected(kind, detail)
    }

    private fun startWindow(sample: TrafficSample) {
        windowStartAt = sample.elapsedRealtimeMillis
        windowStartUplink = sample.uplinkTotal
        windowStartDownlink = sample.downlinkTotal
    }

    companion object {
        /** Длина окна подсчёта; отсчёты ядра идут чаще, окно закрывается первым отсчётом после неё. */
        const val WINDOW_MILLIS = 5_000L

        /** Три окна (~15 с) подряд: короткая пауза или медленная страница не считаются. */
        const val STALLED_WINDOWS = 3

        /** Разрыв между отсчётами больше этого (экран погас, doze) обнуляет наблюдение. */
        const val MAX_GAP_MILLIS = 12_000L

        /** Меньший рост исходящего — тишина: приложения ничего не отправляют. */
        const val UPLINK_MIN_BYTES = 200L

        /** Рост входящего меньше этого — фоновый шум, а не ответы серверов. */
        const val DOWNLINK_FLAT_MAX_BYTES = 512L

        /** Окно с таким входящим трафиком снимает «нет связи». */
        const val RECOVERY_DOWNLINK_BYTES = 8 * 1024L

        const val DNS_FAILURES = 3
        const val DNS_WINDOW_MILLIS = 20_000L

        /** Не чаще одной пассивной тревоги за это время. */
        const val ALARM_COOLDOWN_MILLIS = 5 * 60_000L
    }
}
