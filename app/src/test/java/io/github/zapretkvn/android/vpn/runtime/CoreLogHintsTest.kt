package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `untagged dns timeouts become a separate dns hint`() {
        val hint = CoreLogHints.dnsFailures(
            listOf(
                "ERROR[0057] [1850694183 7.70s] dns: exchange failed for a.example. IN A: context deadline exceeded",
                "ERROR[0057] [1850694184 7.70s] dns: exchange failed for a.example. IN AAAA: context deadline exceeded",
                "ERROR[0058] [1850694185 5.00s] dns: exchange failed for b.example. IN A: read udp 10.0.0.1:1234->8.8.8.8:53: i/o timeout",
                "ERROR[0059] dns: lookup failed for c.example: connection timed out",
            ),
        )
        assertEquals(setOf("a.example.", "b.example.", "c.example:"), hint?.names)
        assertEquals(3, hint?.count)
        // Тегированных подсказок о сервере такие строки не создают.
        assertTrue(
            CoreLogHints.from(
                listOf("ERROR[0057] dns: exchange failed for a.example. IN A: context deadline exceeded"),
            ).isEmpty(),
        )
    }

    @Test
    fun `dns failures without timeout are not counted`() {
        val hint = CoreLogHints.dnsFailures(
            listOf(
                "ERROR[0057] [1850694183 7.70s] dns: exchange failed for •••. IN A: io: read/write on closed pipe",
                "ERROR[0057] dns: exchange failed for x.example. IN A: rcode: NXDOMAIN",
                "INFO[0057] outbound/hysteria2[a]: connection closed: timeout: no recent network activity",
            ),
        )
        assertNull(hint)
    }

    @Test
    fun `dns failure without a name is still counted by line`() {
        val hint = CoreLogHints.dnsFailures(
            listOf(
                "ERROR[0057] dns: exchange failed: context deadline exceeded",
                "ERROR[0057] dns: exchange failed: context deadline exceeded",
            ),
        )
        assertEquals(2, hint?.count)
    }
}
