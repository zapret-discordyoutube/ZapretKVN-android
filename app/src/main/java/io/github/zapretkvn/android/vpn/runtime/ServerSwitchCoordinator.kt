package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.config.ParsedConfig
import io.github.zapretkvn.android.diagnostics.VpnFailureStates
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.android.engines.failover.LatencyHint
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchEngine
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchPolicy
import io.github.zapretkvn.android.engines.failover.SlowSwitchGate
import io.github.zapretkvn.android.engines.failover.SlowSwitchSnapshot
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.network.probes.LivenessResult
import io.github.zapretkvn.android.vpn.VpnController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

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

    /** Пассивный детектор «подключено, но данные не идут»; без таймеров и своих запросов. */
    private val stallDetector = StallDetector()

    private var trafficJob: Job? = null

    /** «Нет связи с сервером» показано пользователю (только в акторе). */
    private var linkLost = false

    // Читаются из потоков libbox/Android: только volatile-флаги.
    @Volatile private var smartSwitchEnabled = true
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
        runOperation(Operation.Select) { previous ->
            previous?.cancelAndJoin()
            val outcome = try {
                // Та же проверка связи, что у failover: недоступный сервер не остаётся выбранным.
                switcher.switch(
                    session,
                    groupTag,
                    outboundTag,
                    SwitchVerifier { candidateSession, json -> verifyCandidate(candidateSession, json) },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SwitchOutcome.RolledBack(error)
            }
            postToActor { onSelectDone(session, outboundTag, outcome, startId) }
        }
    }

    private fun onSelectDone(session: CoreSession, outboundTag: String, outcome: SwitchOutcome, startId: Int) {
        if (sessions.active() !== session || session.generation != controller.currentGeneration()) return
        when (outcome) {
            SwitchOutcome.Committed -> {
                host.showForeground(ForegroundState.Connected)
                controller.clearConnectionIdentity(session.generation)
                onExitChanged(session)
            }
            is SwitchOutcome.RolledBack -> {
                val failure = VpnFailureStates.from(outcome.error)
                timeline("Выбранный ${server(outboundTag)} не прошёл проверку связи (${failure.code}): прежний сервер возвращён.")
                controller.publishMessage(
                    "Сервер ${server(outboundTag)} не прошёл проверку связи, оставлен прежний. ${failure.message}",
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

    /** Одна проба через текущий сервер: единственная активная проверка, которую запускает надзор. */
    private fun startProbe(session: CoreSession, decision: PathDecision.Probe, code: HysteriaFailureCode) {
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
            LivenessResult.Unknown -> timeline("VPN-сеть Android недоступна для проверки; переключения нет.")
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
                timeline(
                    "${server(decision.fromId)} не отвечает${detail?.let { " ($it)" }.orEmpty()}: " +
                        "переключение на ${server(decision.toId)}.",
                )
                val startedAt = clock()
                runOperation(Operation.Failover) { previous ->
                    previous?.cancelAndJoin()
                    val outcome = try {
                        switcher.switch(
                            session,
                            decision.groupTag,
                            decision.toId,
                            SwitchVerifier { candidateSession, json -> verifyCandidate(candidateSession, json) },
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        SwitchOutcome.RolledBack(error)
                    }
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
            LivenessResult.Unknown -> timeline("VPN-сеть Android недоступна для проверки.")
            is LivenessResult.Dead -> {
                timeline("Сервер не отвечает (${result.detail}); автоматической замены для этого профиля нет.")
                setLinkLost(session, true, "сервер не отвечает, автоматической замены нет")
            }
        }
    }

    /**
     * Показать или снять «Нет связи с сервером»: флаг для главного экрана и текст
     * foreground-уведомления. Снимается ростом входящего трафика, ответом сервера
     * на проверку, успешной сменой сервера и новой сессией.
     */
    private fun setLinkLost(session: CoreSession, lost: Boolean, reason: String) {
        if (linkLost == lost || !isCurrent(session)) return
        linkLost = lost
        controller.publishLinkLost(session.generation, lost)
        host.showForeground(if (lost) ForegroundState.LinkLost else ForegroundState.Connected)
        val text = if (lost) "Нет связи с сервером: $reason." else "Связь с сервером восстановилась: $reason."
        timeline(text)
        controller.recordKeyEvent(session.generation, text)
    }

    // ---- «Умная проверка» скорости ----

    fun onSmartSwitchSetting(enabled: Boolean) {
        smartSwitchEnabled = enabled
        // Выключение и включение настройки сбрасывают фиксацию ручного выбора.
        smartPolicy.resetManualHold()
        if (!enabled && operationKind == Operation.Smart) cancelOperation()
        sessions.active()?.let(::applySpeedMonitor)
    }

    /** Экран включился или погас: монитор скорости нужен, только пока он горит. */
    fun onScreenInteractive(interactive: Boolean) {
        screenInteractive = interactive
        postToActor {
            // Погасший экран: данные не идут, это не обрыв. Окна детектора начинаются заново.
            stallDetector.reset()
            sessions.active()?.let(::applySpeedMonitor)
        }
    }

    /** Сессия стала активной: привязать антифлап к профилю и открыть монитор скорости. */
    fun onSessionActivated(session: CoreSession) {
        smartPolicy.bindProfile(session.profileId)
        // Новая сессия: прежние окна, антифлап и «Нет связи» относились к другому подключению.
        stallDetector.reset(keepCooldown = false)
        linkLost = false
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
        if (!session.onThroughputSample(clock(), total, connections)) return
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

    private fun timeline(message: String) = log.note(message)

    private fun server(tag: String): String = log.server(tag)
}
