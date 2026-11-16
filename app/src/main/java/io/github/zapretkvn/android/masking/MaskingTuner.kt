package io.github.zapretkvn.android.masking

import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.masking.merge.MaskingMerge
import io.github.zapretkvn.masking.merge.MaskingSettings
import io.github.zapretkvn.masking.model.MaskingOverride
import io.github.zapretkvn.masking.tester.CandidateTester
import io.github.zapretkvn.masking.tester.TuneState
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "Подобрать" runner (masking-design/05): the adapter over [CandidateTester]. For each
 * candidate it sets the in-memory trial for the selected server, reconnects, runs the planned
 * transfers through the tunnel and hands the numbers back; the module decides the order, the
 * pauses and the verdicts. At the end the trial is dropped and the VPN reconnects with the saved
 * settings. Nothing is stored until the user keeps a result ([keep]).
 *
 * Started only by the user and only while connected; one run at a time.
 */
class MaskingTuner internal constructor(
    private val trial: MaskingTrial,
    private val storedSettings: suspend () -> MaskingSettings,
    private val vpnState: StateFlow<VpnConnectionState>,
    /** The tag selected in the primary group of the running session (a server or a nested group). */
    private val selectedTag: Flow<String?>,
    private val restart: (profileId: String, reason: String) -> Unit,
    private val transfer: suspend () -> MaskingTransferProbe.Outcome,
    private val keepOverride: (serverKey: String, override: MaskingOverride) -> Unit,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
) {
    private val mutableState = MutableStateFlow<TuneState>(TuneState.Idle)
    val state: StateFlow<TuneState> = mutableState.asStateFlow()

    @Volatile
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            mutableState.value = try {
                run()
            } catch (stop: Stop) {
                TuneState.Failed(stop.message)
            } catch (cancelled: CancellationException) {
                mutableState.value = TuneState.Idle
                throw cancelled
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Stores [candidate] as the server's own choice (the normal write: the VPN restarts once more). */
    fun keep(candidate: CandidateTester.Candidate) {
        val done = mutableState.value as? TuneState.Done ?: return
        keepOverride(done.serverKey, candidate.override)
        mutableState.value = done.copy(kept = candidate.id)
    }

    fun dismiss() {
        if (job?.isActive != true) mutableState.value = TuneState.Idle
    }

    private suspend fun run(): TuneState {
        val connected = vpnState.value as? VpnConnectionState.Connected
            ?: throw Stop("Подключите VPN: подбор идёт через выбранный сервер.")
        val profileId = connected.profileId
        val tag = selectedTag.first() ?: throw Stop("Нет выбранного сервера.")
        val server = trial.sessionServers.value[tag]
            ?: throw Stop("«$tag» — группа, а не сервер. Выберите конкретный сервер и запустите подбор снова.")
        val stored = storedSettings()
        val current = MaskingMerge.effective(stored, server.key, server.shape).masking
        val candidates = CandidateTester.candidates(server.shape, current)
        if (candidates.isEmpty()) {
            throw Stop("Для этого сервера подбирать нечего: у Hysteria2 маскировка задаётся в самой ссылке.")
        }
        val steps = CandidateTester.plan(candidates, random).groupBy(CandidateTester.Step::candidate)
        val measurements = mutableListOf<CandidateTester.Measurement>()
        var restore = true
        try {
            for ((index, entry) in steps.entries.withIndex()) {
                val (candidate, plan) = entry
                if (vpnState.value.isOff()) {
                    restore = false
                    throw Stop("VPN выключен: подбор остановлен.")
                }
                mutableState.value = TuneState.Running(tag, index + 1, steps.size, candidate.title)
                trial.begin(server.key, candidate.override, candidate.title)
                when (reconnect(profileId, reason = "Подбор маскировки")) {
                    Reconnect.Stopped -> {
                        restore = false
                        throw Stop("VPN выключен: подбор остановлен.")
                    }
                    Reconnect.Failed -> {
                        measurements += CandidateTester.Measurement(candidate, emptyList(), connected = false)
                        continue
                    }
                    Reconnect.Connected -> Unit
                }
                if (withTimeoutOrNull(SELECTION_WAIT_MILLIS) { selectedTag.first { it == tag } } == null) {
                    throw Stop("Во время подбора VPN перешёл на другой сервер: результаты были бы не про «$tag».")
                }
                val outcomes = plan.map { step ->
                    delay(step.pauseBeforeMs)
                    transfer()
                }
                measurements += CandidateTester.Measurement(
                    candidate = candidate,
                    transfers = outcomes.map(MaskingTransferProbe.Outcome::transfer),
                    firstByteMs = outcomes.mapNotNull(MaskingTransferProbe.Outcome::firstByteMs).minOrNull(),
                )
            }
        } finally {
            trial.end()
            if (restore && !vpnState.value.isOff()) withContext(NonCancellable) { reconnect(profileId, reason = "Подбор маскировки завершён") }
        }
        return TuneState.Done(tag, server.key, CandidateTester.judge(measurements))
    }

    private enum class Reconnect { Connected, Failed, Stopped }

    private suspend fun reconnect(profileId: String, reason: String): Reconnect {
        val before = vpnState.value
        restart(profileId, reason)
        val settled = withTimeoutOrNull(RECONNECT_TIMEOUT_MILLIS) {
            vpnState.first { it != before }
            vpnState.first { it.isSettled() }
        }
        return when (settled) {
            is VpnConnectionState.Connected -> Reconnect.Connected
            null -> Reconnect.Failed
            else -> if (settled.isOff()) Reconnect.Stopped else Reconnect.Failed
        }
    }

    private fun VpnConnectionState.isSettled(): Boolean =
        this is VpnConnectionState.Connected || this is VpnConnectionState.Error || isOff()

    /** The user switched the VPN off: the runner never switches it back on. */
    private fun VpnConnectionState.isOff(): Boolean =
        this == VpnConnectionState.Stopped || this is VpnConnectionState.Stopping

    private class Stop(override val message: String) : Exception(message)

    internal companion object {
        const val RECONNECT_TIMEOUT_MILLIS = 45_000L
        const val SELECTION_WAIT_MILLIS = 10_000L
    }
}
