package io.github.zapretkvn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickSettingsTilePresentationTest {
    @Test
    fun `connected and starting states render active`() {
        val starting = VpnConnectionState.Starting("profile", "Подготовка")
            .toQuickSettingsTilePresentation()
        val connected = VpnConnectionState.Connected("profile", "Profile", 1L)
            .toQuickSettingsTilePresentation()

        assertEquals(true, starting.active)
        assertEquals(QuickSettingsTilePresentation.Subtitle.Starting, starting.subtitle)
        assertEquals(true, connected.active)
        assertEquals(QuickSettingsTilePresentation.Subtitle.On, connected.subtitle)
    }

    @Test
    fun `terminal and stopping states render inactive`() {
        val states = listOf(
            VpnConnectionState.Stopped to QuickSettingsTilePresentation.Subtitle.Off,
            VpnConnectionState.Stopping("profile") to
                QuickSettingsTilePresentation.Subtitle.Stopping,
            VpnConnectionState.Error("failure") to QuickSettingsTilePresentation.Subtitle.Error,
        )

        states.forEach { (state, subtitle) ->
            val presentation = state.toQuickSettingsTilePresentation()
            assertEquals(false, presentation.active)
            assertEquals(subtitle, presentation.subtitle)
        }
    }

    @Test
    fun `automatic reconnect keeps the tile active`() {
        // Сервис жив и сам поднимет VPN: гасить тайл значило бы звать пользователя
        // нажимать «Подключить», хотя ничего делать не нужно.
        val reconnecting = VpnConnectionState.Reconnecting("profile", "Ожидание сети Android")
            .toQuickSettingsTilePresentation()

        assertEquals(true, reconnecting.active)
        assertEquals(QuickSettingsTilePresentation.Subtitle.Reconnecting, reconnecting.subtitle)
    }
}
