package io.github.zapretkvn.android.engines.failover

import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * «Умная проверка»: переключение сервера при низкой скорости.
 *
 * Значения совпадают с ПК-клиентом; менять их нужно на обеих платформах сразу.
 *
 * Пассивное подозрение само ничего не переключает. Оно только запускает
 * активную проверку: короткую ограниченную HTTPS-загрузку через туннель.
 */
internal object SlowServerSwitchDefaults {
    /** Порог «медленно»: ~1 Мбит/с. */
    const val THRESHOLD_BYTES_PER_SECOND = 128L * 1024

    /**
     * Нижняя граница «спроса». Ниже — фоновые keepalive/push, а не приложение,
     * которое ждёт данные; такой трафик подозрения не создаёт.
     */
    const val DEMAND_FLOOR_BYTES_PER_SECOND = 8L * 1024

    /** Сколько подряд должен держаться «спрос при низкой скорости». */
    const val SUSPICION_WINDOW_MILLIS = 20_000L

    /**
     * Разрыв между отсчётами больше этого (пропущенный отсчёт, сон устройства)
     * обнуляет окно: данные о спросе устарели. Отсчёт приходит раз в 5 с.
     */
    const val MAX_SAMPLE_GAP_MILLIS = 11_000L

    /** Ложная тревога или неубедительный замер: пауза перед следующей проверкой. */
    const val FALSE_ALARM_COOLDOWN_MILLIS = 5 * 60_000L

    /** После любого переключения (в том числе пробного) — без умных переключений. */
    const val POST_SWITCH_HOLD_MILLIS = 10 * 60_000L

    /** Покинутый или не оправдавший себя сервер считается медленным. */
    const val SLOW_MARK_MILLIS = 30 * 60_000L

    const val MAX_SWITCHES_PER_HOUR = 3
    const val HOUR_MILLIS = 60 * 60_000L

    /** Сколько кандидатов рассматривается за эпизод. */
    const val CANDIDATE_LIMIT = 3

    /** Кандидат должен быть минимум во столько раз быстрее текущего. */
    const val REQUIRED_SPEEDUP = 2L

    /** Жёсткий дедлайн всей проверки; пробное переключение при этом откатывается. */
    const val EPISODE_DEADLINE_MILLIS = 120_000L
}

/**
 * Пассивный детектор подозрения по отсчётам статуса ядра.
 *
 * Скорость считается по разнице счётчика загруженных байт между отсчётами,
 * а не по мгновенному значению: у видео трафик идёт пачками. Подозрение —
 * это непрерывные [SlowServerSwitchDefaults.SUSPICION_WINDOW_MILLIS], когда
 * есть активные соединения, данные идут (не ниже порога спроса), но медленнее
 * порога. Простой (нет соединений или почти нет трафика) окно обнуляет.
 *
 * Таймера нет: время берётся из отсчётов, которые присылает ядро.
 */
internal class SlowThroughputDetector(
    private val thresholdBytesPerSecond: Long = SlowServerSwitchDefaults.THRESHOLD_BYTES_PER_SECOND,
    private val demandFloorBytesPerSecond: Long = SlowServerSwitchDefaults.DEMAND_FLOOR_BYTES_PER_SECOND,
    private val windowMillis: Long = SlowServerSwitchDefaults.SUSPICION_WINDOW_MILLIS,
    private val maxGapMillis: Long = SlowServerSwitchDefaults.MAX_SAMPLE_GAP_MILLIS,
) {
    private var lastAtMillis: Long = Long.MIN_VALUE
    private var lastTotalBytes: Long = 0
    private var suspiciousSinceMillis: Long = Long.MIN_VALUE

    /**
     * @param downlinkTotalBytes накопительный счётчик загрузки туннеля.
     * @param activeConnections число активных исходящих соединений ядра.
     * @return true, когда окно подозрения выдержано полностью.
     */
    @Synchronized
    fun onSample(nowMillis: Long, downlinkTotalBytes: Long, activeConnections: Int): Boolean {
        val previousAt = lastAtMillis
        val previousTotal = lastTotalBytes
        lastAtMillis = nowMillis
        lastTotalBytes = downlinkTotalBytes
        if (previousAt == Long.MIN_VALUE) return false
        val elapsed = nowMillis - previousAt
        if (elapsed <= 0 || elapsed > maxGapMillis || downlinkTotalBytes < previousTotal) {
            suspiciousSinceMillis = Long.MIN_VALUE
            return false
        }
        val rate = (downlinkTotalBytes - previousTotal) * 1_000 / elapsed
        val demand = activeConnections > 0 && rate >= demandFloorBytesPerSecond
        if (!demand || rate >= thresholdBytesPerSecond) {
            suspiciousSinceMillis = Long.MIN_VALUE
            return false
        }
        if (suspiciousSinceMillis == Long.MIN_VALUE) suspiciousSinceMillis = previousAt
        return nowMillis - suspiciousSinceMillis >= windowMillis
    }

    /** Новое окно: после запуска проверки, смены сети или сервера. */
    @Synchronized
    fun reset() {
        lastAtMillis = Long.MIN_VALUE
        lastTotalBytes = 0
        suspiciousSinceMillis = Long.MIN_VALUE
    }
}

internal enum class SlowSwitchGate {
    Ready,
    Disabled,
    Busy,
    Cooldown,
    PostSwitchHold,
    HourlyLimit,
}

/** Последний сохранённый пинг сервера: для ранжирования кандидатов. */
internal data class LatencyHint(
    val millis: Int?,
    val failed: Boolean,
)

/**
 * Состояние антифлапа «умной проверки». Живёт на уровне сервиса, а не сессии:
 * переподключение не должно обнулять лимиты. Время — монотонное.
 */
internal class SlowServerSwitchPolicy(
    private val monotonicMillis: () -> Long,
) {
    private var cooldownUntil = Long.MIN_VALUE
    private var lastSwitchAt = Long.MIN_VALUE
    private val switchTimes = ArrayDeque<Long>()
    private val slowUntil = mutableMapOf<String, Long>()
    private var profileId: String? = null

    /** Медленные метки относятся к тегам конкретного профиля. */
    @Synchronized
    fun bindProfile(profileId: String) {
        if (this.profileId == profileId) return
        this.profileId = profileId
        slowUntil.clear()
    }

    @Synchronized
    fun gate(enabled: Boolean, busy: Boolean): SlowSwitchGate {
        if (!enabled) return SlowSwitchGate.Disabled
        if (busy) return SlowSwitchGate.Busy
        val now = monotonicMillis()
        if (lastSwitchAt != Long.MIN_VALUE &&
            now - lastSwitchAt < SlowServerSwitchDefaults.POST_SWITCH_HOLD_MILLIS
        ) {
            return SlowSwitchGate.PostSwitchHold
        }
        if (cooldownUntil != Long.MIN_VALUE && now < cooldownUntil) return SlowSwitchGate.Cooldown
        pruneSwitches(now)
        if (switchTimes.size >= SlowServerSwitchDefaults.MAX_SWITCHES_PER_HOUR) {
            return SlowSwitchGate.HourlyLimit
        }
        return SlowSwitchGate.Ready
    }

    /**
     * Кандидаты из той же группы профиля: пригодные для failover, не текущий,
     * не помеченные медленными и без последнего неудачного пинга. Сначала —
     * с лучшим сохранённым пингом, затем без пинга в порядке профиля.
     */
    @Synchronized
    fun candidates(
        currentId: String,
        targets: List<FailoverTarget>,
        hints: Map<String, LatencyHint>,
    ): List<FailoverTarget> {
        val now = monotonicMillis()
        slowUntil.entries.removeAll { it.value <= now }
        return targets
            .asSequence()
            .filter { it.id != currentId && it.valid && !it.maintenance }
            .filter { it.id !in slowUntil }
            .filter { hints[it.id]?.failed != true }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<FailoverTarget>> { hints[it.value.id]?.millis ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
            .map { it.value }
            .take(SlowServerSwitchDefaults.CANDIDATE_LIMIT)
            .toList()
    }

    fun isBetter(currentBytesPerSecond: Long, candidateBytesPerSecond: Long): Boolean =
        candidateBytesPerSecond >= SlowServerSwitchDefaults.THRESHOLD_BYTES_PER_SECOND &&
            candidateBytesPerSecond >= currentBytesPerSecond * SlowServerSwitchDefaults.REQUIRED_SPEEDUP

    fun isSlow(bytesPerSecond: Long): Boolean =
        bytesPerSecond < SlowServerSwitchDefaults.THRESHOLD_BYTES_PER_SECOND

    /** Ложная тревога, неубедительный замер или нет кандидатов. */
    @Synchronized
    fun onCooldown() {
        cooldownUntil = monotonicMillis() + SlowServerSwitchDefaults.FALSE_ALARM_COOLDOWN_MILLIS
    }

    /** Любое переключение селектора, даже пробное, расходует часовой лимит. */
    @Synchronized
    fun onSwitch() {
        val now = monotonicMillis()
        lastSwitchAt = now
        pruneSwitches(now)
        switchTimes.addLast(now)
    }

    @Synchronized
    fun markSlow(id: String) {
        slowUntil[id] = monotonicMillis() + SlowServerSwitchDefaults.SLOW_MARK_MILLIS
    }

    @Synchronized
    fun isMarkedSlow(id: String): Boolean = (slowUntil[id] ?: Long.MIN_VALUE) > monotonicMillis()

    private fun pruneSwitches(now: Long) {
        while (switchTimes.isNotEmpty() &&
            now - switchTimes.first() >= SlowServerSwitchDefaults.HOUR_MILLIS
        ) {
            switchTimes.removeFirst()
        }
    }
}

/** Что видно движку в момент проверки: текущий сервер и цели failover той же группы. */
internal data class SlowSwitchSnapshot(
    val currentId: String,
    val targets: List<FailoverTarget>,
    val hints: Map<String, LatencyHint>,
    /** Подключение, переподключение или failover в процессе. */
    val busy: Boolean,
)

/**
 * Операции сервиса. Переключение идёт тем же путём selector hot-switch, что у
 * failover и ручного выбора; второго механизма смены сервера нет.
 */
internal interface SlowSwitchHost {
    /** null — сессии больше нет. */
    suspend fun snapshot(): SlowSwitchSnapshot?

    /** Реальная скорость через туннель, байт/с; null — замер не удался. */
    suspend fun measureThroughput(): Long?

    /** Пробно переключает селектор; false — состояние изменилось или команда не прошла. */
    suspend fun trialSwitch(currentId: String, candidateId: String): Boolean

    /** Сохраняет пробный выбор в профиле; false — пробу перехватил пользователь или failover. */
    suspend fun commit(candidateId: String): Boolean

    /** Возвращает прежний сервер, если проба всё ещё наша. */
    suspend fun rollback()

    fun log(message: String)

    fun announceSwitch(fromId: String, toId: String, fromBytesPerSecond: Long, toBytesPerSecond: Long)
}

internal sealed interface SlowSwitchOutcome {
    data class Skipped(val gate: SlowSwitchGate) : SlowSwitchOutcome
    data object SessionGone : SlowSwitchOutcome
    data object NoCandidate : SlowSwitchOutcome
    data object Inconclusive : SlowSwitchOutcome
    data object FalseAlarm : SlowSwitchOutcome
    data object TrialFailed : SlowSwitchOutcome
    data object NoBetter : SlowSwitchOutcome
    data object Superseded : SlowSwitchOutcome
    data class Switched(val fromId: String, val toId: String) : SlowSwitchOutcome
}

/**
 * Один эпизод «умной проверки».
 *
 * libbox не умеет мерить скорость отдельного outbound без переключения
 * селектора, поэтому кандидат проверяется после переключения: сервер с лучшим
 * сохранённым пингом выбирается пробно, замеряется и либо закрепляется (если
 * он не меньше порога и минимум вдвое быстрее), либо селектор возвращается
 * назад, а кандидат помечается медленным. За эпизод — одна проба: каждое
 * переключение рвёт текущие соединения (interrupt_exist_connections), а
 * следующий кандидат будет проверен после паузы антифлапа.
 */
internal class SlowServerSwitchEngine(
    private val policy: SlowServerSwitchPolicy,
    private val host: SlowSwitchHost,
    private val enabled: () -> Boolean,
    private val deadlineMillis: Long = SlowServerSwitchDefaults.EPISODE_DEADLINE_MILLIS,
) {
    suspend fun runEpisode(): SlowSwitchOutcome =
        withTimeoutOrNull(deadlineMillis) { runEpisodeWithinDeadline() }
            ?: run {
                policy.onCooldown()
                host.log("Проверка скорости не уложилась в 120 с; переключения нет.")
                SlowSwitchOutcome.Inconclusive
            }

    private suspend fun runEpisodeWithinDeadline(): SlowSwitchOutcome {
        val snapshot = host.snapshot() ?: return SlowSwitchOutcome.SessionGone
        policy.gate(enabled(), snapshot.busy).takeIf { it != SlowSwitchGate.Ready }?.let { gate ->
            return SlowSwitchOutcome.Skipped(gate)
        }
        val candidates = policy.candidates(snapshot.currentId, snapshot.targets, snapshot.hints)
        if (candidates.isEmpty()) {
            policy.onCooldown()
            host.log("Низкая скорость: других подходящих серверов нет, замер пропущен.")
            return SlowSwitchOutcome.NoCandidate
        }
        val current = host.measureThroughput()
        if (current == null) {
            policy.onCooldown()
            host.log("Низкая скорость: замер текущего сервера не удался, переключения нет.")
            return SlowSwitchOutcome.Inconclusive
        }
        if (!policy.isSlow(current)) {
            policy.onCooldown()
            host.log("Низкая скорость не подтвердилась: ${formatRate(current)}.")
            return SlowSwitchOutcome.FalseAlarm
        }
        // Пока шёл замер, пользователь или failover могли сменить состояние.
        val fresh = host.snapshot() ?: return SlowSwitchOutcome.SessionGone
        if (fresh.currentId != snapshot.currentId) return SlowSwitchOutcome.Superseded
        policy.gate(enabled(), fresh.busy).takeIf { it != SlowSwitchGate.Ready }?.let { gate ->
            return SlowSwitchOutcome.Skipped(gate)
        }
        val candidate = candidates.first()
        host.log(
            "Низкая скорость подтверждена: ${formatRate(current)}; " +
                "пробуем сервер ${candidate.id}.",
        )
        policy.onSwitch()
        if (!host.trialSwitch(snapshot.currentId, candidate.id)) {
            policy.markSlow(candidate.id)
            host.log("Пробное переключение на ${candidate.id} не выполнено.")
            return SlowSwitchOutcome.TrialFailed
        }
        var committed = false
        try {
            val measured = host.measureThroughput()
            if (measured == null || !policy.isBetter(current, measured)) {
                policy.markSlow(candidate.id)
                host.log(
                    "Сервер ${candidate.id} не быстрее " +
                        "(${measured?.let(::formatRate) ?: "замер не удался"} против " +
                        "${formatRate(current)}); возврат на ${snapshot.currentId}.",
                )
                return SlowSwitchOutcome.NoBetter
            }
            if (!host.commit(candidate.id)) {
                committed = true // проба уже не наша или хост сам вернул прежний сервер
                host.log("Проба на ${candidate.id} не закреплена.")
                return SlowSwitchOutcome.Superseded
            }
            committed = true
            policy.markSlow(snapshot.currentId)
            host.log(
                "Сервер переключён из-за низкой скорости: ${snapshot.currentId} " +
                    "${formatRate(current)} → ${candidate.id} ${formatRate(measured)}.",
            )
            host.announceSwitch(snapshot.currentId, candidate.id, current, measured)
            return SlowSwitchOutcome.Switched(snapshot.currentId, candidate.id)
        } finally {
            if (!committed) withContext(NonCancellable) { host.rollback() }
        }
    }

    companion object {
        fun formatRate(bytesPerSecond: Long): String =
            String.format(Locale.US, "%.1f Мбит/с", bytesPerSecond * 8 / 1_000_000.0)
    }
}
