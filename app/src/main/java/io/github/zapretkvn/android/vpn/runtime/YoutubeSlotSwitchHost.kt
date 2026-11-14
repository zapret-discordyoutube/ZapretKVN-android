package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.config.SlotSelectors
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

/** What the YouTube episode needs from the runtime actor when YouTube has its own slot. Every call runs in the actor. */
internal interface SlotSwitchContext {
    /** Slot server and the candidates of its pool; null when the session is gone or the slot is unknown. */
    suspend fun snapshot(session: CoreSession, slotId: String): SlowSwitchSnapshot?

    /** The slot is still on [currentId] and nothing else is switching. */
    suspend fun canTrial(session: CoreSession, slotId: String, currentId: String): Boolean

    /** The slot now sits on [serverId]: remember it and let the server's name resolve. */
    suspend fun place(session: CoreSession, slotId: String, serverId: String)

    fun announce(session: CoreSession, message: String, notificationDetail: String)

    fun timeline(message: String)
}

/**
 * The "slow YouTube" episode for a YouTube slot: the same engine as for the main selector, but it moves the slot's
 * own selector. With slots on, YouTube leaves through `zapret-slot-youtube`, so switching the main selector (what the
 * non-slot host does) would change nothing for the video. There is no trial/commit split and nothing is written to
 * the profile: a slot's server lives in the session; "commit" only records the new place.
 */
internal class YoutubeSlotSwitchHost(
    private val session: CoreSession,
    private val slotId: String,
    private val context: SlotSwitchContext,
    private val switcher: ServerSwitcher,
    private val controller: VpnController,
    private val verdicts: Channel<SlowVideoDetector.Verdict>,
) : YoutubeSwitchHost {
    private val selectorTag = SlotSelectors.tag(slotId)

    override suspend fun snapshot(): SlowSwitchSnapshot? = context.snapshot(session, slotId)

    override suspend fun trialSwitch(currentId: String, candidateId: String): Boolean {
        if (!context.canTrial(session, slotId, currentId)) return false
        context.place(session, slotId, candidateId)
        val failure = switcher.moveSlot(session, selectorTag, candidateId)
        if (failure != null) {
            context.place(session, slotId, currentId)
            log("Пробное переключение слота YouTube не выполнено: ${failure.message.orEmpty()}")
            return false
        }
        context.timeline("YouTube медленный: слот YouTube пробует ${redact(candidateId)}.")
        return true
    }

    override suspend fun dropVideoFlows() {
        // The selector is switched, the open connections stay on the old server until they are closed.
        session.closeFlows(RuntimeLog.videoFlowIds())
        RuntimeLog.resetVideoDetector()
        while (verdicts.tryReceive().isSuccess) {
            // verdicts of the previous server say nothing about the new one
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

    /** Already in place since the trial; the slot's server is session state, so there is nothing to persist. */
    override suspend fun commit(candidateId: String): Boolean = true

    override suspend fun restore(id: String) {
        context.place(session, slotId, id)
        switcher.moveSlot(session, selectorTag, id)?.let { error ->
            log("Возврат слота YouTube на ${redact(id)} не удался: ${error.message.orEmpty()}")
        }
        RuntimeLog.resetVideoDetector()
    }

    override fun log(message: String) {
        controller.publishDiagnosticInfo(redact(message))
    }

    override fun announce(fromId: String, toId: String, restartVideo: Boolean) {
        val server = redact(toId)
        context.announce(
            session,
            if (restartVideo) {
                "Кластер YouTube на сервере ${redact(fromId)} не отвечает: слот YouTube перешёл на сервер $server. Перезапустите видео."
            } else {
                "YouTube на сервере ${redact(fromId)} шёл медленно: слот YouTube перешёл на сервер $server."
            },
            if (restartVideo) "YouTube завис, слот перешёл на $server: перезапустите видео" else "YouTube медленный, слот перешёл на $server",
        )
    }

    private fun redact(value: String): String = ServerAddressRedactor.redact(SecretRedactor.redactInline(value))
}
