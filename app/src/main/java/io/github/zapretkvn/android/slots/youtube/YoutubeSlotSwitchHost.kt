package io.github.zapretkvn.android.slots.youtube

import io.github.zapretkvn.android.vpn.runtime.CoreSession
import io.github.zapretkvn.android.vpn.runtime.ServerSwitcher
import io.github.zapretkvn.android.slots.SlotSelectors
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

    /** Server the slot sits on now (the slot's own failover may have moved it during a trial); null: session gone. */
    suspend fun placeOf(session: CoreSession, slotId: String): String?

    /**
     * YouTube was slow on [serverId] and the slot left it for a server that plays: remember it for the hour, so that the
     * next connect (a pin included) and the next episode do not go back to the server that just starved the video.
     */
    fun markLeft(session: CoreSession, serverId: String)

    fun announce(session: CoreSession, message: String, notificationDetail: String)

    fun timeline(message: String)
}

/**
 * Silence after a trial move is read as "the trial server is dead" when the slot's own failover has left it: returns the
 * server the slot sits on now, or null when the slot is still on the trial server (silence then may just mean that the
 * viewer left) or there was no trial.
 */
internal fun deadTrialPlace(trial: String?, placeNow: String?): String? =
    if (trial != null && placeNow != null && placeNow != trial) placeNow else null

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

    /** Server the last successful trial command moved the slot to; null before a trial. */
    private var trialId: String? = null

    /**
     * Where the slot sits after a trial server turned out dead (the slot's own failover left it during the trial): the
     * next trial of the episode starts from there, not from the server the engine believes the slot is on.
     */
    private var baseAfterDeadTrial: String? = null

    override suspend fun snapshot(): SlowSwitchSnapshot? = context.snapshot(session, slotId)

    override suspend fun trialSwitch(currentId: String, candidateId: String): Boolean {
        val from = baseAfterDeadTrial ?: currentId
        baseAfterDeadTrial = null
        if (!context.canTrial(session, slotId, from)) return false
        if (from == candidateId) {
            // The slot's failover has already put it on this candidate: nothing to move, the video is judged there.
            trialId = candidateId
            return true
        }
        context.place(session, slotId, candidateId)
        val failure = switcher.moveSlot(session, selectorTag, candidateId)
        if (failure != null) {
            context.place(session, slotId, from)
            log("Пробное переключение слота YouTube не выполнено: ${failure.message.orEmpty()}")
            return false
        }
        trialId = candidateId
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
        outcome?.let { return it }
        // Silence on a trial server is no proof that the viewer left: when the slot's own failover has already left the
        // trial server (its DNS or connections fail), the candidate was dead. That counts as a failed candidate, so the
        // episode goes on to the next one instead of ending with the slow server restored and the video still stuck
        // (field case 2026-11-15: the only trial went to a server that fails the slot's DNS).
        val trial = trialId
        val left = deadTrialPlace(trial, context.placeOf(session, slotId))
        if (trial != null && left != null) {
            baseAfterDeadTrial = left
            log("Пробный сервер ${redact(trial)} не отвечает: слот ушёл с него сам, берём следующий.")
            return VideoOutcome.Starving
        }
        return VideoOutcome.Silent
    }

    /** Already in place since the trial; the slot's server is session state, so there is nothing to persist. */
    override suspend fun commit(candidateId: String): Boolean = true

    override suspend fun restore(id: String) {
        context.place(session, slotId, id)
        val failure = switcher.moveSlot(session, selectorTag, id)
        if (failure != null) {
            log("Возврат слота YouTube на ${redact(id)} не удался: ${failure.message.orEmpty()}")
            // The command did not go through, so the selector most likely still sits on the trial server: the slot's
            // recorded place must say so, otherwise later decisions start from a server the slot has left.
            trialId?.takeIf { it != id }?.let { context.place(session, slotId, it) }
        } else {
            trialId = null
        }
        RuntimeLog.resetVideoDetector()
    }

    override fun log(message: String) {
        controller.publishDiagnosticInfo(redact(message))
    }

    override fun announce(fromId: String, toId: String, restartVideo: Boolean) {
        context.markLeft(session, fromId)
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
