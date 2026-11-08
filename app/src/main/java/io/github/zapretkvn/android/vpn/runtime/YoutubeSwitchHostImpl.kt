package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.diagnostics.RuntimeLog
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.diagnostics.ServerAddressRedactor
import io.github.zapretkvn.android.engines.failover.SlowSwitchSnapshot
import io.github.zapretkvn.android.engines.failover.VideoOutcome
import io.github.zapretkvn.android.engines.failover.YoutubeSwitchHost
import io.github.zapretkvn.android.vpn.VpnController
import io.github.zapretkvn.runtimelog.SlowVideoDetector
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Операции одного эпизода «переключения при медленном YouTube» над сессией. Смена сервера идёт тем же [ServerSwitcher],
 * что у ручного выбора, failover и «умной проверки»: проба без сохранения, затем закрепление или возврат. Состояние
 * рантайма читается через [SmartSwitchContext] только в акторе.
 */
internal class YoutubeSwitchHostImpl(
    private val session: CoreSession,
    private val context: SmartSwitchContext,
    private val switcher: ServerSwitcher,
    private val controller: VpnController,
    private val verdicts: Channel<SlowVideoDetector.Verdict>,
) : YoutubeSwitchHost {
    @Volatile private var groupTag: String? = null

    override suspend fun snapshot(): SlowSwitchSnapshot? {
        val (plan, snapshot) = context.snapshot(session) ?: return null
        groupTag = plan?.groupTag
        return snapshot
    }

    override suspend fun trialSwitch(currentId: String, candidateId: String): Boolean {
        val group = groupTag ?: return false
        if (!context.canTrial(session, currentId)) return false
        return when (val outcome = switcher.trial(session, group, candidateId)) {
            SwitchOutcome.Committed -> {
                context.timeline("YouTube медленный: пробное переключение на ${redact(candidateId)}.")
                true
            }
            is SwitchOutcome.RolledBack, is SwitchOutcome.CoreUnreachable -> {
                switcher.restore(session, group, currentId)
                log(
                    "Пробное переключение для YouTube не выполнено: " +
                        (outcome as? SwitchOutcome.CoreUnreachable)?.error?.message.orEmpty(),
                )
                false
            }
        }
    }

    override suspend fun dropVideoFlows() {
        // Селектор переключается, а уже открытые соединения остаются на прежнем сервере: без закрытия плеер продолжил бы
        // сидеть на медленном потоке. Новые потоки пойдут уже через выбранный сервер.
        session.closeFlows(RuntimeLog.videoFlowIds())
        RuntimeLog.resetVideoDetector()
        while (verdicts.tryReceive().isSuccess) {
            // вердикты прежнего сервера не относятся к новому
        }
    }

    override suspend fun verifyVideo(timeoutMillis: Long): VideoOutcome {
        var outcome: VideoOutcome? = null
        withTimeoutOrNull(timeoutMillis) {
            while (outcome == null) {
                val verdict = verdicts.receive()
                outcome = when {
                    verdict.healthy -> VideoOutcome.Healthy
                    verdict.starving -> VideoOutcome.Starving
                    else -> null
                }
            }
        }
        return outcome ?: VideoOutcome.Silent
    }

    override suspend fun commit(candidateId: String): Boolean {
        val group = groupTag ?: return false
        if (!context.canCommit(session)) return false
        switcher.persist(session, group, candidateId)?.let {
            log("Выбор $candidateId не сохранён в профиле.")
            return false
        }
        session.resetThroughputWindow()
        context.onCommitted(session)
        return true
    }

    override suspend fun restore(id: String) {
        val group = groupTag ?: return
        switcher.restore(session, group, id)?.let { error ->
            log("Возврат на $id не удался: ${error.message.orEmpty()}")
        }
        session.resetThroughputWindow()
        RuntimeLog.resetVideoDetector()
    }

    override fun log(message: String) {
        controller.publishDiagnosticInfo(redact(message))
    }

    override fun announce(fromId: String, toId: String) {
        val server = redact(toId)
        context.announce(
            session,
            "YouTube на сервере ${redact(fromId)} шёл медленно: выбран сервер $server.",
            // Пользователь обычно в другом приложении: сообщение видно и в уведомлении.
            "YouTube медленный, выбран сервер $server",
        )
    }

    private fun redact(value: String): String = ServerAddressRedactor.redact(SecretRedactor.redactInline(value))
}
