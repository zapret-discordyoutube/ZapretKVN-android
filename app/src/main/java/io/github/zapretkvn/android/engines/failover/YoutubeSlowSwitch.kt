package io.github.zapretkvn.android.engines.failover

/**
 * Переключение сервера, когда он плох именно для YouTube (владелец 2026-11-08: «перенаправлять на более быстрый
 * сервер», настройка по желанию).
 *
 * Чем это отличается от «умной проверки» ([SlowServerSwitchEngine]): та смотрит на общую скорость и качает
 * посторонний файл, поэтому не замечает сервера, у которого всё быстро, кроме кеш-узлов YouTube (так было в архиве 0.5.38:
 * туннель чистый, а плеер сидит на потоке в 5 КБ/с). Здесь поводом служит «голодание» видео (`SlowVideoDetector`),
 * а проверка кандидата идёт по живому видео: здоровый поток после переключения закрепляет выбор, голодание означает,
 * что кандидат тоже плох для YouTube, молчание означает, что судить не по чему, и тогда возвращается прежний сервер.
 *
 * Выбор кандидата использует историю скоростей: сначала серверы с хорошей средней по видео, затем без истории по пингу,
 * серверы с плохой историей и помеченные медленными не берутся. Переключения идут тем же `ServerSwitcher`, что ручной
 * выбор и failover: второго способа сменить сервер нет.
 */
internal object YoutubeSwitchDefaults {
    /** Пауза после эпизода: плеер должен успеть прийти в себя. */
    const val COOLDOWN_MILLIS = 90_000L

    /** Не больше столько переключений за [WINDOW_MILLIS]: сервер не должен прыгать без конца. */
    const val MAX_SWITCHES_PER_WINDOW = 3
    const val WINDOW_MILLIS = 10 * 60_000L

    /** Сколько ждать здорового видео на кандидате. */
    const val VERIFY_MILLIS = 15_000L

    /** Сервер, плохой для YouTube, не берётся в кандидаты столько. */
    const val SLOW_MARK_MILLIS = 30 * 60_000L

    /** Сколько кандидатов пробовать за один эпизод. */
    const val MAX_CANDIDATES = 2

    /** Средняя по видео, ниже которой сервер по истории не берётся совсем (граница «медленно» в журнале). */
    const val BAD_HISTORY_BYTES_PER_SECOND = 50L * 1024
}

internal enum class YoutubeSwitchGate { Ready, Disabled, Busy, Cooldown, Limit }

/** Антифлап и метки «плох для YouTube». Живёт на уровне сервиса; время монотонное. */
internal class YoutubeSwitchPolicy(private val monotonicMillis: () -> Long) {
    private var cooldownUntil = Long.MIN_VALUE
    private val switchTimes = ArrayDeque<Long>()
    private val slowUntil = mutableMapOf<String, Long>()
    private var profileId: String? = null

    /** Метки относятся к тегам конкретного профиля. */
    @Synchronized
    fun bindProfile(profileId: String) {
        if (this.profileId == profileId) return
        this.profileId = profileId
        slowUntil.clear()
    }

    @Synchronized
    fun gate(enabled: Boolean, busy: Boolean): YoutubeSwitchGate {
        if (!enabled) return YoutubeSwitchGate.Disabled
        if (busy) return YoutubeSwitchGate.Busy
        val now = monotonicMillis()
        if (cooldownUntil != Long.MIN_VALUE && now < cooldownUntil) return YoutubeSwitchGate.Cooldown
        prune(now)
        if (switchTimes.size >= YoutubeSwitchDefaults.MAX_SWITCHES_PER_WINDOW) return YoutubeSwitchGate.Limit
        return YoutubeSwitchGate.Ready
    }

    /**
     * Кандидаты той же группы: пригодные, не текущий, не помеченные, без неудачного пинга и без плохой истории.
     * Порядок: известные хорошие по убыванию средней по видео, затем без истории по пингу и порядку профиля.
     */
    @Synchronized
    fun candidates(
        currentId: String,
        targets: List<FailoverTarget>,
        hints: Map<String, LatencyHint>,
        youtubeAverage: (String) -> Long?,
    ): List<FailoverTarget> {
        val now = monotonicMillis()
        slowUntil.entries.removeAll { it.value <= now }
        return targets
            .asSequence()
            .filter { it.id != currentId && it.valid && !it.maintenance }
            .filter { it.id !in slowUntil }
            .filter { hints[it.id]?.failed != true }
            .filter { (youtubeAverage(it.id) ?: Long.MAX_VALUE) >= YoutubeSwitchDefaults.BAD_HISTORY_BYTES_PER_SECOND }
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<FailoverTarget>> { youtubeAverage(it.value.id) ?: -1L }
                    .thenBy { hints[it.value.id]?.millis ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
            .map { it.value }
            .take(YoutubeSwitchDefaults.MAX_CANDIDATES)
            .toList()
    }

    @Synchronized
    fun markSlow(id: String) {
        slowUntil[id] = monotonicMillis() + YoutubeSwitchDefaults.SLOW_MARK_MILLIS
    }

    @Synchronized
    fun isMarkedSlow(id: String): Boolean = (slowUntil[id] ?: Long.MIN_VALUE) > monotonicMillis()

    /** Любое переключение, даже пробное, расходует лимит окна. */
    @Synchronized
    fun onSwitch() {
        val now = monotonicMillis()
        prune(now)
        switchTimes.addLast(now)
    }

    @Synchronized
    fun onEpisodeEnd() {
        cooldownUntil = monotonicMillis() + YoutubeSwitchDefaults.COOLDOWN_MILLIS
    }

    private fun prune(now: Long) {
        while (switchTimes.isNotEmpty() && now - switchTimes.first() >= YoutubeSwitchDefaults.WINDOW_MILLIS) {
            switchTimes.removeFirst()
        }
    }
}

/** Что видно после переключения: видео идёт здорово, голодает или не идёт вовсе. */
internal enum class VideoOutcome { Healthy, Starving, Silent }

/** Операции сервиса для эпизода. Переключение идёт общим `ServerSwitcher`, как у ручного выбора и failover. */
internal interface YoutubeSwitchHost {
    /** null: сессии больше нет. */
    suspend fun snapshot(): SlowSwitchSnapshot?

    /** Пробно переключает селектор без сохранения; false: состояние изменилось или команда не прошла. */
    suspend fun trialSwitch(currentId: String, candidateId: String): Boolean

    /** Закрывает видеопотоки, шедшие через прежний сервер: плеер откроет новые уже через новый. */
    suspend fun dropVideoFlows()

    /** Ждёт здорового видео, голодания или тишины не дольше [timeoutMillis]. */
    suspend fun verifyVideo(timeoutMillis: Long): VideoOutcome

    /** Сохраняет выбор в профиле; false: пробу перехватил пользователь или failover. */
    suspend fun commit(candidateId: String): Boolean

    /** Возвращает сервер [id] (то, с чего эпизод начался). */
    suspend fun restore(id: String)

    fun log(message: String)

    fun announce(fromId: String, toId: String)
}

internal sealed interface YoutubeSwitchOutcome {
    data object Gone : YoutubeSwitchOutcome
    data object NoCandidates : YoutubeSwitchOutcome
    data class Switched(val serverId: String) : YoutubeSwitchOutcome
    data object Inconclusive : YoutubeSwitchOutcome
    data object AllCandidatesSlow : YoutubeSwitchOutcome
    data object TrialFailed : YoutubeSwitchOutcome
    data object CommitRefused : YoutubeSwitchOutcome
}

internal class YoutubeSwitchEngine(
    private val policy: YoutubeSwitchPolicy,
    private val host: YoutubeSwitchHost,
    private val youtubeAverage: (String) -> Long?,
    private val hintsOf: (SlowSwitchSnapshot) -> Map<String, LatencyHint> = { it.hints },
) {
    suspend fun runEpisode(): YoutubeSwitchOutcome {
        val snapshot = host.snapshot() ?: return YoutubeSwitchOutcome.Gone
        val original = snapshot.currentId
        policy.markSlow(original)
        val candidates = policy.candidates(original, snapshot.targets, hintsOf(snapshot), youtubeAverage)
        if (candidates.isEmpty()) {
            host.log("YouTube на сервере $original медленный, подходящего запасного сервера нет.")
            policy.onEpisodeEnd()
            return YoutubeSwitchOutcome.NoCandidates
        }
        var current = original
        for (candidate in candidates) {
            if (!host.trialSwitch(current, candidate.id)) {
                host.restore(original)
                policy.onEpisodeEnd()
                return YoutubeSwitchOutcome.TrialFailed
            }
            policy.onSwitch()
            host.dropVideoFlows()
            when (host.verifyVideo(YoutubeSwitchDefaults.VERIFY_MILLIS)) {
                VideoOutcome.Healthy -> {
                    if (!host.commit(candidate.id)) {
                        host.restore(original)
                        policy.onEpisodeEnd()
                        return YoutubeSwitchOutcome.CommitRefused
                    }
                    host.announce(original, candidate.id)
                    policy.onEpisodeEnd()
                    return YoutubeSwitchOutcome.Switched(candidate.id)
                }
                VideoOutcome.Starving -> {
                    host.log("YouTube на сервере ${candidate.id} тоже медленный.")
                    policy.markSlow(candidate.id)
                    current = candidate.id
                }
                VideoOutcome.Silent -> {
                    // Видео не пошло вовсе (пользователь ушёл): судить не по чему, прежний выбор пользователя возвращается.
                    host.restore(original)
                    policy.onEpisodeEnd()
                    return YoutubeSwitchOutcome.Inconclusive
                }
            }
        }
        host.restore(original)
        policy.onEpisodeEnd()
        return YoutubeSwitchOutcome.AllCandidatesSlow
    }
}
