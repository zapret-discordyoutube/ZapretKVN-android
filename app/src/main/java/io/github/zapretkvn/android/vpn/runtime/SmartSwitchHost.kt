package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.diagnostics.ServerAddressRedactor
import io.github.zapretkvn.android.engines.failover.SlowServerSwitchEngine
import io.github.zapretkvn.android.engines.failover.SlowSwitchHost
import io.github.zapretkvn.android.engines.failover.SlowSwitchSnapshot
import io.github.zapretkvn.android.network.probes.VpnThroughputProbe
import io.github.zapretkvn.android.vpn.VpnController

/**
 * Что эпизоду «умной проверки» нужно от рантайма. Реализация выполняет
 * каждый вызов в акторе: состояние рантайма читается только там.
 */
internal interface SmartSwitchContext {
    /** План группы текущего сервера; null — сессия больше не текущая. */
    suspend fun snapshot(session: CoreSession): Pair<FailoverPlan?, SlowSwitchSnapshot>?

    suspend fun isCurrent(session: CoreSession): Boolean

    /** Можно ли пробовать: сервер не сменился и автоматика не занята. */
    suspend fun canTrial(session: CoreSession, currentId: String): Boolean

    /** Проба всё ещё наша: пользователь или failover её не перехватили. */
    suspend fun canCommit(session: CoreSession): Boolean

    /** Новый сервер закреплён. */
    suspend fun onCommitted(session: CoreSession)

    fun announce(session: CoreSession, message: String, notificationDetail: String)

    fun timeline(message: String)
}

/**
 * Операции одного эпизода «умной проверки» над сессией. Смена сервера идёт
 * тем же [ServerSwitcher], что у ручного выбора и failover: проба без
 * сохранения, затем закрепление или возврат.
 */
internal class SmartSwitchHost(
    private val session: CoreSession,
    private val context: SmartSwitchContext,
    private val switcher: ServerSwitcher,
    private val throughputProbe: VpnThroughputProbe,
    private val controller: VpnController,
) : SlowSwitchHost {
    private data class Trial(val groupTag: String, val previousId: String)

    @Volatile private var groupTag: String? = null
    @Volatile private var trial: Trial? = null

    override suspend fun snapshot(): SlowSwitchSnapshot? {
        val (plan, snapshot) = context.snapshot(session) ?: return null
        groupTag = plan?.groupTag
        return snapshot
    }

    override suspend fun measureThroughput(): Long? {
        if (!context.isCurrent(session)) return null
        return throughputProbe.measure()
    }

    override suspend fun trialSwitch(currentId: String, candidateId: String): Boolean {
        val group = groupTag ?: return false
        if (!context.canTrial(session, currentId)) return false
        return when (val outcome = switcher.trial(session, group, candidateId)) {
            SwitchOutcome.Committed -> {
                trial = Trial(group, currentId)
                context.timeline("Низкая скорость: пробное переключение на ${redact(candidateId)}.")
                true
            }
            is SwitchOutcome.RolledBack, is SwitchOutcome.CoreUnreachable -> {
                switcher.restore(session, group, currentId)
                log(
                    "Пробное переключение не выполнено: " +
                        (outcome as? SwitchOutcome.CoreUnreachable)?.error?.message.orEmpty(),
                )
                false
            }
        }
    }

    override suspend fun commit(candidateId: String): Boolean {
        val current = trial ?: return false
        if (!context.canCommit(session)) {
            // Движок после отказа закрепления откат не вызывает: вернуть сервер здесь.
            rollback()
            return false
        }
        trial = null
        switcher.persist(session, current.groupTag, candidateId)?.let {
            switcher.restore(session, current.groupTag, current.previousId)
            log("Выбор $candidateId не сохранён в профиле; возврат на ${current.previousId}.")
            return false
        }
        session.resetThroughputWindow()
        context.onCommitted(session)
        return true
    }

    override suspend fun rollback() {
        val current = trial ?: return
        trial = null
        switcher.restore(session, current.groupTag, current.previousId)?.let { error ->
            log("Возврат на ${current.previousId} не удался: ${error.message.orEmpty()}")
        }
        session.resetThroughputWindow()
    }

    override fun log(message: String) {
        controller.publishDiagnosticInfo(redact(message))
    }

    override fun announceSwitch(fromId: String, toId: String, fromBytesPerSecond: Long, toBytesPerSecond: Long) {
        val server = redact(toId)
        context.announce(
            session,
            "Низкая скорость: сервер $server выбран автоматически " +
                "(${SlowServerSwitchEngine.formatRate(fromBytesPerSecond)} → " +
                "${SlowServerSwitchEngine.formatRate(toBytesPerSecond)}).",
            // Пользователь обычно в другом приложении: сообщение видно и в уведомлении.
            "низкая скорость, выбран сервер $server",
        )
    }

    private fun redact(value: String): String = ServerAddressRedactor.redact(SecretRedactor.redactInline(value))
}
