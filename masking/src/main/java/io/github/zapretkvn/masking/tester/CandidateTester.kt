package io.github.zapretkvn.masking.tester

import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.MaskingOverride
import io.github.zapretkvn.masking.model.ServerShape
import io.github.zapretkvn.masking.presets.MaskingPresets
import kotlin.random.Random

/**
 * "Подбор": which candidates to try on the selected server, in which order and with which
 * pauses, and how to rank the results. The platform adapter runs each step through the
 * already connected tunnel and reports numbers; every decision is here.
 *
 * Must not expose the servers (project memory `probes-must-not-expose-servers`): one neutral
 * target, strictly sequential, jittered browser-like pauses, no fan-out, never a UDP probe of
 * the VPN server.
 */
object CandidateTester {
    /** Pause between two requests: a person clicking, not a scanner. */
    val PAUSE_MS = 1_500L..4_000L

    /** Transfers per candidate: two are needed to tell a freeze from a random stall. */
    const val TRANSFERS_PER_CANDIDATE = 2

    /** Bytes read per transfer: twice the 64 KB the freeze needs to show, far above the ~16-33 KB counter. */
    const val TRANSFER_BYTES = 256L * 1024

    /** What one candidate sets as the server's own choice while it is measured (and when kept). */
    data class Candidate(val id: String, val title: String, val override: MaskingOverride) {
        companion object {
            /** Measured first: the comparison point, and a working run proves the network itself is fine. */
            val BASELINE = Candidate("baseline", "Без маскировки", MaskingOverride.Off)

            fun of(preset: MaskingPresets.Preset) = Candidate(preset.id, preset.title, MaskingOverride.Custom(preset.masking))
        }
    }

    data class Step(val candidate: Candidate, val transfer: Int, val pauseBeforeMs: Long)

    /** What the adapter hands back for one candidate. [connected] false: the tunnel did not come up with it. */
    data class Measurement(
        val candidate: Candidate,
        val transfers: List<FreezeDetector.Transfer>,
        val firstByteMs: Long? = null,
        val connected: Boolean = true,
    )

    data class Result(val candidate: Candidate, val verdict: FreezeDetector.Verdict, val handshakeMs: Long?)

    data class Ranked(val result: Result, val reason: String)

    /**
     * Candidates that can act on [shape]: the baseline, then the server's [current] set when it is
     * not one of the presets, then the presets that fit, strongest group first. Empty when nothing
     * can act (Hysteria2: obfs is part of the link).
     */
    fun candidates(
        shape: ServerShape,
        current: Masking? = null,
        presets: List<MaskingPresets.Preset> = MaskingPresets.all,
    ): List<Candidate> {
        val fitting = presets.filter { preset -> fits(preset.masking, shape) }.sortedBy { it.group.ordinal }
        if (fitting.isEmpty()) return emptyList()
        val own = current?.takeIf { !it.isEmpty && fitting.none { preset -> preset.masking == it } }
            ?.let { Candidate("current", "Сейчас", MaskingOverride.Custom(it)) }
        return listOfNotNull(Candidate.BASELINE, own) + fitting.map(Candidate::of)
    }

    private fun fits(masking: Masking, shape: ServerShape): Boolean = when (shape.core) {
        ServerShape.Core.Amnezia -> masking.wireguard != null
        ServerShape.Core.Xray -> masking.xray?.let { xray ->
            when {
                xray.xmux != null -> shape.isXhttp
                xray.mux != null -> !shape.isMultiplexedTransport
                xray.desync != null -> shape.isTcpStream
                else -> xray.fragment != null && shape.isTcpStream
            }
        } ?: false
        ServerShape.Core.SingBox -> masking.singbox != null && shape.security == "tls"
        ServerShape.Core.Hysteria -> false
    }

    /** The run plan: sequential, one target, jittered pauses. [random] is injectable for tests. */
    fun plan(candidates: List<Candidate>, random: Random = Random.Default): List<Step> =
        candidates.flatMap { candidate ->
            (1..TRANSFERS_PER_CANDIDATE).map { transfer ->
                Step(candidate, transfer, random.nextLong(PAUSE_MS.first, PAUSE_MS.last + 1))
            }
        }

    /**
     * Verdicts of a whole run, best first. The control is the run itself: the most bytes any
     * transfer carried through the same tunnel to the same target. If nothing reached 64 KB, a
     * stall everywhere says more about the network than about any candidate.
     */
    fun judge(measurements: List<Measurement>): List<Ranked> {
        val control = measurements.flatMap(Measurement::transfers).maxOfOrNull(FreezeDetector.Transfer::totalBytes) ?: 0
        return rank(
            measurements.map { measurement ->
                val verdict = if (!measurement.connected) {
                    FreezeDetector.Verdict.Inconclusive("туннель не поднялся с этими настройками")
                } else {
                    FreezeDetector.verdict(measurement.transfers, control)
                }
                Result(measurement.candidate, verdict, measurement.firstByteMs)
            },
        )
    }

    /** Best first: passed (fastest first), then inconclusive, then frozen (later freeze first). */
    fun rank(results: List<Result>): List<Ranked> =
        results.sortedWith(
            compareBy<Result> { order(it.verdict) }
                .thenByDescending { (it.verdict as? FreezeDetector.Verdict.Pass)?.bytesPerSecond ?: 0 }
                .thenByDescending { (it.verdict as? FreezeDetector.Verdict.Frozen)?.offsetBytes ?: 0 },
        ).map { Ranked(it, reason(it.verdict)) }

    private fun order(verdict: FreezeDetector.Verdict): Int = when (verdict) {
        is FreezeDetector.Verdict.Pass -> 0
        is FreezeDetector.Verdict.Inconclusive -> 1
        FreezeDetector.Verdict.NotApplicable -> 2
        is FreezeDetector.Verdict.Frozen -> 3
    }

    fun reason(verdict: FreezeDetector.Verdict): String = when (verdict) {
        is FreezeDetector.Verdict.Pass -> "прошло без заморозки, ${verdict.bytesPerSecond / 1024} КБ/с"
        is FreezeDetector.Verdict.Frozen -> "заморозка на ${verdict.offsetBytes / 1024} КБ"
        is FreezeDetector.Verdict.Inconclusive -> "не ясно: ${verdict.reason}"
        FreezeDetector.Verdict.NotApplicable -> "проверка слишком мала, чтобы увидеть заморозку"
    }
}
