package io.github.zapretkvn.android.vpn.runtime

/**
 * Decides whether a queued restart request is still wanted (review 2026-11-14). Pure, so the race is tested on plain
 * values; [VpnRuntime] only asks.
 *
 * A restart that names the generation it was meant for is stale when the generation moved on or a stop is in flight.
 * A restart without a generation (settings changed, a dialog asked for it) is read from the UI state, which can be
 * a few milliseconds old: if the user pressed Stop in between, the stop is processed first and the restart that was
 * queued behind it must not bring the VPN back. Once the stop is finished (state no longer `Stopping`) a restart
 * without a generation is a deliberate start again and goes through.
 */
internal object RestartGate {
    fun ignore(
        expectedGeneration: Long?,
        currentGeneration: Long,
        stopInProgress: Boolean,
        stateIsStopping: Boolean,
    ): Boolean = if (expectedGeneration != null) {
        expectedGeneration != currentGeneration || stopInProgress
    } else {
        stopInProgress && stateIsStopping
    }
}
