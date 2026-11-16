package io.github.zapretkvn.masking

import io.github.zapretkvn.masking.model.ServerShape
import io.github.zapretkvn.masking.presets.MaskingPresets
import io.github.zapretkvn.masking.tester.CandidateTester
import io.github.zapretkvn.masking.tester.FreezeDetector
import io.github.zapretkvn.masking.tester.FreezeDetector.End
import io.github.zapretkvn.masking.tester.FreezeDetector.Sample
import io.github.zapretkvn.masking.tester.FreezeDetector.Transfer
import io.github.zapretkvn.masking.tester.FreezeDetector.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TesterTest {
    private val kb = 1024L

    /** A transfer that grows by [step] bytes per second and then stops at [stopAt] for [stallMs]. */
    private fun stalled(stopAt: Long, stallMs: Long, step: Long = 4 * kb): Transfer {
        val samples = mutableListOf<Sample>()
        var t = 0L
        var bytes = 0L
        while (bytes < stopAt) {
            samples += Sample(t, bytes)
            t += 1000
            bytes = minOf(stopAt, bytes + step)
        }
        samples += Sample(t, stopAt)
        samples += Sample(t + stallMs, stopAt)
        return Transfer(samples, End.Timeout, targetBytes = 128 * kb)
    }

    private fun steady(total: Long = 128 * kb, seconds: Int = 4): Transfer =
        Transfer((0..seconds).map { Sample(it * 1000L, total * it / seconds) }, End.Completed, targetBytes = total)

    @Test
    fun `two stalls at the same offset with a working control are a freeze`() {
        val verdict = FreezeDetector.verdict(listOf(stalled(18 * kb, 9_000), stalled(20 * kb, 8_500)), controlBytes = 128 * kb)
        assertTrue(verdict is Verdict.Frozen)
        assertEquals(19 * kb, (verdict as Verdict.Frozen).offsetBytes)
    }

    @Test
    fun `a freeze is not claimed when the control also failed`() {
        val verdict = FreezeDetector.verdict(listOf(stalled(18 * kb, 9_000), stalled(18 * kb, 9_000)), controlBytes = 10 * kb)
        assertTrue(verdict is Verdict.Inconclusive)
    }

    @Test
    fun `a short pause is not a freeze`() {
        val verdict = FreezeDetector.verdict(listOf(stalled(18 * kb, 3_000), steady()), controlBytes = 128 * kb)
        assertTrue(verdict is Verdict.Pass)
    }

    @Test
    fun `stalls at different offsets are not the counter`() {
        val verdict = FreezeDetector.verdict(listOf(stalled(18 * kb, 9_000), stalled(90 * kb, 9_000)), controlBytes = 128 * kb)
        assertTrue(verdict is Verdict.Inconclusive)
    }

    @Test
    fun `a reset is inconclusive, not a freeze`() {
        val reset = steady().copy(end = End.Reset, samples = steady().samples.take(2))
        assertTrue(FreezeDetector.verdict(listOf(reset), controlBytes = 128 * kb) is Verdict.Inconclusive)
    }

    @Test
    fun `a target below 64 KB cannot show the freeze`() {
        assertEquals(Verdict.NotApplicable, FreezeDetector.verdict(listOf(steady(total = 32 * kb)), controlBytes = 128 * kb))
    }

    @Test
    fun `steady transfer passes with its rate`() {
        val verdict = FreezeDetector.verdict(listOf(steady(128 * kb, 4)), controlBytes = 128 * kb)
        assertEquals(Verdict.Pass(32 * kb), verdict)
    }

    @Test
    fun `rank puts a passing wireguard preset above a frozen run`() {
        val wg = CandidateTester.Candidate(MaskingPresets.wireGuardJunkOnly)
        val xmux = CandidateTester.Candidate(MaskingPresets.xmuxFew)
        val ranked = CandidateTester.rank(
            listOf(
                CandidateTester.Result(xmux, Verdict.Frozen(18 * kb), 300),
                CandidateTester.Result(wg, Verdict.Pass(100 * kb), 200),
            ),
        )
        assertEquals(wg, ranked.first().result.candidate)
        assertEquals("заморозка на 18 КБ", ranked.last().reason)
    }

    @Test
    fun `candidates fit the server core`() {
        val wg = CandidateTester.candidates(ServerShape(ServerShape.Core.Amnezia, "wireguard"))
        assertTrue(wg.isNotEmpty() && wg.all { it.preset.masking.wireguard != null })
        val xhttp = CandidateTester.candidates(ServerShape(ServerShape.Core.Xray, "vless", network = "xhttp", security = "reality"))
        assertTrue(xhttp.none { it.preset.id == "xray-mux" })
        assertTrue(xhttp.any { it.preset.id == "xmux-rotate" })
        assertTrue(CandidateTester.candidates(ServerShape(ServerShape.Core.Hysteria, "hysteria2")).isEmpty())
    }

    @Test
    fun `plan is sequential with human pauses and no fan out`() {
        val candidates = CandidateTester.candidates(ServerShape(ServerShape.Core.Xray, "vless", network = "tcp", security = "reality"))
        val plan = CandidateTester.plan(candidates, Random(7))
        assertEquals(candidates.size * CandidateTester.TRANSFERS_PER_CANDIDATE, plan.size)
        assertTrue(plan.all { it.pauseBeforeMs in CandidateTester.PAUSE_MS })
        assertTrue(plan.map { it.pauseBeforeMs }.toSet().size > 1)
    }
}
