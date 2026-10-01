package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.engines.failover.FailoverTarget
import io.github.zapretkvn.android.engines.failover.LatencyHint
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode

/**
 * Селекторная группа, в которой сейчас работает сервер, и её кандидаты.
 *
 * [currentId] — участник, выбранный в ядре прямо сейчас (по состоянию групп
 * libbox), а не default из сохранённого JSON: так вложенные селекторы и
 * неосновные группы получают те же правила, что и основная.
 */
internal data class FailoverPlan(
    val groupTag: String,
    val currentId: String,
    val currentType: String,
    val targets: List<FailoverTarget>,
)

/** Строка ядра об отказе конкретного outbound, уже классифицированная. */
internal data class PathHint(
    val outboundTag: String,
    val outboundType: String,
    val code: HysteriaFailureCode,
)

internal sealed interface PathDecision {
    /** Ничего не делать: сигнал устарел, заглушён или не касается текущего сервера. */
    data class Ignore(val reason: String? = null) : PathDecision

    /** Проверить текущий сервер короткой пробой через туннель. */
    data class Probe(val episode: Long, val serverId: String) : PathDecision

    /** Сервер подтверждённо мёртв: переключить группу на кандидата. */
    data class Switch(
        val episode: Long,
        val groupTag: String,
        val fromId: String,
        val toId: String,
    ) : PathDecision

    /** Восстановление невозможно; туннель закрывается с кодом. */
    data class Terminate(val code: HysteriaFailureCode, val message: String) : PathDecision
}

/**
 * Единственный владелец решений об автоматической смене сервера во время
 * работающей сессии.
 *
 * Прежний failover переключал сервер по одной строке лога и рвал все
 * соединения (interrupt_exist_connections): кратковременный обрыв QUIC у
 * Hysteria или тайм-аут одного соединения xray превращались в разрыв видео.
 * Здесь строка лога — только подсказка:
 *
 * 1. Подсказка про текущий сервер запускает короткую пробу через туннель.
 * 2. Сервер ответил — ложная тревога; подсказки о нём заглушаются на время.
 * 3. Не ответил — переключение на лучшего кандидата группы.
 * 4. Кандидат тоже не прошёл проверку — сбой, скорее всего, в сети целиком:
 *    селектор возвращается, автоматика замирает на [NETWORK_WIDE_HOLD_MILLIS],
 *    вместо пинг-понга между серверами.
 *
 * Не-Hysteria протоколы никогда не закрывают туннель автоматикой: их отказы
 * известны только из шумного лога. Hysteria сохраняет прежние терминальные
 * исходы (ошибки TLS/авторизации/pin и отсутствие резервного сервера).
 *
 * Класс не потокобезопасен: им владеет актор [VpnRuntime].
 */
internal class PathSupervisor(
    private val monotonicMillis: () -> Long,
) {
    private sealed interface State {
        data object Idle : State
        data class Probing(val episode: Long, val serverId: String) : State
        data class Switching(val episode: Long, val fromId: String, val toId: String) : State
    }

    private var state: State = State.Idle
    private var episode = 0L
    private var commitFenceUntil = Long.MIN_VALUE
    private var networkWideHoldUntil = Long.MIN_VALUE
    private val mutedUntil = mutableMapOf<String, Long>()
    private val deadUntil = mutableMapOf<String, Long>()
    private val switchTimes = ArrayDeque<Long>()

    val busy: Boolean get() = state != State.Idle

    /**
     * Новый жизненный цикл подключения, ручной выбор сервера или новая сеть:
     * всё, что было известно о серверах, могло устареть.
     */
    fun reset() {
        state = State.Idle
        commitFenceUntil = Long.MIN_VALUE
        networkWideHoldUntil = Long.MIN_VALUE
        mutedUntil.clear()
        deadUntil.clear()
        switchTimes.clear()
    }

    /** Эпизод прерван извне (смена сервера пользователем, остановка). */
    fun abandon() {
        state = State.Idle
    }

    /**
     * [plan] вычисляется лениво, только если подсказка прошла дешёвые фильтры:
     * при потоке строк лога профиль не разбирается на каждую строку.
     */
    fun onHint(hint: PathHint, plan: () -> FailoverPlan?): PathDecision {
        val isHysteria = hint.outboundType == HYSTERIA_TYPE
        if (hint.code in SECURITY_FAILURES) {
            if (!isHysteria) return PathDecision.Ignore("security failure of a non-terminal protocol")
            if (plan()?.currentId != hint.outboundTag) return PathDecision.Ignore("not the selected server")
            return PathDecision.Terminate(hint.code, "Сервер отклонил TLS/авторизацию: ${hint.code.name}")
        }
        if (hint.code !in SWITCHABLE_FAILURES) return PathDecision.Ignore("not a path failure")
        if (state != State.Idle) return PathDecision.Ignore("episode in progress")
        val now = monotonicMillis()
        if (now < commitFenceUntil) return PathDecision.Ignore("stale line after switch")
        if (now < networkWideHoldUntil) return PathDecision.Ignore("network-wide hold")
        if ((mutedUntil[hint.outboundTag] ?: Long.MIN_VALUE) > now) {
            return PathDecision.Ignore("server recently confirmed alive")
        }
        if (plan()?.currentId != hint.outboundTag) return PathDecision.Ignore("not the selected server")
        episode += 1
        state = State.Probing(episode, hint.outboundTag)
        return PathDecision.Probe(episode, hint.outboundTag)
    }

    /**
     * Результат пробы текущего сервера. [plan] — план на момент результата:
     * за время пробы сервер мог смениться.
     */
    fun onProbeResult(
        episode: Long,
        alive: Boolean,
        plan: FailoverPlan?,
        hints: Map<String, LatencyHint>,
    ): PathDecision {
        val probing = state as? State.Probing
        if (probing == null || probing.episode != episode) return PathDecision.Ignore("stale probe")
        val now = monotonicMillis()
        if (alive) {
            state = State.Idle
            mutedUntil[probing.serverId] = now + FALSE_ALARM_MUTE_MILLIS
            return PathDecision.Ignore("false alarm")
        }
        if (plan == null || plan.currentId != probing.serverId) {
            state = State.Idle
            return PathDecision.Ignore("server changed during probe")
        }
        return confirmedDead(plan, hints, now)
    }

    /**
     * Отказ, подтверждённый без пробы (инструментальный тест подменяет отказ
     * ядра). Эпизод открывается как после неудачной пробы.
     */
    fun onConfirmedFailure(plan: FailoverPlan, hints: Map<String, LatencyHint>): PathDecision {
        if (state != State.Idle) return PathDecision.Ignore("episode in progress")
        episode += 1
        return confirmedDead(plan, hints, monotonicMillis())
    }

    private fun confirmedDead(
        plan: FailoverPlan,
        hints: Map<String, LatencyHint>,
        now: Long,
    ): PathDecision {
        deadUntil[plan.currentId] = now + DEAD_SERVER_COOLDOWN_MILLIS
        pruneSwitches(now)
        val candidate = candidates(plan, hints).firstOrNull()
        if (candidate == null) {
            state = State.Idle
            mutedUntil[plan.currentId] = now + FALSE_ALARM_MUTE_MILLIS
            return if (plan.currentType == HYSTERIA_TYPE) {
                PathDecision.Terminate(
                    HysteriaFailureCode.NO_COMPATIBLE_FALLBACK,
                    "Совместимый резервный сервер не найден.",
                )
            } else {
                PathDecision.Ignore("no candidate")
            }
        }
        if (switchTimes.size >= MAX_SWITCHES_PER_WINDOW) {
            state = State.Idle
            networkWideHoldUntil = now + NETWORK_WIDE_HOLD_MILLIS
            return PathDecision.Ignore("switch rate limit")
        }
        state = State.Switching(episode, plan.currentId, candidate.id)
        return PathDecision.Switch(episode, plan.groupTag, plan.currentId, candidate.id)
    }

    /** Итог переключения: кандидат прошёл (или не прошёл) проверку DNS/HTTPS. */
    fun onSwitchResult(episode: Long, committed: Boolean) {
        val switching = state as? State.Switching
        if (switching == null || switching.episode != episode) return
        val now = monotonicMillis()
        state = State.Idle
        switchTimes.addLast(now)
        if (committed) {
            commitFenceUntil = now + COMMIT_FENCE_MILLIS
        } else {
            // Ни текущий, ни кандидат не прошли: вероятнее всего, сбой в сети.
            deadUntil[switching.toId] = now + DEAD_SERVER_COOLDOWN_MILLIS
            networkWideHoldUntil = now + NETWORK_WIDE_HOLD_MILLIS
        }
    }

    /**
     * Серверы, не прошедшие проверку при запуске: сессия начинается на
     * резервном, и надзор не должен вернуть её на заведомо мёртвый.
     */
    fun markDead(serverIds: Collection<String>) {
        val until = monotonicMillis() + DEAD_SERVER_COOLDOWN_MILLIS
        serverIds.forEach { deadUntil[it] = until }
    }

    /** Кандидаты для замены без подтверждённо мёртвых серверов. */
    fun candidates(plan: FailoverPlan, hints: Map<String, LatencyHint>): List<FailoverTarget> {
        val now = monotonicMillis()
        deadUntil.entries.removeAll { it.value <= now }
        return rank(plan, hints, deadUntil.keys)
    }

    private fun pruneSwitches(now: Long) {
        while (switchTimes.isNotEmpty() && now - switchTimes.first() >= SWITCH_WINDOW_MILLIS) {
            switchTimes.removeFirst()
        }
    }

    companion object {
        const val HYSTERIA_TYPE = "hysteria2"

        /**
         * Порядок замены: сначала лучший сохранённый пинг, затем порядок профиля.
         * Чистая функция — ею же пользуется запуск, у которого своего надзора нет.
         */
        fun rank(
            plan: FailoverPlan,
            hints: Map<String, LatencyHint>,
            excluded: Set<String> = emptySet(),
        ): List<FailoverTarget> = plan.targets
            .asSequence()
            .filter { it.id != plan.currentId && it.valid && !it.maintenance }
            .filter { it.id !in excluded }
            .filter { hints[it.id]?.failed != true }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<FailoverTarget>> { hints[it.value.id]?.millis ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
            .map { it.value }
            .toList()

        /** Сервер ответил на пробу: подсказки о нём не проверяются снова столько времени. */
        const val FALSE_ALARM_MUTE_MILLIS = 60_000L

        /** Строки лога покинутого сервера ещё приходят сразу после переключения. */
        const val COMMIT_FENCE_MILLIS = 2_000L

        /** Подтверждённо мёртвый сервер не рассматривается как кандидат. */
        const val DEAD_SERVER_COOLDOWN_MILLIS = 5 * 60_000L

        /** После отказа и текущего, и кандидата автоматика ждёт восстановления сети. */
        const val NETWORK_WIDE_HOLD_MILLIS = 2 * 60_000L

        /** Не больше стольких автоматических переключений за окно: против пинг-понга. */
        const val MAX_SWITCHES_PER_WINDOW = 3
        const val SWITCH_WINDOW_MILLIS = 15 * 60_000L

        val SWITCHABLE_FAILURES = setOf(
            HysteriaFailureCode.TARGET_NETWORK_TIMEOUT,
            HysteriaFailureCode.TARGET_CONNECTION_REFUSED,
            HysteriaFailureCode.LOCAL_PROCESS_EXITED,
            HysteriaFailureCode.LOCAL_RELAY_DIED,
            HysteriaFailureCode.LOCAL_RELAY_NOT_READY,
        )

        val SECURITY_FAILURES = setOf(
            HysteriaFailureCode.TARGET_TLS_REJECTED,
            HysteriaFailureCode.TARGET_TLS_INTERNAL,
            HysteriaFailureCode.TARGET_TLS_UNKNOWN_AUTHORITY,
            HysteriaFailureCode.TARGET_PIN_MISMATCH,
            HysteriaFailureCode.TARGET_AUTH_REJECTED,
            HysteriaFailureCode.TARGET_OBFS_REJECTED,
        )
    }
}
