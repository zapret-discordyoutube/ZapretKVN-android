package io.github.zapretkvn.clientreports

import kotlin.math.abs

/**
 * The ingest's clock as seen from the `Date` header of its replies (vpnbot 2026-11-20: Windows PCs whose clock runs
 * ahead had whole batches refused as `client_report_invalid_attempted_at`; the ingest takes now − 7 days … now + 10 min).
 * The app's own freshness check uses the same wrong clock, so it can never notice; only the ingest's time can.
 *
 * An offset smaller than [THRESHOLD_MILLIS] is ignored: `Date` has whole seconds and the reply takes a round trip.
 */
class ReportClock {
    @Volatile
    private var offsetMillis: Long = 0

    /** Learn from one reply: [serverMillis] from its `Date` header (≤ 0 — none), [localMillis] when it arrived. */
    fun learn(serverMillis: Long, localMillis: Long) {
        if (serverMillis > 0) offsetMillis = serverMillis - localMillis
    }

    /** What to add to our timestamps: the learnt offset when it is real, else 0. */
    fun applied(): Long = offsetMillis.takeIf { abs(it) > THRESHOLD_MILLIS } ?: 0

    companion object {
        const val THRESHOLD_MILLIS = 60_000L
    }
}

/**
 * A refused batch (400 or 413) is never dropped with its good reports (vpnbot 2026-11-20: the ingest validates the
 * whole batch and names no index). [send] says what happened to one batch; a refused one is split in halves until the
 * refused reports stand alone, and only those are dropped. [resolved] gets every part that is done (accepted or refused
 * alone). The result is null when the whole batch is done, else the action that stopped the rest (no answer, limit).
 * [budget] caps the extra requests a split may make; past it the refused part is dropped as a whole.
 */
object BatchDelivery {
    const val SPLIT_BUDGET = 16

    fun <T> deliver(
        batch: List<T>,
        send: (List<T>) -> SendAction,
        resolved: (List<T>, SendAction) -> Unit,
        budget: IntArray = intArrayOf(SPLIT_BUDGET),
    ): SendAction? {
        val action = send(batch)
        if (action == SendAction.Remove || action == SendAction.Drop && (batch.size == 1 || budget[0] < 2)) {
            resolved(batch, action)
            return null
        }
        if (action != SendAction.Drop) return action
        budget[0] -= 2
        val half = batch.size / 2
        for (part in listOf(batch.subList(0, half), batch.subList(half, batch.size))) {
            deliver(part, send, resolved, budget)?.let { return it }
        }
        return null
    }
}
