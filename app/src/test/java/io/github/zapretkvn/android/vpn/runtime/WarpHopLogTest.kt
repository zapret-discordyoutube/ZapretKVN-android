package io.github.zapretkvn.android.vpn.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lines below are REAL captures: the owner's phone diagnostic of 2026-11-26 and the emulator run with real WARP. */
class WarpHopLogTest {
    private val retry = "WARN[0003] endpoint/warp[kvn-warp-entry]: WARP handshake did not answer at •••:500, trying the next address"
    private val answered = "INFO[0003] endpoint/warp[kvn-warp-entry]: WARP tunnel answered at •••:2408 (attempt 2)"
    private val first = "DEBUG[0001] endpoint/warp[kvn-warp-entry]: peer(bmXO…fgyo) - received handshake response"
    private val rekey = "DEBUG[0136] endpoint/warp[kvn-warp-entry]: peer(bmXO…fgyo) - received handshake response"
    private val noise = "DEBUG[0016] endpoint/warp[kvn-warp-entry]: peer(bmXO…fgyo) - sending keepalive packet"
    private val other = "INFO[0001] [2305420726 1.3s] endpoint/warp[kvn-warp-entry]: outbound connection to <сервер ep-1>:443"

    @Test
    fun theStartOfTheTunnelIsSaidOnTheCoresOwnClock() {
        val lines = WarpHopLog.lines(listOf(retry, answered, first, noise, other))
        assertEquals(
            listOf(
                "вход через WARP: рукопожатие не ответило на порту 500, пробуется следующий адрес (3 с после запуска ядра)",
                "вход через WARP: туннель ответил на порту 2408, попытка 2 (3 с после запуска ядра)",
                "вход через WARP: рукопожатие получено (1 с после запуска ядра)",
            ),
            lines,
        )
    }

    @Test
    fun aLaterRekeyIsNotAStartEvent() {
        assertTrue(WarpHopLog.lines(listOf(rekey)).isEmpty())
    }

    @Test
    fun noAddressEverLeavesALine() {
        assertTrue(WarpHopLog.lines(listOf(retry, answered)).none { "•••" in it || "." in it.substringBefore("(") })
    }
}
