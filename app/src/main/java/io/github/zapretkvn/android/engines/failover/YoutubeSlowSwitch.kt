package io.github.zapretkvn.android.engines.failover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    const val MAX_SWITCHES_PER_WINDOW = 6
    const val WINDOW_MILLIS = 10 * 60_000L

    /** Сколько ждать здорового видео на кандидате. */
    const val VERIFY_MILLIS = 15_000L

    /** Сервер, плохой для YouTube, не берётся в кандидаты столько. */
    const val SLOW_MARK_MILLIS = 30 * 60_000L

    /** Сколько кандидатов пробовать за один эпизод. */
    const val MAX_CANDIDATES = 3

    /** Средняя по видео, ниже которой сервер по истории не берётся совсем (граница «медленно» в журнале). */
    const val BAD_HISTORY_BYTES_PER_SECOND = 50L * 1024

    /**
     * Видео голодает одинаково на стольких серверах подряд (прежний и отвечающие кандидаты): дело не в сервере, а в
     * клиенте YouTube или в ответах YouTube этому клиенту. Поле 2026-11-15 18:44-18:50 UTC: за 5 минут эпизоды прошли
     * девять серверов, включая Rusina, которая час назад отдала 60 МБ видео; на всех потоки отправляли больше, чем
     * получали, а через 6 минут тот же клиент через Platifin играл. Отметки этого эпизода снимаются. Поставить
     * [Int.MAX_VALUE], чтобы вернуть прежнее поведение (каждый голодающий сервер виноват).
     */
    const val CLIENT_STALL_MIN_SERVERS = 3

    /** Пауза после вывода «дело не в сервере»: смена сервера не поможет, пользователь перезапускает YouTube. */
    const val CLIENT_STALL_COOLDOWN_MILLIS = 3 * 60_000L
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
        /** Серверы, которые только что подтверждённо не отвечали (общий failover): пробовать их нет смысла. */
        excludedIds: Set<String> = emptySet(),
        /** Серверы с недавним хорошим результатом: после истории по видео и до пинга. */
        alive: Set<String> = emptySet(),
        youtubeAverage: (String) -> Long?,
    ): List<FailoverTarget> {
        val now = monotonicMillis()
        slowUntil.entries.removeAll { it.value <= now }
        return targets
            .asSequence()
            .filter { it.id != currentId && it.valid && !it.maintenance }
            .filter { it.id !in slowUntil }
            .filter { it.id !in excludedIds }
            .filter { hints[it.id]?.failed != true }
            .filter { (youtubeAverage(it.id) ?: Long.MAX_VALUE) >= YoutubeSwitchDefaults.BAD_HISTORY_BYTES_PER_SECOND }
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<FailoverTarget>> { youtubeAverage(it.value.id) ?: -1L }
                    .thenBy { if (it.value.id in alive) 0 else 1 }
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

    /** Снимает метки «плох для YouTube»: эпизод выяснил, что эти серверы не виноваты. */
    @Synchronized
    fun clearSlow(ids: Collection<String>) {
        ids.forEach { slowUntil.remove(it) }
    }

    /** Любое переключение, даже пробное, расходует лимит окна. */
    @Synchronized
    fun onSwitch() {
        val now = monotonicMillis()
        prune(now)
        switchTimes.addLast(now)
    }

    @Synchronized
    fun onEpisodeEnd(cooldownMillis: Long = YoutubeSwitchDefaults.COOLDOWN_MILLIS) {
        cooldownUntil = monotonicMillis() + cooldownMillis
    }

    private fun prune(now: Long) {
        while (switchTimes.isNotEmpty() && now - switchTimes.first() >= YoutubeSwitchDefaults.WINDOW_MILLIS) {
            switchTimes.removeFirst()
        }
    }
}

/**
 * Что видно после переключения: видео идёт здорово, голодает, не идёт вовсе, или пробный сервер мёртв (слот сам ушёл
 * с него: DNS или соединения не прошли). Мёртвый сервер, как и голодающий, ведёт к следующему кандидату, но это другой
 * признак: он не говорит, что видео голодает везде.
 */
internal enum class VideoOutcome { Healthy, Starving, Silent, Dead }

/** Операции сервиса для эпизода. Переключение идёт общим `ServerSwitcher`, как у ручного выбора и failover. */
internal interface YoutubeSwitchHost {
    /** null: сессии больше нет. */
    suspend fun snapshot(): SlowSwitchSnapshot?

    /**
     * Ни один кандидат не дал здорового видео. По умолчанию возвращается прежний сервер; хост слота может оставить
     * слот на кандидате, который отвечал, чтобы не вернуться на сервер, на котором видео голодало.
     */
    suspend fun endWithoutWinner(original: String) = restore(original)

    /**
     * Видео голодало одинаково на [original] и на отвечающих кандидатах [starved]: дело не в сервере. Хост снимает
     * отметку, которую эпизод поставил [original], и пишет в журнал, почему.
     */
    fun notTheServer(original: String, starved: List<String>) = Unit

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

    /** [restartVideo]: выбор не проверен видео, плеер держит ссылки мёртвого кластера и ждёт перезапуска видео. */
    fun announce(fromId: String, toId: String, restartVideo: Boolean = false)
}

internal sealed interface YoutubeSwitchOutcome {
    data object Gone : YoutubeSwitchOutcome
    data object NoCandidates : YoutubeSwitchOutcome
    data class Switched(val serverId: String) : YoutubeSwitchOutcome

    /** Мёртвый кластер: запасной сервер оставлен без проверки видео, плееру нужен перезапуск видео. */
    data class SwitchedUnverified(val serverId: String) : YoutubeSwitchOutcome
    data object Inconclusive : YoutubeSwitchOutcome
    data object AllCandidatesSlow : YoutubeSwitchOutcome

    /** Видео голодало на прежнем сервере и на отвечающих кандидатах: дело в клиенте YouTube, отметки сняты. */
    data class StarvesEverywhere(val servers: Int) : YoutubeSwitchOutcome
    data object TrialFailed : YoutubeSwitchOutcome
    data object CommitRefused : YoutubeSwitchOutcome
}

internal class YoutubeSwitchEngine(
    private val policy: YoutubeSwitchPolicy,
    private val host: YoutubeSwitchHost,
    private val youtubeAverage: (String) -> Long?,
    private val hintsOf: (SlowSwitchSnapshot) -> Map<String, LatencyHint> = { it.hints },
    private val excludedIds: () -> Set<String> = { emptySet() },
) {
    /**
     * [keepOnSilent]: эпизод начался с вердикта «мёртвый кластер». Запущенный плеер держит ссылки именно этого кластера
     * (YouTube выбирает кластер по адресу запроса /player, а ссылка подписана) и не откроет видеопоток через новый сервер,
     * пока видео не перезапустят. Тишина тогда не значит «пользователь ушёл», и запасной сервер остаётся: перезапущенное
     * видео получит другой кластер. Если запасной сервер окажется плох, обычный путь голодания переключит его снова.
     */
    suspend fun runEpisode(keepOnSilent: Boolean = false): YoutubeSwitchOutcome {
        val state = EpisodeState()
        try {
            return episode(keepOnSilent, state)
        } catch (cancelled: CancellationException) {
            // Отмена посреди эпизода (ручной выбор сервера, проба по подсказке ядра) не должна оставлять сервер на
            // непроверенном кандидате: пробное переключение откатывается, даже когда корутина уже отменена.
            val original = state.original
            if (state.trialOpen && original != null) {
                withContext(NonCancellable) {
                    host.restore(original)
                    host.log("Эпизод YouTube прерван: возвращён прежний сервер $original.")
                }
            }
            policy.onEpisodeEnd()
            throw cancelled
        }
    }

    /** Что эпизод успел сделать: по этому отмена знает, нужен ли откат. */
    private class EpisodeState {
        var original: String? = null
        var trialOpen: Boolean = false
    }

    private suspend fun episode(keepOnSilent: Boolean, state: EpisodeState): YoutubeSwitchOutcome {
        val snapshot = host.snapshot() ?: return YoutubeSwitchOutcome.Gone
        val original = snapshot.currentId
        state.original = original
        policy.markSlow(original)
        val candidates = policy.candidates(original, snapshot.targets, hintsOf(snapshot), excludedIds(), snapshot.alive, youtubeAverage)
        if (candidates.isEmpty()) {
            host.log("YouTube на сервере $original медленный, подходящего запасного сервера нет.")
            policy.onEpisodeEnd()
            return YoutubeSwitchOutcome.NoCandidates
        }
        var current = original
        // Кандидаты, которые отвечали, но видео на них голодало так же, как на прежнем сервере.
        val starved = ArrayList<String>()
        for (candidate in candidates) {
            if (!host.trialSwitch(current, candidate.id)) {
                host.restore(original)
                policy.onEpisodeEnd()
                return YoutubeSwitchOutcome.TrialFailed
            }
            state.trialOpen = true
            policy.onSwitch()
            host.dropVideoFlows()
            when (host.verifyVideo(YoutubeSwitchDefaults.VERIFY_MILLIS)) {
                VideoOutcome.Healthy -> {
                    if (!host.commit(candidate.id)) {
                        host.restore(original)
                        policy.onEpisodeEnd()
                        return YoutubeSwitchOutcome.CommitRefused
                    }
                    state.trialOpen = false
                    host.announce(original, candidate.id)
                    policy.onEpisodeEnd()
                    return YoutubeSwitchOutcome.Switched(candidate.id)
                }
                VideoOutcome.Starving -> {
                    host.log("YouTube на сервере ${candidate.id} тоже медленный.")
                    policy.markSlow(candidate.id)
                    starved += candidate.id
                    current = candidate.id
                }
                VideoOutcome.Dead -> {
                    policy.markSlow(candidate.id)
                    current = candidate.id
                }
                VideoOutcome.Silent -> if (keepOnSilent) {
                    if (!host.commit(candidate.id)) {
                        host.restore(original)
                        policy.onEpisodeEnd()
                        return YoutubeSwitchOutcome.CommitRefused
                    }
                    state.trialOpen = false
                    host.announce(original, candidate.id, restartVideo = true)
                    policy.onEpisodeEnd()
                    return YoutubeSwitchOutcome.SwitchedUnverified(candidate.id)
                } else {
                    // Видео не пошло вовсе (пользователь ушёл): судить не по чему, прежний выбор пользователя возвращается.
                    host.restore(original)
                    policy.onEpisodeEnd()
                    return YoutubeSwitchOutcome.Inconclusive
                }
            }
        }
        host.endWithoutWinner(original)
        val servers = starved.size + 1
        if (servers >= YoutubeSwitchDefaults.CLIENT_STALL_MIN_SERVERS) {
            // Одинаковое голодание везде: серверы не виноваты, их метки снимаются, а новый обход серверов ничего не даст,
            // пока клиент YouTube не перезапустят.
            policy.clearSlow(starved + original)
            host.notTheServer(original, starved)
            policy.onEpisodeEnd(YoutubeSwitchDefaults.CLIENT_STALL_COOLDOWN_MILLIS)
            return YoutubeSwitchOutcome.StarvesEverywhere(servers)
        }
        policy.onEpisodeEnd()
        return YoutubeSwitchOutcome.AllCandidatesSlow
    }
}
