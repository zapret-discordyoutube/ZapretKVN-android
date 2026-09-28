package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Строки взяты из реальных диагностик Android 0.4.7/0.4.8 (адреса замаскированы). */
class CoreLogHintsTest {
    @Test
    fun `hysteria QUIC idle timeout becomes a single hint for the server`() {
        val hints = CoreLogHints.from(
            listOf(
                "ERROR[2147] [3068333715 1ms] outbound/hysteria2[senge]: connection closed: timeout: no recent network activity",
                "ERROR[2147] [3068333716 1ms] outbound/hysteria2[senge]: connection closed: timeout: no recent network activity",
            ),
        )
        assertEquals(listOf(PathHint("senge", "hysteria2", HysteriaFailureCode.TARGET_NETWORK_TIMEOUT)), hints)
    }

    @Test
    fun `selector-level lines name the group, not a server`() {
        val hints = CoreLogHints.from(
            listOf(
                "ERROR[2147] [3068333715 8ms] connection: open connection to •••:443 using " +
                    "outbound/selector[zapret-proxy]: connection closed: timeout: no recent network activity",
            ),
        )
        // Группа не бывает текущим сервером плана: супервизор такую подсказку отбросит.
        assertTrue(hints.all { it.outboundTag == "zapret-proxy" })
    }

    @Test
    fun `xray internal dial errors are not server hints`() {
        val hints = CoreLogHints.from(
            listOf(
                "ERROR[0058] xray: [Error] transport/internet/websocket: failed to dial to <сервер ep-1>:443 " +
                    "> dial wlan0 (45): dial tcp <сервер ep-1>:443: operation was canceled",
                "ERROR[0057] [1850694183 7.70s] dns: exchange failed for •••. IN A: io: read/write on closed pipe",
            ),
        )
        assertTrue(hints.isEmpty())
    }

    @Test
    fun `stream cancelled by the local app is not a failure`() {
        val hints = CoreLogHints.from(
            listOf("ERROR[0058] [288433457 56.18s] connection: connection download closed: stream 88 canceled by local with error code 0"),
        )
        assertTrue(hints.isEmpty())
    }
}
