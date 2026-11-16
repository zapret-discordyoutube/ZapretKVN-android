package io.github.zapretkvn.android.vpn.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopLogLineTest {
    @Test
    fun `user stop names the button and has no reason tail`() {
        assertEquals(
            "VPN останавливается (user_stop), причина — команда «Отключить» (приложение, уведомление или внешнее управление)",
            stopLogLine("user_stop", null),
        )
    }

    @Test
    fun `revoke by another VPN keeps the service message`() {
        val line = stopLogLine("permission_revoked", "Разрешение Android VPN отозвано.")
        assertTrue(line, line.startsWith("VPN останавливается (permission_revoked), причина — Android отозвал VPN"))
        assertTrue(line, line.endsWith(": Разрешение Android VPN отозвано."))
    }

    @Test
    fun `unknown trigger and long reason are cut`() {
        val line = stopLogLine("x".repeat(80), "y".repeat(400))
        assertTrue(line, line.contains("— " + "x".repeat(40) + ": "))
        assertTrue(line, line.endsWith("y".repeat(160)))
        assertTrue(line, line.length < 330)
    }

    @Test
    fun `blank reason adds nothing`() {
        assertEquals(stopLogLine("policy_stop", null), stopLogLine("policy_stop", "  "))
    }

    @Test
    fun `destroy line counts live sessions`() {
        assertEquals("сервис VPN закрыт системой без команды остановки (onDestroy), живых сессий: 1", destroyLogLine(1))
    }

    @Test
    fun `every stop goes through the logged path`() {
        val source = File("src/main/java/io/github/zapretkvn/android/vpn/runtime/VpnRuntime.kt").readText()
        val onStop = source.substringAfter("private fun onStop(message: Message.Stop) {").substringBefore("val token")
        assertTrue("onStop logs the trigger before the stop starts", onStop.contains("timeline(stopLogLine(message.trigger, message.errorMessage))"))
        val destroy = source.substringAfter("fun destroy(): VpnConnectionState? {").substringBefore("closeNetworkMonitor()")
        assertTrue("destroy logs a live session closed by the system", destroy.contains("timeline(destroyLogLine(remaining.size))"))
    }
}
