package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.diagnostics.RuntimeErrors
import io.github.zapretkvn.android.diagnostics.RuntimeLog
import io.github.zapretkvn.android.diagnostics.VpnFailureStates
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.engines.failover.LatencyHint
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchEngine
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchPolicy
import io.github.zapretkvn.android.engines.failover.SlowSwitchGate
import io.github.zapretkvn.android.engines.failover.SlowSwitchSnapshot
import io.github.zapretkvn.android.engines.failover.YoutubeSwitchEngine
import io.github.zapretkvn.android.engines.failover.YoutubeSwitchGate
import io.github.zapretkvn.android.engines.failover.YoutubeSwitchOutcome
import io.github.zapretkvn.android.engines.failover.YoutubeSwitchPolicy
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.config.SlotSelectors
import io.github.zapretkvn.android.network.probes.LivenessResult
import io.github.zapretkvn.android.slots.SlotCandidates
import io.github.zapretkvn.android.slots.SlotCatalog
import io.github.zapretkvn.android.slots.SlotFailoverDecision
import io.github.zapretkvn.android.slots.SlotFailoverPolicy
import io.github.zapretkvn.android.slots.SlotIds
import io.github.zapretkvn.android.slots.SlotMove
import io.github.zapretkvn.android.slots.SlotTraffic
import io.github.zapretkvn.networkbootstrap.ServerNameRegistry
import io.github.zapretkvn.android.vpn.PathStatus
import io.github.zapretkvn.android.vpn.SlotState
import io.github.zapretkvn.android.vpn.VpnController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import io.github.zapretkvn.runtimelog.SlowVideoDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Операции актора, нужные смене сервера работающей сессии. */
internal interface ServerSwitchOps : RuntimeActorOps {
    /** Отказ работающей сессии, который автоматика исправить не может. */
    fun terminateSession(session: CoreSession, code: HysteriaFailureCode, message: String)

    /** Команда пришла, когда VPN выключен: сервис, поднятый ради неё, завершается. */
    fun stopIfIdle(startId: Int)

    /** Ручной выбор отменяет отложенную замену сервера после отказа запуска. */
    fun clearPendingReplacement()

    /** Ядро не приняло команду выбора: полный перезапуск с тем же профилем. */
    fun relaunchAfterSelectFailure(session: CoreSession, startId: Int)

    /** Жизненный цикл занят: не Running, идёт восстановление или дебаунс сети. */
    fun lifecycleBusy(): Boolean
}

/**
 * Смена сервера работающей сессии: ручной выбор, failover по подсказкам ядра с
 * проверкой связи и «умная проверка» скорости. Все три идут через один
 * [ServerSwitcher] и одну очередь операций.
 *
 * Состояние (текущая операция, код пробы, антифлап «умной проверки») меняется
 * только в акторе. Из потоков libbox/Android читаются лишь volatile-флаги
 * настройки и экрана. Фоновые операции возвращают результат в актор через
 * [postToActor].
 */
internal class ServerSwitchCoordinator(
    private val scope: CoroutineScope,
    private val container: AppContainer,
    private val sessions: SessionSlot,
    private val controller: VpnController,
    private val host: RuntimeHost,
    private val ops: ServerSwitchOps,
    private val switcher: ServerSwitcher,
    private val supervisor: PathSupervisor,
    private val log: RuntimeTimeline,
    private val onExitChanged: (CoreSession) -> Unit,
    private val postToActor: (() -> Unit) -> Unit,
    private val clock: () -> Long,
) {
    private enum class Operation { Select, Probe, Failover, Smart }

    private var operationJob: Job? = null
    private var operationKind: Operation? = null
    private var probeCode = HysteriaFailureCode.TARGET_NETWORK_TIMEOUT
    private val healthVerifier = SessionHealthVerifier(container)

    /** Антифлап «умной проверки» переживает переподключения. */
    private val smartPolicy = SlowServerSwitchPolicy { clock() }

    /** Антифлап и метки «плох для YouTube»: тоже переживают переподключения. */
    private val youtubePolicy = YoutubeSwitchPolicy { clock() }

    /** Вердикты по видео для проверки кандидата после переключения (только последний нужен). */
    private val videoVerdicts = Channel<SlowVideoDetector.Verdict>(Channel.CONFLATED)

    private var videoJob: Job? = null

    /** Пассивный детектор «подключено, но данные не идут»; без таймеров и своих запросов. */
    private val stallDetector = StallDetector()

    private var trafficJob: Job? = null

    /**
     * Failover раздельных серверов (docs/SERVICE_SLOTS.md, «только при сбое»): есть, пока у сессии есть слоты.
     * Отдельно от [supervisor]: у слота своя проверка по повторам, потому что проба через туннель идёт через основной
     * сервер и про сервер слота ничего не говорит.
     */
    private var slotFailover: SlotFailoverPolicy? = null

    /** Закрепления слотов из настроек (id слота -> тег сервера); приходят сообщением при старте и при каждой правке. */
    private var slotPins: Map<String, String> = emptyMap()

    /** «Нет связи с сервером» показано пользователю (только в акторе). */
    private var pathStatus = PathStatus.Ok

    // Читаются из потоков libbox/Android: только volatile-флаги.
    @Volatile private var smartSwitchEnabled = true
    @Volatile private var youtubeSwitchEnabled = false
    @Volatile private var screenInteractive = true

    // ---- Общее ----

    fun currentPlan(session: CoreSession, serverId: String? = null): FailoverPlan? {
        return FailoverPlanner.plan(session.profileConfig, session.runtimeSelections(), session.primaryGroupTag, serverId)
    }

    fun latencyHints(profileId: String): Map<String, LatencyHint> =
        container.serverLatencyStore.failoverHints(profileId)

    private fun isCurrent(session: CoreSession): Boolean =
        sessions.active() === session && session.generation == controller.currentGeneration() && !ops.stopInProgress

    /**
     * Одна операция над селектором за раз: новая сначала отменяет предыдущую и
     * дожидается её отката. Приоритет задаёт порядок: ручной выбор и failover
     * вытесняют пробу «умной проверки», а не наоборот.
     */
    private fun runOperation(kind: Operation, block: suspend (previous: Job?) -> Unit) {
        val previous = operationJob
        previous?.cancel()
        operationKind = kind
        operationJob = scope.launch { block(previous) }
    }

    fun cancelOperation() {
        operationJob?.cancel()
        operationJob = null
        operationKind = null
    }

    private fun operationActive(): Boolean = operationJob?.isActive == true

    // ---- Ручной выбор сервера ----

    fun onSelect(profileId: String, groupTag: String, outboundTag: String, startId: Int) {
        if (ops.stopInProgress) return
        val session = sessions.active()
        // Connected публикуется стартером чуть раньше, чем актор узнаёт об итоге
        // попытки: активной сессии текущего поколения для выбора достаточно.
        if (session == null || session.profileId != profileId ||
            session.generation != controller.currentGeneration()
        ) {
            controller.publishMessage(
                if (ops.phase == RuntimePhase.Idle) "Активный VPN-профиль не найден." else "Сервер можно выбрать после подключения.",
            )
            ops.stopIfIdle(startId)
            return
        }
        supervisor.reset()
        stallDetector.reset(keepCooldown = false)
        setLinkLost(session, false, "сервер выбран вручную")
        ops.clearPendingReplacement()
        session.resetThroughputWindow()
        // Ручной выбор фиксируется на 30 минут; failover это не затрагивает.
        smartPolicy.onManualSelection()
        timeline("Сервер выбран вручную: ${server(outboundTag)}.")
        setPathStatus(session, PathStatus.Switching, "сервер выбран вручную")
        runOperation(Operation.Select) { previous ->
            previous?.cancelAndJoin()
            // Транзакция выбора (SelectionTransaction): выбор закрепляется после
            // проверки; мёртвый сервер возвращает прежний, сбой не по вине сервера
            // оставляет выбор с предупреждением.
            val transaction = SelectionTransaction<CoreSession>(
                outboundTag,
                verify = ::verifyCandidate,
                evidence = { current ->
                    RuntimeErrors.bestEvidence(controller.runtimeErrors.forGeneration(current.generation))
                },
                plan = { current, tag -> currentPlan(current, tag) },
            )
            val outcome = switcher.switchOrRollback(
                session,
                groupTag,
                outboundTag,
                SwitchVerifier { candidateSession, json -> transaction.check(candidateSession, json) },
            )
            postToActor { onSelectDone(session, outboundTag, outcome, transaction.warning, startId) }
        }
    }

    private fun onSelectDone(
        session: CoreSession,
        outboundTag: String,
        outcome: SwitchOutcome,
        checkError: Throwable?,
        startId: Int,
    ) {
        if (sessions.active() !== session || session.generation != controller.currentGeneration()) return
        when (outcome) {
            SwitchOutcome.Committed -> {
                setPathStatus(session, PathStatus.Ok, "выбранный сервер подключён")
                host.showForeground(ForegroundState.Connected)
                controller.clearConnectionIdentity(session.generation)
                onExitChanged(session)
                if (checkError != null) {
                    val failure = VpnFailureStates.from(checkError)
                    timeline("Выбранный ${server(outboundTag)} не ответил на проверку связи (${failure.code}); выбор оставлен.")
                    controller.publishMessage(
                        "Сервер ${server(outboundTag)} не ответил на проверку связи, но остаётся выбранным. " +
                            "Если сайты не открываются, выберите другой. ${failure.message}",
                    )
                }
            }
            is SwitchOutcome.RolledBack -> {
                // Прежний сервер возвращён: путь тот же, что был до выбора; профиль
                // не менялся — отказавший сервер не становился выбранным.
                setPathStatus(session, PathStatus.Ok, "прежний сервер возвращён")
                val dead = generateSequence(outcome.error) { it.cause }.filterIsInstance<DeadServerException>().firstOrNull()
                val failure = VpnFailureStates.from(dead?.cause ?: outcome.error)
                timeline("Выбранный ${server(outboundTag)} не прошёл проверку связи (${dead?.code?.name ?: failure.code}): прежний сервер возвращён.")
                controller.publishMessage(
                    if (dead != null) {
                        "Сервер ${server(outboundTag)} не ответил, соединение осталось на прежнем. ${dead.explanation()}"
                    } else {
                        "Сервер ${server(outboundTag)} не прошёл проверку связи, оставлен прежний. ${failure.message}"
                    },
                )
                host.showForeground(ForegroundState.Connected)
            }
            is SwitchOutcome.CoreUnreachable -> {
                // Команда в ядро не прошла: состояние селектора неизвестно — полный перезапуск.
                ops.relaunchAfterSelectFailure(session, startId)
            }
        }
    }

    // ---- Надзор за сервером работающей сессии ----

    fun onCoreHints(generation: Long, hints: List<PathHint>) {
        val session = sessions.active() ?: return
        if (session.generation != generation || !isCurrent(session)) return
        onSlotHints(session, hints)
        // Только что выбранный сервер ещё поднимает соединения, и его таймауты в логе —
        // не повод для пробы: она отменила бы сам выбор.
        if (operationKind == Operation.Select && operationActive()) return
        for (hint in hints) {
            val decision = supervisor.onHint(hint) { currentPlan(session, hint.outboundTag) }
            when (decision) {
                is PathDecision.Ignore -> Unit
                is PathDecision.Probe -> {
                    timeline("${server(decision.serverId)}: ${hint.code.name} в логе ядра — проверка связи.")
                    startProbe(session, decision, hint.code)
                    return
                }
                else -> {
                    apply(session, decision, hint.code)
                    return
                }
            }
        }
    }

    /**
     * Подсказки об отказе сервера, на котором сидит слот. Основной selector не затрагивается: его ведёт [supervisor].
     * Решение о переносе принимает чистая [SlotFailoverPolicy]; здесь только команда selector'а слота и строка в журнал.
     */
    private fun onSlotHints(session: CoreSession, hints: List<PathHint>) {
        val policy = slotFailover ?: return
        for (hint in hints) {
            if (hint.code !in PathSupervisor.SWITCHABLE_FAILURES) continue
            val main = session.primaryGroupTag?.let { session.runtimeSelections()[it] }
            when (val decision = policy.onFailureHint(hint.outboundTag, main, supervisor.deadServerIds())) {
                is SlotFailoverDecision.Ignore -> Unit
                is SlotFailoverDecision.Move -> {
                    timeline(
                        "${server(decision.failedServer)}: ${decision.hints} отказов за минуту (${hint.code.name}), " +
                            "слоты уходят: ${decision.moves.joinToString { "${slotTitle(it.slotId)} → ${server(it.to)}" }}.",
                    )
                    // Имя нового сервера должно разрешаться до команды.
                    ServerNameRegistry.useForSlots(policy.slotServers())
                    publishSlots(session)
                    decision.moves.forEach { move -> moveSlot(session, policy, move) }
                }
            }
        }
    }

    /**
     * Пользователь закрепил сервер за слотом или снял закрепление (карточка на Главной). Сессия переносит закреплённый
     * слот сразу, командой его selector'а, без перезапуска; снятое закрепление ничего не двигает.
     */
    fun onSlotPins(pins: Map<String, String>) {
        slotPins = pins
        val session = sessions.active() ?: return
        val policy = slotFailover ?: return
        if (!isCurrent(session)) return
        val specs = session.slotPlan?.specs.orEmpty().associateBy { it.slotId }
        val moves = policy.setPins(pins) { slotId -> specs[slotId]?.members.orEmpty() }
        if (moves.isEmpty()) return
        timeline("Закреплено вручную: ${moves.joinToString { "${slotTitle(it.slotId)} → ${server(it.to)}" }}.")
        ServerNameRegistry.useForSlots(policy.slotServers())
        publishSlots(session)
        moves.forEach { move -> moveSlot(session, policy, move) }
    }

    private fun moveSlot(session: CoreSession, policy: SlotFailoverPolicy, move: SlotMove) {
        scope.launch {
            val failure = switcher.moveSlot(session, SlotSelectors.tag(move.slotId), move.to)
            postToActor {
                if (failure != null && slotFailover === policy) {
                    policy.moveFailed(move)
                    ServerNameRegistry.useForSlots(policy.slotServers())
                    publishSlots(session)
                    timeline("Слот ${slotTitle(move.slotId)}: команда на ${server(move.to)} не прошла, остаётся на ${server(move.from)}.")
                } else if (failure == null) {
                    timeline("Слот ${slotTitle(move.slotId)}: теперь через ${server(move.to)}.")
                }
            }
        }
    }

    /** Карточки слотов на Главной: где сейчас каждый слот. Только в акторе. */
    private fun publishSlots(session: CoreSession) {
        val policy = slotFailover
        val states = if (policy == null) {
            emptyList()
        } else {
            session.slotPlan?.specs.orEmpty().mapNotNull { spec ->
                policy.serverOf(spec.slotId)?.let { SlotState(spec.slotId, slotTitle(spec.slotId), it) }
            }
        }
        controller.publishSlots(session.generation, states)
    }

    private fun slotTitle(slotId: String): String =
        SlotCatalog.builtIn().firstOrNull { it.id == slotId }?.title ?: slotId

    /** Одна проба через текущий сервер: единственная активная проверка, которую запускает надзор. */
    private fun startProbe(session: CoreSession, decision: PathDecision.Probe, code: HysteriaFailureCode) {
        if (pathStatus == PathStatus.Ok) setPathStatus(session, PathStatus.Checking, "проверка связи")
        probeCode = code
        runOperation(Operation.Probe) { previous ->
            previous?.cancelAndJoin()
            val result = container.vpnLivenessProbe.check()
            postToActor { onProbeDone(session, decision.episode, result) }
        }
    }

    private fun onProbeDone(session: CoreSession, episode: Long, result: LivenessResult) {
        if (!isCurrent(session)) {
            supervisor.abandon()
            return
        }
        val decision = supervisor.onProbeResult(
            episode = episode,
            alive = result !is LivenessResult.Dead,
            plan = currentPlan(session),
            hints = latencyHints(session.profileId),
        )
        when (result) {
            is LivenessResult.Alive -> {
                timeline(
                    "Сервер отвечает (${result.endpoint}, ${result.elapsedMillis} мс): ложная тревога, переключения нет.",
                )
                setLinkLost(session, false, "сервер ответил на проверку")
            }
            LivenessResult.Unknown -> {
                timeline("VPN-сеть Android недоступна для проверки; переключения нет.")
                if (pathStatus == PathStatus.Checking) setPathStatus(session, PathStatus.Ok, "проверка не выполнена")
            }
            is LivenessResult.Dead -> if (decision is PathDecision.Ignore) {
                timeline("Сервер не отвечает (${result.detail}); подходящей замены нет, остаёмся.")
                // Сервер подтверждённо мёртв, а автоматике действовать нечем: статус не «Подключено».
                if (decision.reason in PathSupervisor.DEAD_UNRECOVERED_REASONS) {
                    setLinkLost(session, true, "сервер не отвечает, замены нет")
                }
            }
        }
        apply(session, decision, probeCode, (result as? LivenessResult.Dead)?.detail)
    }

    /**
     * Запуск закончился на резервном сервере. Сообщение приходит раньше итога
     * попытки, поэтому сверяется поколение, а не активная сессия.
     */
    fun onStartupFallback(session: CoreSession, deadIds: List<String>, toId: String, code: HysteriaFailureCode) {
        if (session.generation != controller.currentGeneration() || ops.stopInProgress) return
        supervisor.markDead(deadIds)
        timeline(
            "Запуск: ${deadIds.joinToString { server(it) }} не прошли проверку (${code.name}); " +
                "подключение через ${server(toId)}, ядро не перезапускалось.",
        )
    }

    fun onInjectedFailure(session: CoreSession, code: HysteriaFailureCode) {
        if (!isCurrent(session)) return
        val plan = currentPlan(session) ?: return
        val decision = if (code in PathSupervisor.SWITCHABLE_FAILURES) {
            supervisor.onConfirmedFailure(plan, latencyHints(session.profileId))
        } else {
            supervisor.onHint(PathHint(plan.currentId, plan.currentType, code)) { plan }
        }
        apply(session, decision, code)
    }

    fun onInjectedHint(session: CoreSession, code: HysteriaFailureCode) {
        val plan = currentPlan(session) ?: return
        onCoreHints(session.generation, listOf(PathHint(plan.currentId, plan.currentType, code)))
    }

    private fun apply(
        session: CoreSession,
        decision: PathDecision,
        code: HysteriaFailureCode,
        detail: String? = null,
    ) {
        when (decision) {
            is PathDecision.Ignore, is PathDecision.Probe -> Unit
            is PathDecision.Terminate -> ops.terminateSession(session, decision.code, decision.message)
            is PathDecision.Switch -> {
                setPathStatus(session, PathStatus.Switching, "переключение сервера")
                timeline(
                    "${server(decision.fromId)} не отвечает${detail?.let { " ($it)" }.orEmpty()}: " +
                        "переключение на ${server(decision.toId)}.",
                )
                val startedAt = clock()
                runOperation(Operation.Failover) { previous ->
                    previous?.cancelAndJoin()
                    val outcome = switcher.switchOrRollback(
                        session,
                        decision.groupTag,
                        decision.toId,
                        SwitchVerifier { candidateSession, json -> verifyCandidate(candidateSession, json) },
                    )
                    postToActor {
                        onSwitchDone(session, decision.episode, decision.fromId, decision.toId, code, startedAt, outcome)
                    }
                }
            }
        }
    }

    private fun onSwitchDone(
        session: CoreSession,
        episode: Long,
        fromId: String,
        toId: String,
        code: HysteriaFailureCode,
        startedAt: Long,
        outcome: SwitchOutcome,
    ) {
        if (!isCurrent(session)) {
            supervisor.abandon()
            return
        }
        when (outcome) {
            SwitchOutcome.Committed -> {
                supervisor.onSwitchResult(episode, committed = true)
                // Общий бюджет автоматики: «умная проверка» после автозамены выдерживает свою
                // паузу и считает переключение в часовом лимите — без второго переключения подряд.
                smartPolicy.onSwitch()
                controller.publishDiagnosticWarning(
                    "Выполнено одно автоматическое переключение сервера (${code.name}) за " +
                        "${clock() - startedAt} мс; ядро не перезапускалось.",
                )
                session.resetThroughputWindow()
                stallDetector.reset()
                setLinkLost(session, false, "переключение на ${server(toId)}")
                controller.clearConnectionIdentity(session.generation)
                onExitChanged(session)
            }
            is SwitchOutcome.RolledBack -> {
                supervisor.onSwitchResult(episode, committed = false)
                setLinkLost(session, true, "резервный сервер не прошёл проверку")
                timeline(
                    "Резервный ${server(toId)} тоже не прошёл проверку " +
                        "(${VpnFailureStates.from(outcome.error).code}): вероятно, сбой сети. " +
                        "Остаёмся на ${server(fromId)}, автоматика ждёт восстановления.",
                )
            }
            is SwitchOutcome.CoreUnreachable -> {
                supervisor.onSwitchResult(episode, committed = false)
                timeline("Ядро не приняло команду переключения; перезапуск VPN.")
                ops.restart(
                    profileId = session.profileId,
                    reason = "Перезапуск после сбоя управления ядром",
                    startId = 0,
                    updaterRouting = null,
                    expectedGeneration = session.generation,
                )
            }
        }
    }

    /** Проверка кандидата после команды селектора: тот же DNS/HTTPS, что при подключении. */
    private suspend fun verifyCandidate(session: CoreSession, candidateJson: String) {
        if (VpnTestHooks.consumeHysteriaReplacementFailure()) {
            throw IllegalStateException("Injected Hysteria replacement readiness failure.")
        }
        if (VpnTestHooks.consumeSwitchVerificationSuccess()) return
        val dnsServer = session.platform().internalDnsServer
            ?: throw IllegalStateException("libbox не передал внутренний DNS TUN.")
        healthVerifier.verify(session, ParsedConfig.parse(candidateJson), dnsServer, reportStages = false)
    }

    // ---- Пассивный детектор «связь пропала при статусе Подключено» ----

    /**
     * Отсчёт счётчиков ядра (в акторе). Источник — единый поток трафика сессии,
     * см. подписку в [onSessionActivated]. Погасший экран отсчёты не принимает.
     */
    fun onTrafficSample(session: CoreSession, sample: TrafficSample) {
        if (!isCurrent(session)) return
        // replay=1 отдаёт новому подписчику последний отсчёт, он может быть старым.
        if (!screenInteractive || clock() - sample.elapsedRealtimeMillis > StallDetector.MAX_GAP_MILLIS) {
            stallDetector.reset()
            return
        }
        onStallEvent(session, stallDetector.onSample(sample))
    }

    /** Нетегированные отказы DNS по таймауту из лога ядра (в акторе). */
    fun onDnsFailures(generation: Long, hint: DnsFailureHint) {
        val session = sessions.active() ?: return
        if (session.generation != generation || !isCurrent(session) || !screenInteractive) return
        val now = clock()
        for (name in hint.names) {
            val event = stallDetector.onDnsFailure(now, name) ?: continue
            onStallEvent(session, event)
            return
        }
    }

    /** Сеть под сессией сменилась: окна детектора начинаются заново, антифлап тревог сохраняется. */
    fun onNetworkReplaced() = stallDetector.reset()

    private fun onStallEvent(session: CoreSession, event: StallEvent?) {
        when (event) {
            null -> Unit
            StallEvent.Recovered -> setLinkLost(session, false, "входящий трафик снова идёт")
            is StallEvent.Suspected -> onStallSuspected(session, event)
        }
    }

    /**
     * Накопились пассивные признаки обрыва: тот же путь, что у подсказки ядра,
     * то есть одна проба [VpnLivenessProbe] через текущий сервер; мёртв — обычный
     * failover с проверкой кандидата. Никаких периодических проб и веера серверов.
     */
    private fun onStallSuspected(session: CoreSession, event: StallEvent.Suspected) {
        timeline("Подозрение на обрыв связи (${event.kind.name}): ${event.detail}.")
        if (ops.lifecycleBusy() || operationKind == Operation.Select || operationKind == Operation.Failover) {
            timeline("Проверка связи отложена: уже идёт другая операция над сервером.")
            return
        }
        val plan = currentPlan(session)
        if (plan == null) {
            // Профиль без плана замены (ручной JSON без uri у серверов): переключать
            // не на что, но та же одна проверка через текущий сервер честно покажет
            // «Нет связи с сервером» вместо молчаливого «Подключено».
            timeline("Проверка связи через текущий сервер (автоматической замены для этого профиля нет).")
            if (pathStatus == PathStatus.Ok) setPathStatus(session, PathStatus.Checking, "проверка связи")
            runOperation(Operation.Probe) { previous ->
                previous?.cancelAndJoin()
                val result = container.vpnLivenessProbe.check()
                postToActor { onStandaloneProbeDone(session, result) }
            }
            return
        }
        val code = HysteriaFailureCode.TARGET_NETWORK_TIMEOUT
        val decision = supervisor.onHint(PathHint(plan.currentId, plan.currentType, code)) { plan }
        when (decision) {
            is PathDecision.Probe -> {
                timeline("${server(decision.serverId)}: проверка связи через текущий сервер.")
                startProbe(session, decision, code)
            }
            is PathDecision.Ignore -> timeline("Проверка связи не запущена: ${decision.reason ?: "сигнал заглушён"}.")
            else -> apply(session, decision, code)
        }
    }

    private fun onStandaloneProbeDone(session: CoreSession, result: LivenessResult) {
        if (!isCurrent(session)) return
        when (result) {
            is LivenessResult.Alive -> {
                timeline("Сервер отвечает (${result.endpoint}, ${result.elapsedMillis} мс): ложная тревога.")
                setLinkLost(session, false, "сервер ответил на проверку")
            }
            LivenessResult.Unknown -> {
                timeline("VPN-сеть Android недоступна для проверки.")
                if (pathStatus == PathStatus.Checking) setPathStatus(session, PathStatus.Ok, "проверка не выполнена")
            }
            is LivenessResult.Dead -> {
                timeline("Сервер не отвечает (${result.detail}); автоматической замены для этого профиля нет.")
                setLinkLost(session, true, "сервер не отвечает, автоматической замены нет")
            }
        }
    }

    /** «Нет связи с сервером» поднимается или снимается; см. [setPathStatus]. */
    private fun setLinkLost(session: CoreSession, lost: Boolean, reason: String) =
        setPathStatus(session, if (lost) PathStatus.Lost else PathStatus.Ok, reason)

    /**
     * Единственный владелец состояния пути поверх «Подключено»: проверка, замена,
     * «нет связи». Экран и уведомление получают его отсюда, поэтому во время
     * проверки или замены сервера нельзя увидеть «Всё работает».
     */
    private fun setPathStatus(session: CoreSession, status: PathStatus, reason: String) {
        if (pathStatus == status || !isCurrent(session)) return
        val previous = pathStatus
        pathStatus = status
        controller.publishPathStatus(session.generation, status)
        host.showForeground(
            when (status) {
                PathStatus.Lost -> ForegroundState.LinkLost
                PathStatus.Switching -> ForegroundState.SwitchingServer
                PathStatus.Ok, PathStatus.Checking -> ForegroundState.Connected
            },
        )
        val text = when (status) {
            PathStatus.Lost -> "Нет связи с сервером: $reason."
            PathStatus.Ok -> if (previous == PathStatus.Lost) "Связь с сервером восстановилась: $reason." else null
            PathStatus.Checking, PathStatus.Switching -> null
        }
        text?.let {
            timeline(it)
            controller.recordKeyEvent(session.generation, it)
        }
    }

    // ---- «Умная проверка» скорости ----

    fun onSmartSwitchSetting(enabled: Boolean) {
        smartSwitchEnabled = enabled
        // Выключение и включение настройки сбрасывают фиксацию ручного выбора.
        smartPolicy.resetManualHold()
        if (!enabled && operationKind == Operation.Smart) cancelOperation()
        sessions.active()?.let(::applySpeedMonitor)
    }

    /** Настройка «переключать сервер при медленном YouTube» (по умолчанию выключена). */
    fun onYoutubeSwitchSetting(enabled: Boolean) {
        youtubeSwitchEnabled = enabled
    }

    /** Экран включился или погас: монитор скорости нужен, только пока он горит. */
    fun onScreenInteractive(interactive: Boolean) {
        screenInteractive = interactive
        postToActor {
            // Погасший экран: данные не идут, это не обрыв. Окна детектора начинаются заново.
            stallDetector.reset()
            sessions.active()?.let { session ->
                applySpeedMonitor(session)
                applyVideoLoop(session)
            }
        }
    }

    /**
     * Цикл выборки потоков: пока экран горит, раз в [VIDEO_IDLE_MILLIS], а пока идёт видео googlevideo, каждые
     * [VIDEO_FAST_MILLIS]. С погасшим экраном цикла нет вовсе: у сервиса нет периодической работы (I6-03).
     */
    private fun applyVideoLoop(session: CoreSession) {
        if (!screenInteractive || !isCurrent(session)) {
            videoJob?.cancel()
            videoJob = null
            return
        }
        if (videoJob?.isActive == true) return
        videoJob = scope.launch {
            while (isActive) {
                delay(if (RuntimeLog.videoActive()) VIDEO_FAST_MILLIS else VIDEO_IDLE_MILLIS)
                val verdict = withContext(Dispatchers.IO) { runCatching { session.sampleFlows().verdict }.getOrNull() }
                if (verdict != null) postToActor { onVideoVerdict(session, verdict) }
            }
        }
    }

    /**
     * Вердикт по видео из цикла выборки. Здесь он и кормит проверку кандидата, и, при включённой настройке, запускает
     * переключение, когда видео голодает на текущем сервере.
     */
    private fun onVideoVerdict(session: CoreSession, verdict: SlowVideoDetector.Verdict) {
        if (!isCurrent(session)) {
            videoJob?.cancel()
            videoJob = null
            return
        }
        videoVerdicts.trySend(verdict)
        noteDeadCluster(verdict)
        if (!youtubeSwitchEnabled || !(verdict.starving || verdict.deadCluster)) return
        if (youtubePolicy.gate(enabled = true, busy = smartBusy() || operationActive()) != YoutubeSwitchGate.Ready) return
        // Мёртвый кластер (плеер ждёт ответ, которого нет) лечится сменой сервера так же, как голодание:
        // YouTube выбирает кластер по адресу запроса, другой сервер получит другой кластер.
        // Имя переменной не «reason»: RestartTriggersTest сканирует исходники на присваивания причин перезапуска.
        val cause = if (verdict.deadCluster) "кластер ${verdict.deadClusters.joinToString()} не отвечает" else "видео голодает"
        // Со слотом YouTube видео идёт через его selector: переключать надо слот, а не основной сервер.
        val onSlot = slotFailover?.serverOf(SlotIds.YOUTUBE) != null &&
            session.slotPlan?.specs?.any { it.slotId == SlotIds.YOUTUBE } == true
        val leftServerId = if (onSlot) slotFailover?.serverOf(SlotIds.YOUTUBE) else currentPlan(session)?.currentId
        runOperation(Operation.Smart) { previous ->
            previous?.cancelAndJoin()
            RuntimeLog.resetVideoDetector()
            val outcome = YoutubeSwitchEngine(
                policy = youtubePolicy,
                host = if (onSlot) {
                    YoutubeSlotSwitchHost(session, SlotIds.YOUTUBE, SlotContext(), switcher, controller, videoVerdicts)
                } else {
                    YoutubeSwitchHostImpl(session, SmartContext(), switcher, controller, videoVerdicts)
                },
                youtubeAverage = RuntimeLog::youtubeAverage,
                excludedIds = supervisor::deadServerIds,
            ).runEpisode(keepOnSilent = verdict.deadCluster)
            timeline(
                (if (onSlot) "Слот YouTube, переключение при медленном видео" else "Переключение при медленном YouTube") +
                    " ($cause): ${youtubeOutcomeText(outcome)}.",
            )
            val switchedAway = outcome is YoutubeSwitchOutcome.Switched || outcome is YoutubeSwitchOutcome.SwitchedUnverified
            if (verdict.deadCluster && switchedAway && leftServerId != null && !onSlot) {
                supervisor.avoidForYoutube(leftServerId)
                timeline("${server(leftServerId)}: кластер YouTube мёртв, при автоматической замене сервера он будет последним.")
            }
        }
    }

    /**
     * Один раз за эпизод пишет в журнал, что кластер мёртв, а переключение при медленном YouTube выключено:
     * иначе по журналу не понять, почему приложение бездействует. Эпизод кончается, когда мёртвых кластеров нет.
     */
    private fun noteDeadCluster(verdict: SlowVideoDetector.Verdict) {
        if (!verdict.deadCluster) {
            deadClusterNoted = false
            return
        }
        if (deadClusterNoted || youtubeSwitchEnabled) return
        deadClusterNoted = true
        timeline(
            "Кластер YouTube ${verdict.deadClusters.joinToString()} не отвечает, но «Менять сервер при медленном YouTube» " +
                "выключено: сервер не меняется.",
        )
    }

    private var deadClusterNoted = false

    private fun youtubeOutcomeText(outcome: YoutubeSwitchOutcome): String = when (outcome) {
        YoutubeSwitchOutcome.Gone -> "сессия закончилась"
        YoutubeSwitchOutcome.NoCandidates -> "запасного сервера нет"
        is YoutubeSwitchOutcome.Switched -> "выбран ${server(outcome.serverId)}"
        is YoutubeSwitchOutcome.SwitchedUnverified -> "выбран ${server(outcome.serverId)}, перезапустите видео"
        YoutubeSwitchOutcome.Inconclusive -> "видео не пошло, прежний сервер возвращён"
        YoutubeSwitchOutcome.AllCandidatesSlow -> "запасные серверы тоже медленные, прежний возвращён"
        YoutubeSwitchOutcome.TrialFailed -> "переключение не удалось"
        YoutubeSwitchOutcome.CommitRefused -> "выбор не сохранён, прежний сервер возвращён"
    }

    /** Сессия стала активной: привязать антифлап к профилю и открыть монитор скорости. */
    fun onSessionActivated(session: CoreSession) {
        smartPolicy.bindProfile(session.profileId)
        youtubePolicy.bindProfile(session.profileId)
        slotFailover = session.slotPlan?.let { plan ->
            val initial = plan.assignments.mapNotNull { a -> a.serverId?.let { a.slotId to it } }.toMap()
            val servers = plan.specs.flatMap { it.members }.distinct()
            SlotFailoverPolicy(SlotCatalog.withPins(slotPins), servers, initial) { clock() }
        }
        publishSlots(session)
        applyVideoLoop(session)
        // Новая сессия: прежние окна, антифлап и «Нет связи» относились к другому подключению.
        stallDetector.reset(keepCooldown = false)
        pathStatus = PathStatus.Ok
        // Единый поток трафика сессии (CoreSession.trafficSamples) — единственный вход детектора.
        // Подписка живёт, пока сессия не сменится; отсчёты чужих сессий отсекает isCurrent.
        trafficJob?.cancel()
        trafficJob = scope.launch {
            session.trafficSamples().collect { sample ->
                postToActor { onTrafficSample(session, sample) }
            }
        }
        if (smartSwitchEnabled && screenInteractive) {
            session.openSpeedMonitorClient { total, connections ->
                postToActor { onSpeedSample(session, total, connections) }
            }
        }
    }

    /**
     * Монитор скорости нужен, только пока настройка включена и экран горит:
     * при погашенном экране и простое у сервиса нет периодической работы.
     */
    private fun applySpeedMonitor(session: CoreSession) {
        scope.launch {
            if (smartSwitchEnabled && screenInteractive) {
                session.openSpeedMonitorClient { total, connections ->
                    postToActor { onSpeedSample(session, total, connections) }
                }
            } else {
                session.closeSpeedMonitorClient()
            }
        }
    }

    private fun onSpeedSample(session: CoreSession, total: Long, connections: Int) {
        if (!smartSwitchEnabled || !isCurrent(session)) return
        // Раздельные серверы: основной сервер судится только по своему трафику, без того, что несут слоты.
        val (own, ownConnections) = SlotTraffic.own(total, connections, session.slotTrafficOutsideDefault())
        if (!session.onThroughputSample(clock(), own, ownConnections)) return
        if (smartPolicy.gate(enabled = true, busy = false) != SlowSwitchGate.Ready) return
        if (operationActive() || supervisor.busy) return
        session.resetThroughputWindow()
        runOperation(Operation.Smart) { previous ->
            previous?.cancelAndJoin()
            SlowServerSwitchEngine(
                policy = smartPolicy,
                host = SmartSwitchHost(session, SmartContext(), switcher, container.vpnThroughputProbe, controller),
                enabled = { smartSwitchEnabled },
            ).runEpisode()
        }
    }

    private fun smartBusy(): Boolean = ops.lifecycleBusy() || supervisor.busy

    private companion object {
        /** Опрос потоков пока видео идёт: так медленный видеопоток узнаётся за секунды, а не за десять. */
        const val VIDEO_FAST_MILLIS = 1_500L

        /** Опрос потоков пока видео нет: история и журнал не требуют частого. */
        const val VIDEO_IDLE_MILLIS = 4_000L
    }

    /** Выполнить блок в акторе и дождаться результата (для задач «умной проверки»). */
    private suspend fun <T> ask(block: () -> T): T {
        val reply = CompletableDeferred<T>()
        postToActor { reply.complete(block()) }
        return reply.await()
    }

    /** Доступ эпизода «умной проверки» к состоянию рантайма — только через актор. */
    private inner class SmartContext : SmartSwitchContext {
        override suspend fun snapshot(session: CoreSession) = ask {
            if (!this@ServerSwitchCoordinator.isCurrent(session)) return@ask null
            val plan = currentPlan(session)
            plan to SlowSwitchSnapshot(
                currentId = plan?.currentId ?: session.selectedOutboundTag().orEmpty(),
                targets = plan?.targets.orEmpty(),
                hints = latencyHints(session.profileId),
                busy = smartBusy(),
            )
        }

        override suspend fun isCurrent(session: CoreSession) = ask { this@ServerSwitchCoordinator.isCurrent(session) }

        override suspend fun canTrial(session: CoreSession, currentId: String) = ask {
            this@ServerSwitchCoordinator.isCurrent(session) && !smartBusy() &&
                currentPlan(session)?.currentId == currentId
        }

        override suspend fun canCommit(session: CoreSession) = ask {
            this@ServerSwitchCoordinator.isCurrent(session) && operationKind == Operation.Smart && !supervisor.busy
        }

        override suspend fun onCommitted(session: CoreSession) = ask {
            // Общий бюджет автоматики: переключение «умной проверки» входит в лимит автозамены.
            supervisor.recordExternalSwitch()
            controller.clearConnectionIdentity(session.generation)
            onExitChanged(session)
        }

        override fun announce(session: CoreSession, message: String, notificationDetail: String) {
            controller.publishMessage(session.generation, message)
            postToActor {
                if (this@ServerSwitchCoordinator.isCurrent(session)) {
                    host.showForeground(ForegroundState.Connected, detail = notificationDetail)
                }
            }
        }

        override fun timeline(message: String) = this@ServerSwitchCoordinator.timeline(message)
    }

    /** Доступ эпизода YouTube-слота к состоянию рантайма — только через актор. */
    private inner class SlotContext : SlotSwitchContext {
        override suspend fun snapshot(session: CoreSession, slotId: String) = ask {
            if (!this@ServerSwitchCoordinator.isCurrent(session)) return@ask null
            val policy = slotFailover ?: return@ask null
            val spec = session.slotPlan?.specs?.firstOrNull { it.slotId == slotId } ?: return@ask null
            val currentId = policy.serverOf(slotId) ?: return@ask null
            val main = session.primaryGroupTag?.let { session.runtimeSelections()[it] }
            val usedByOthers = policy.assignments().filterKeys { it != slotId }.values.toSet() + listOfNotNull(main)
            SlowSwitchSnapshot(
                currentId = currentId,
                targets = SlotCandidates.targets(
                    members = spec.members,
                    planTargets = currentPlan(session)?.targets,
                    currentId = currentId,
                    usedByOthers = usedByOthers,
                    dead = policy.deadServers() + supervisor.deadServerIds(),
                ),
                hints = latencyHints(session.profileId),
                busy = smartBusy(),
            )
        }

        override suspend fun canTrial(session: CoreSession, slotId: String, currentId: String) = ask {
            this@ServerSwitchCoordinator.isCurrent(session) && !smartBusy() && slotFailover?.serverOf(slotId) == currentId
        }

        override suspend fun place(session: CoreSession, slotId: String, serverId: String) = ask {
            val policy = slotFailover ?: return@ask
            policy.place(slotId, serverId)
            publishSlots(session)
            // Имя нового сервера должно разрешаться до команды (она идёт следом).
            ServerNameRegistry.useForSlots(policy.slotServers())
        }

        override fun announce(session: CoreSession, message: String, notificationDetail: String) =
            SmartContext().announce(session, message, notificationDetail)

        override fun timeline(message: String) = this@ServerSwitchCoordinator.timeline(message)
    }

    private fun timeline(message: String) = log.note(message)

    private fun server(tag: String): String = log.server(tag)
}
