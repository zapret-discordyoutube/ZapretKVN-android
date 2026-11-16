package io.github.zapretkvn.masking.tester

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

    data class Candidate(val preset: MaskingPresets.Preset)

    data class Step(val candidate: Candidate, val transfer: Int, val pauseBeforeMs: Long)

    data class Result(val candidate: Candidate, val verdict: FreezeDetector.Verdict, val handshakeMs: Long?)

    data class Ranked(val result: Result, val reason: String)

    /** Candidates that can act on [shape], strongest group first. */
    fun candidates(shape: ServerShape, presets: List<MaskingPresets.Preset> = MaskingPresets.all): List<Candidate> =
        presets.filter { preset ->
            val masking = preset.masking
            when (shape.core) {
                ServerShape.Core.Amnezia -> masking.wireguard != null
                ServerShape.Core.Xray -> masking.xray?.let { xray ->
                    when {
                        xray.xmux != null -> shape.isXhttp
                        xray.mux != null -> !shape.isMultiplexedTransport
                        else -> xray.fragment != null && shape.isTcpStream
                    }
                } ?: false
                ServerShape.Core.SingBox -> masking.singbox != null && shape.security == "tls"
                ServerShape.Core.Hysteria -> false
            }
        }.sortedBy { it.group.ordinal }.map(::Candidate)

    /** The run plan: sequential, one target, jittered pauses. [random] is injectable for tests. */
    fun plan(candidates: List<Candidate>, random: Random = Random.Default): List<Step> =
        candidates.flatMap { candidate ->
            (1..TRANSFERS_PER_CANDIDATE).map { transfer ->
                Step(candidate, transfer, random.nextLong(PAUSE_MS.first, PAUSE_MS.last + 1))
            }
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
