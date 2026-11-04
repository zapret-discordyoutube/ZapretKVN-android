package io.github.zapretkvn.android.ui

import io.github.zapretkvn.android.vpn.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalControlTest {
    private val on = ExternalControlSettings(enabled = true, token = "abc123")

    @Test
    fun externalControlIsOffByDefault() {
        val defaults = UiSettings().externalControl
        assertFalse(defaults.enabled)
        assertEquals("", defaults.token)
    }

    @Test
    fun rightTokenIsAcceptedOnlyWhileEnabled() {
        assertTrue(ExternalControl.accepts(on, "abc123"))
        assertFalse(ExternalControl.accepts(on.copy(enabled = false), "abc123"))
    }

    @Test
    fun wrongMissingOrEmptyTokenIsRejected() {
        assertFalse(ExternalControl.accepts(on, "abc124"))
        assertFalse(ExternalControl.accepts(on, "abc12"))
        assertFalse(ExternalControl.accepts(on, "abc1234"))
        assertFalse(ExternalControl.accepts(on, ""))
        assertFalse(ExternalControl.accepts(on, null))
    }

    @Test
    fun emptyStoredTokenNeverMatches() {
        val empty = ExternalControlSettings(enabled = true, token = "")
        assertFalse(ExternalControl.accepts(empty, ""))
        assertFalse(ExternalControl.accepts(empty, null))
        assertFalse(ExternalControl.accepts(empty, "anything"))
    }

    @Test
    fun newTokenIsLongHexAndDiffersEveryTime() {
        val first = ExternalControl.newToken()
        val second = ExternalControl.newToken()
        assertEquals(48, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(first, second)
    }

    @Test
    fun startActsOnlyWhenStoppedOrFailedOrPaused() {
        assertEquals(ExternalCommand.Start, ExternalControl.commandFor(ExternalControl.ACTION_START, VpnConnectionState.Stopped))
        assertEquals(ExternalCommand.Resume, ExternalControl.commandFor(ExternalControl.ACTION_START, paused()))
        assertEquals(ExternalCommand.Start, ExternalControl.commandFor(ExternalControl.ACTION_START, failed()))
        assertEquals(ExternalCommand.Nothing, ExternalControl.commandFor(ExternalControl.ACTION_START, connected()))
        assertEquals(ExternalCommand.Nothing, ExternalControl.commandFor(ExternalControl.ACTION_START, starting()))
    }

    @Test
    fun stopActsOnlyWhileSomethingRuns() {
        assertEquals(ExternalCommand.Stop, ExternalControl.commandFor(ExternalControl.ACTION_STOP, connected()))
        assertEquals(ExternalCommand.Stop, ExternalControl.commandFor(ExternalControl.ACTION_STOP, paused()))
        assertEquals(ExternalCommand.Stop, ExternalControl.commandFor(ExternalControl.ACTION_STOP, starting()))
        assertEquals(ExternalCommand.Stop, ExternalControl.commandFor(ExternalControl.ACTION_STOP, reconnecting()))
        assertEquals(ExternalCommand.Nothing, ExternalControl.commandFor(ExternalControl.ACTION_STOP, VpnConnectionState.Stopped))
        assertEquals(ExternalCommand.Nothing, ExternalControl.commandFor(ExternalControl.ACTION_STOP, VpnConnectionState.Stopping(null)))
    }

    @Test
    fun toggleFollowsTheQuickSettingsTile() {
        assertEquals(ExternalCommand.Stop, ExternalControl.commandFor(ExternalControl.ACTION_TOGGLE, connected()))
        assertEquals(ExternalCommand.Stop, ExternalControl.commandFor(ExternalControl.ACTION_TOGGLE, reconnecting()))
        assertEquals(ExternalCommand.Resume, ExternalControl.commandFor(ExternalControl.ACTION_TOGGLE, paused()))
        assertEquals(ExternalCommand.Start, ExternalControl.commandFor(ExternalControl.ACTION_TOGGLE, failed()))
        assertEquals(ExternalCommand.Start, ExternalControl.commandFor(ExternalControl.ACTION_TOGGLE, VpnConnectionState.Stopped))
        assertEquals(ExternalCommand.Nothing, ExternalControl.commandFor(ExternalControl.ACTION_TOGGLE, VpnConnectionState.Stopping(null)))
    }

    @Test
    fun unknownActionDoesNothing() {
        assertEquals(ExternalCommand.Nothing, ExternalControl.commandFor("io.github.zapretkvn.android.control.OTHER", VpnConnectionState.Stopped))
    }

    private fun connected(): VpnConnectionState =
        VpnConnectionState.Connected(profileId = "p", profileName = "P", connectedAtEpochMillis = 0L)

    private fun starting(): VpnConnectionState = VpnConnectionState.Starting(profileId = "p", message = "")

    private fun reconnecting(): VpnConnectionState = VpnConnectionState.Reconnecting(profileId = "p", message = "")

    private fun failed(): VpnConnectionState = VpnConnectionState.Error(message = "")

    private fun paused(): VpnConnectionState = VpnConnectionState.Paused(profileId = "p", message = "")
}
