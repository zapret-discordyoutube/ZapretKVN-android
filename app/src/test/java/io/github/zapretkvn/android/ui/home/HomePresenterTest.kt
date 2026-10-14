package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.vpn.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePresenterTest {
    private fun input(
        state: VpnConnectionState = VpnConnectionState.Stopped,
        hasProfile: Boolean = true,
        selected: Int = 3,
        blocked: Int = 0,
        mode: AppScopeMode = AppScopeMode.Include,
        server: String? = "Amsterdam-2",
    ) = HomeInput(state, hasProfile, selected, blocked, mode, server)

    @Test
    fun `stages follow setup order before vpn state`() {
        assertEquals(HomeStage.NoProfile, homeStage(input(hasProfile = false)))
        assertEquals(HomeStage.NeedsApps, homeStage(input(selected = 0)))
        assertEquals(HomeStage.Ready, homeStage(input()))
        // Blocked apps alone are enough in Include mode: the tunnel has work to do.
        assertEquals(HomeStage.Ready, homeStage(input(selected = 0, blocked = 2)))
        // Once running, the setup hints never hide the live state.
        val connected = VpnConnectionState.Connected("p", "Daily", 0L)
        assertEquals(HomeStage.Connected, homeStage(input(state = connected, selected = 0)))
    }

    @Test
    fun `actions keep the labels tests and users rely on`() {
        assertEquals(HomeAction.Connect to HomeAction.None, homeActions(HomeStage.Ready, AppScopeMode.Include))
        assertEquals(HomeAction.Connect to HomeAction.None, homeActions(HomeStage.Error, AppScopeMode.Include))
        assertEquals(HomeAction.Disconnect to HomeAction.Restart, homeActions(HomeStage.Connected, AppScopeMode.Include))
        assertEquals(HomeAction.ResumeNow to HomeAction.Stop, homeActions(HomeStage.Paused, AppScopeMode.Include))
        assertEquals(HomeAction.SelectDirectApps, homeActions(HomeStage.NeedsApps, AppScopeMode.Exclude).first)
        assertEquals("Подключить", HomeAction.Connect.label)
        assertEquals("Отключить", HomeAction.Disconnect.label)
        assertEquals("Остановить", HomeAction.Stop.label)
    }

    @Test
    fun `headlines tell what to do next`() {
        val ready = homeHeadline(HomeStage.Ready, input())
        assertEquals("Готово к подключению", ready.title)
        assertTrue(ready.hint!!.contains("Amsterdam-2"))

        val connected = input(state = VpnConnectionState.Connected("p", "Daily", 0L), selected = 12)
        val headline = homeHeadline(HomeStage.Connected, connected)
        assertEquals("Всё работает", headline.title)
        assertTrue(headline.hint!!.contains("12 приложений"))

        val reconnecting = VpnConnectionState.Reconnecting("p", "Сеть пропала", attempt = 2, maxAttempts = 5)
        assertEquals(
            "Сеть пропала · попытка 2 из 5",
            homeHeadline(HomeStage.Reconnecting, input(state = reconnecting)).hint,
        )
        assertEquals(
            "Выберите хотя бы одно приложение для VPN.",
            homeHeadline(HomeStage.NeedsApps, input(selected = 0)).hint,
        )
    }

    @Test
    fun `status labels stay stable`() {
        assertEquals("VPN выключен", statusLabel(VpnConnectionState.Stopped))
        assertEquals("Ошибка VPN", statusLabel(VpnConnectionState.Error("x")))
        assertEquals("Через VPN: 2 · заблокировано: 1", appsSummary(AppScopeMode.Include, 2, 1))
    }
}
