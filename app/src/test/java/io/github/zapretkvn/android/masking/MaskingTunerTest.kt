package io.github.zapretkvn.android.masking

import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.masking.merge.MaskingSettings
import io.github.zapretkvn.masking.model.MaskingOverride
import io.github.zapretkvn.masking.model.ServerShape
import io.github.zapretkvn.masking.tester.CandidateTester
import io.github.zapretkvn.masking.tester.FreezeDetector
import io.github.zapretkvn.masking.tester.FreezeDetector.End
import io.github.zapretkvn.masking.tester.FreezeDetector.Sample
import io.github.zapretkvn.masking.tester.TuneState
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MaskingTunerTest {
    private val kb = 1024L
    private val wg = TunableServer("wg-1", "key-1", ServerShape(ServerShape.Core.Amnezia, "wireguard"))

    private fun frozen() = MaskingTransferProbe.Outcome(
        FreezeDetector.Transfer(
            listOf(Sample(0, 0), Sample(1_000, 10 * kb), Sample(2_000, 18 * kb), Sample(14_000, 18 * kb)),
            End.Timeout,
            CandidateTester.TRANSFER_BYTES,
        ),
        firstByteMs = 400,
    )

    private fun clean() = MaskingTransferProbe.Outcome(
        FreezeDetector.Transfer((0..4).map { Sample(it * 500L, CandidateTester.TRANSFER_BYTES * it / 4) }, End.Completed, CandidateTester.TRANSFER_BYTES),
        firstByteMs = 300,
    )

    private class Rig(scope: TestScope, val transfer: suspend Rig.() -> MaskingTransferProbe.Outcome) {
        val trial = MaskingTrial()
        val vpn = MutableStateFlow<VpnConnectionState>(VpnConnectionState.Connected("p", "Профиль", 1))
        val selected = MutableStateFlow<String?>("wg-1")
        val reasons = mutableListOf<String>()
        val kept = mutableListOf<Pair<String, MaskingOverride>>()
        private var generation = 1L

        /** The override the next session would give the server, as the session reads it. */
        fun trialOverride(): MaskingOverride = trial.applyTo(MaskingSettings.OFF).overrideFor("key-1")

        val tuner = MaskingTuner(
            trial = trial,
            storedSettings = { MaskingSettings.OFF },
            vpnState = vpn,
            selectedTag = selected,
            restart = { profileId, reason ->
                reasons += reason
                scope.launch {
                    vpn.value = VpnConnectionState.Starting(profileId, "Подключение")
                    delay(2_000)
                    vpn.value = VpnConnectionState.Connected(profileId, "Профиль", ++generation)
                }
            },
            transfer = { transfer() },
            keepOverride = { key, override -> kept += key to override },
            scope = scope,
            random = Random(3),
        )
    }

    @Test
    fun `a run measures every candidate, ranks a clean preset above a frozen baseline and restores the session`() = runTest {
        val rig = Rig(this) { if (trialOverride() == MaskingOverride.Off) frozen() else clean() }
        rig.trial.publish(mapOf(wg.tag to wg))
        rig.tuner.start()
        advanceUntilIdle()

        val done = rig.tuner.state.value as TuneState.Done
        assertEquals("key-1", done.serverKey)
        assertTrue(done.ranked.first().result.verdict is FreezeDetector.Verdict.Pass)
        assertEquals(CandidateTester.Candidate.BASELINE, done.ranked.last().result.candidate)
        assertTrue(done.ranked.last().result.verdict is FreezeDetector.Verdict.Frozen)
        val candidates = CandidateTester.candidates(wg.shape)
        assertEquals(List(candidates.size) { "Подбор маскировки" } + "Подбор маскировки завершён", rig.reasons)
        assertEquals("the trial is never left behind", MaskingOverride.Inherit, rig.trialOverride())
        assertTrue(rig.kept.isEmpty())

        val best = done.ranked.first().result.candidate
        rig.tuner.keep(best)
        assertEquals(listOf("key-1" to best.override), rig.kept)
        assertEquals(best.id, (rig.tuner.state.value as TuneState.Done).kept)
    }

    @Test
    fun `switching the VPN off stops the run and never switches it back on`() = runTest {
        val rig = Rig(this) {
            vpn.value = VpnConnectionState.Stopped
            clean()
        }
        rig.trial.publish(mapOf(wg.tag to wg))
        rig.tuner.start()
        advanceUntilIdle()

        assertEquals(TuneState.Failed("VPN выключен: подбор остановлен."), rig.tuner.state.value)
        assertEquals(listOf("Подбор маскировки"), rig.reasons)
        assertEquals(MaskingOverride.Inherit, rig.trialOverride())
    }

    @Test
    fun `a group is not a server`() = runTest {
        val rig = Rig(this) { clean() }
        rig.selected.value = "auto"
        rig.trial.publish(mapOf(wg.tag to wg))
        rig.tuner.start()
        advanceUntilIdle()

        assertTrue((rig.tuner.state.value as TuneState.Failed).message.startsWith("«auto» — группа"))
        assertTrue(rig.reasons.isEmpty())
    }

    @Test
    fun `a server change in the middle stops the run and restores the saved settings`() = runTest {
        val rig = Rig(this) {
            selected.value = "other"
            clean()
        }
        rig.trial.publish(mapOf(wg.tag to wg))
        rig.tuner.start()
        advanceUntilIdle()

        assertTrue((rig.tuner.state.value as TuneState.Failed).message.startsWith("Во время подбора VPN перешёл"))
        assertEquals("Подбор маскировки завершён", rig.reasons.last())
    }

    @Test
    fun `not connected means nothing happens`() = runTest {
        val rig = Rig(this) { clean() }
        rig.vpn.value = VpnConnectionState.Stopped
        rig.tuner.start()
        advanceUntilIdle()

        assertTrue(rig.tuner.state.value is TuneState.Failed)
        assertTrue(rig.reasons.isEmpty())
    }

    @Test
    fun `recorder reads up to the target and maps the end`() {
        var now = 0L
        val recorder = TransferRecorder(0) { now }
        now = 5_000_000
        recorder.read(ByteArrayInputStream(ByteArray((CandidateTester.TRANSFER_BYTES + 10_000).toInt())))
        val outcome = recorder.outcome()
        assertEquals(End.Completed, outcome.transfer.end)
        assertEquals(CandidateTester.TRANSFER_BYTES, outcome.transfer.totalBytes)
        assertEquals(5L, outcome.firstByteMs)

        val short = TransferRecorder(0) { 0 }
        short.read(ByteArrayInputStream(ByteArray(1000)))
        assertEquals("early close is FIN, not a freeze", End.Reset, short.outcome().transfer.end)

        assertEquals(End.Timeout, TransferRecorder.endOf(SocketTimeoutException("Read timed out")))
        assertEquals(End.Reset, TransferRecorder.endOf(SocketException("Connection reset")))
        assertEquals(End.Error, TransferRecorder.endOf(IOException("handshake failed")))
    }

    @Test
    fun `an overrun is a timeout even when the cut read throws`() {
        val recorder = TransferRecorder(0) { 0 }
        recorder.overrun()
        recorder.finish(TransferRecorder.endOf(SocketException("Socket closed")))
        assertEquals(End.Timeout, recorder.outcome().transfer.end)
        assertFalse(recorder.outcome().transfer.samples.isEmpty())
    }
}
