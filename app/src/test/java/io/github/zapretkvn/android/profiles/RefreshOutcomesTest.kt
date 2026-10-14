package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.SubscriptionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshOutcomesTest {
    private fun refresh(restart: Boolean, connected: Boolean, active: Boolean, selection: Boolean) =
        refreshOutcome(restart, connected, active, selection)

    @Test
    fun `confirmed restart of a connected profile restarts the vpn`() {
        val plain = refresh(restart = true, connected = true, active = true, selection = false)
        val changed = refresh(restart = true, connected = true, active = true, selection = true)

        assertEquals(VpnFollowUp.Restart, plain.followUp)
        assertEquals("Подписка сохранена; подтверждён контролируемый перезапуск VPN.", plain.message)
        assertEquals(VpnFollowUp.Restart, changed.followUp)
        assertTrue(changed.message.startsWith("Выбранный сервер исчез"))
    }

    @Test
    fun `already disconnected active profile needs no restart`() {
        val outcome = refresh(restart = true, connected = false, active = true, selection = true)

        assertEquals(VpnFollowUp.None, outcome.followUp)
        assertEquals("Подписка сохранена; VPN уже отключён, перезапуск не требуется.", outcome.message)
    }

    @Test
    fun `without restart consent the running vpn is never touched`() {
        val selection = refresh(restart = false, connected = true, active = true, selection = true)
        val plain = refresh(restart = false, connected = true, active = true, selection = false)

        assertEquals(VpnFollowUp.None, selection.followUp)
        assertEquals("Выбранный сервер исчез: выбран первый доступный. VPN не перезапущен.", selection.message)
        assertEquals(VpnFollowUp.None, plain.followUp)
        assertEquals("Подписка обновлена. Работающий VPN не изменён.", plain.message)
    }

    private fun plan(removed: Set<String>) = SplitRefreshPlan(
        groupId = "g",
        source = SubscriptionSource("https://sub.example/list"),
        baseName = "База",
        previousBindings = emptyMap(),
        updatedJson = mapOf("a" to "{}", "b" to "{}"),
        additions = emptyList(),
        removedProfileIds = removed,
        knownMemberKeys = emptySet(),
        memberKeysByProfileId = emptyMap(),
        connectedProfileId = null,
    )

    @Test
    fun `split refresh stops a connected profile that was removed`() {
        val outcome = splitRefreshOutcome(plan(setOf("c")), 1, true, "c", true, true)

        assertEquals(VpnFollowUp.Stop, outcome.followUp)
    }

    @Test
    fun `split refresh restarts a connected surviving profile`() {
        val outcome = splitRefreshOutcome(plan(setOf("c")), 1, true, "a", true, true)

        assertEquals(VpnFollowUp.Restart, outcome.followUp)
        assertEquals("Группа подписки обновлена; VPN контролируемо перезапущен.", outcome.message)
    }

    @Test
    fun `split refresh reports counts when nothing is connected or consent is missing`() {
        val noConsent = splitRefreshOutcome(plan(setOf("c")), 3, false, "a", true, true)
        val disconnected = splitRefreshOutcome(plan(emptySet()), 0, true, null, false, true)

        assertEquals(VpnFollowUp.None, noConsent.followUp)
        assertEquals("Группа подписки обновлена: 2 обновлено, 3 добавлено, 1 удалено.", noConsent.message)
        assertEquals("Группа подписки обновлена; VPN уже отключён.", disconnected.message)
    }
}
