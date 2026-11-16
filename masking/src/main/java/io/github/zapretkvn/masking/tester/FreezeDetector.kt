package io.github.zapretkvn.masking.tester

import kotlin.math.abs
import kotlin.math.max

/**
 * Decides whether a candidate setting hit the per-connection freeze (masking-design/05, spec
 * of net4people #490 and pwnnex/ByeByeVPN):
 *
 * FROZEN = bytes stop for at least [STALL_MS] at the same offset on two transfers, AND a
 * control transfer carried at least [MIN_TARGET_BYTES] (so the network itself works).
 * A FIN/RST is inconclusive, not a freeze. A target smaller than [MIN_TARGET_BYTES] cannot
 * show the freeze at all (the counter fires at ~16-33 KB), so it is not applicable.
 *
 * Numbers are the bytes the tunnel's OUTER connection carried (what the DPI sees), sampled by
 * the platform adapter; this class never measures anything itself.
 */
object FreezeDetector {
    const val STALL_MS = 8_000L
    const val MIN_TARGET_BYTES = 64L * 1024

    /** Two stalls count as "the same offset" within this many bytes or this share of the offset. */
    const val SAME_OFFSET_BYTES = 4L * 1024
    const val SAME_OFFSET_SHARE = 0.25

    data class Sample(val atMs: Long, val bytes: Long)

    enum class End { Completed, Reset, Timeout, Error }

    data class Transfer(val samples: List<Sample>, val end: End, val targetBytes: Long?) {
        val totalBytes: Long get() = samples.maxOfOrNull(Sample::bytes) ?: 0
        val durationMs: Long get() = if (samples.size < 2) 0 else samples.last().atMs - samples.first().atMs
    }

    /** The longest pause and the offset it happened at. */
    data class Stall(val offsetBytes: Long, val lengthMs: Long)

    sealed interface Verdict {
        data class Pass(val bytesPerSecond: Long) : Verdict

        data class Frozen(val offsetBytes: Long) : Verdict

        data class Inconclusive(val reason: String) : Verdict

        data object NotApplicable : Verdict
    }

    fun longestStall(transfer: Transfer): Stall? {
        var best: Stall? = null
        var since = transfer.samples.firstOrNull() ?: return null
        for (sample in transfer.samples.drop(1)) {
            if (sample.bytes > since.bytes) {
                since = sample
                continue
            }
            val length = sample.atMs - since.atMs
            if (best == null || length > best.lengthMs) best = Stall(since.bytes, length)
        }
        return best
    }

    fun verdict(transfers: List<Transfer>, controlBytes: Long): Verdict {
        if (transfers.isEmpty()) return Verdict.Inconclusive("нет данных")
        if (transfers.all { (it.targetBytes ?: Long.MAX_VALUE) < MIN_TARGET_BYTES }) return Verdict.NotApplicable
        val stalls = transfers.mapNotNull { transfer ->
            longestStall(transfer)?.takeIf { it.lengthMs >= STALL_MS && transfer.end != End.Completed }
        }
        if (stalls.size >= 2) {
            val (first, second) = stalls.sortedBy(Stall::offsetBytes).let { it[0] to it[1] }
            if (sameOffset(first.offsetBytes, second.offsetBytes)) {
                if (controlBytes < MIN_TARGET_BYTES) return Verdict.Inconclusive("контрольная загрузка тоже не прошла")
                return Verdict.Frozen((first.offsetBytes + second.offsetBytes) / 2)
            }
        }
        val completed = transfers.filter { it.end == End.Completed && it.totalBytes >= MIN_TARGET_BYTES }
        if (completed.isNotEmpty()) {
            val rate = completed.map { if (it.durationMs > 0) it.totalBytes * 1000 / it.durationMs else 0 }.max()
            return Verdict.Pass(rate)
        }
        if (transfers.any { it.end == End.Reset }) return Verdict.Inconclusive("соединение сброшено (FIN/RST), это не заморозка")
        return Verdict.Inconclusive("загрузка не завершилась")
    }

    fun sameOffset(a: Long, b: Long): Boolean =
        abs(a - b) <= max(SAME_OFFSET_BYTES, (max(a, b) * SAME_OFFSET_SHARE).toLong())
}
