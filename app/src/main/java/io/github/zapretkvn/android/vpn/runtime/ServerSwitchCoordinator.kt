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
    private val startIdentityProbe: (CoreSession) -> Unit,
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
        ops.clearPendingReplacement()
        session.resetThroughputWindow()
        // Ручной выбор фиксируется на 30 минут; failover это не затрагивает.
        smartPolicy.onManualSelection()
        timeline("Сервер выбран вручную: ${server(outboundTag)}.")
        runOperation(Operation.Select) { previous ->
            previous?.cancelAndJoin()
            val outcome = try {
                switcher.switch(session, groupTag, outboundTag)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                SwitchOutcome.RolledBack(error)
            }
            postToActor { onSelectDone(session, outcome, startId) }
        }
    }

    private fun onSelectDone(session: CoreSession, outcome: SwitchOutcome, startId: Int) {
        if (sessions.active() !== session || session.generation != controller.currentGeneration()) return
        when (outcome) {
            SwitchOutcome.Committed -> {
                host.showForeground(ForegroundState.Connected)
                controller.clearConnectionIdentity(session.generation)
                startIdentityProbe(session)
            }
            is SwitchOutcome.RolledBack -> {
                controller.publishMessage(VpnFailureStates.from(outcome.error).message)
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
                    probeCode = hint.code
                    runOperation(Operation.Probe) { previous ->
                        previous?.cancelAndJoin()
                        val result = container.vpnLivenessProbe.check()
                        postToActor { onProbeDone(session, decision.episode, result) }
                    }
                    return
                }
                else -> {
                    apply(session, decision, hint.code)
                    return
                }
            }
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
            is LivenessResult.Alive -> timeline(
                "Сервер отвечает (${result.endpoint}, ${result.elapsedMillis} мс): ложная тревога, переключения нет.",
            )
            LivenessResult.Unknown -> timeline("VPN-сеть Android недоступна для проверки; переключения нет.")
            is LivenessResult.Dead -> if (decision is PathDecision.Ignore) {
                timeline("Сервер не отвечает (${result.detail}); подходящей замены нет, остаёмся.")
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
                controller.clearConnectionIdentity(session.generation)
                startIdentityProbe(session)
            }
            is SwitchOutcome.RolledBack -> {
                supervisor.onSwitchResult(episode, committed = false)
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
        postToActor { sessions.active()?.let(::applySpeedMonitor) }
    }

    /** Сессия стала активной: привязать антифлап к профилю и открыть монитор скорости. */
    fun onSessionActivated(session: CoreSession) {
        smartPolicy.bindProfile(session.profileId)
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
            startIdentityProbe(session)
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
