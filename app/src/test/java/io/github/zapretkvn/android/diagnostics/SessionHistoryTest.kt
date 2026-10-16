package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.vpn.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryTest {
    private var epoch = 1_000_000L
    private var elapsed = 10_000L
    private val history = SessionHistory(epochClock = { epoch }, elapsedClock = { elapsed })

    /** Число прописью из букв: цифры нормализуются в ключе схлопывания, буквы - нет. */
    private fun word(n: Int): String = n.toString().map { 'a' + (it - '0') }.joinToString("")

    private fun open(generation: Long, trigger: String = "user_start") =
        history.open(generation, trigger, "profile-aaaa")

    @Test
    fun `keeps only the last five sessions`() {
        (1L..8L).forEach { generation ->
            open(generation)
            history.markConnected(generation, epoch)
            history.recordTraffic(generation, elapsed, 1, 1)
        }
        val generations = history.snapshot().map { it.generation }
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), generations)
    }

    @Test
    fun `failed attempts do not push out a connected session`() {
        open(1)
        history.markConnected(1, epoch)
        history.onState(1, VpnConnectionState.Reconnecting("p", "m", code = "NET-102"))
        (2L..9L).forEach { generation ->
            open(generation, "auto_recovery")
            history.onState(generation, VpnConnectionState.Reconnecting("p", "m", code = "NET-102"))
        }
        val snapshot = history.snapshot()
        assertEquals(5, snapshot.size)
        assertTrue("connected session survives", snapshot.any { it.generation == 1L })
        assertEquals(9L, snapshot.last().generation)
    }

    @Test
    fun `key event ring is bounded to 24 and drops the oldest`() {
        open(1)
        (0 until 40).forEach { history.recordKeyEvent(1, "event ${word(it)}") }
        val session = history.snapshot().single()
        assertEquals(SessionHistory.MAX_EVENTS, session.events.size)
        assertEquals(40 - SessionHistory.MAX_EVENTS, session.eventsDropped)
        assertEquals("event ${word(39)}", session.events.last().text)
        assertFalse(session.events.any { it.text == "event ${word(0)}" })
    }

    @Test
    fun `same event differing only in digits is coalesced`() {
        open(1)
        repeat(10) { history.recordKeyEvent(1, "core WARN: dial tcp 10.0.0.$it:443: i/o timeout $it") }
        val events = history.snapshot().single().events
        assertEquals(1, events.size)
        assertEquals(10, events.single().repeat)
    }

    @Test
    fun `routine events are evicted before important ones`() {
        open(1)
        history.recordKeyEvent(1, "alarm: detector tripped", important = true)
        repeat(SessionHistory.MAX_EVENTS + 5) { index ->
            history.recordKeyEvent(1, "routine ${word(index)}", important = false)
        }
        val events = history.snapshot().single().events
        assertEquals(SessionHistory.MAX_EVENTS, events.size)
        assertTrue(events.any { it.text.startsWith("alarm") })
    }

    @Test
    fun `end reasons are derived from stop, state and replacement triggers`() {
        open(1)
        history.onStopRequested("user_stop")
        assertEquals("user_stop", history.snapshot().first { it.generation == 1L }.endReason)

        open(2)
        open(3, "network_change")
        assertEquals("network_change", history.snapshot().first { it.generation == 2L }.endReason)

        open(4, "server_switch_restart")
        assertEquals("server_switch", history.snapshot().first { it.generation == 3L }.endReason)

        open(5, "dns_mode_change")
        assertEquals("restart:dns_mode_change", history.snapshot().first { it.generation == 4L }.endReason)
        assertEquals("server_switch", SessionHistory.endReasonForReplacement("server_replacement"))
        assertEquals("restart:dns_mode_change", SessionHistory.endReasonForReplacement("dns_mode_change"))

        history.onState(5, VpnConnectionState.Error("m", code = "VPN-200"))
        assertEquals("error:VPN-200", history.snapshot().first { it.generation == 5L }.endReason)
        assertNotNull(history.snapshot().first { it.generation == 5L }.endedAtEpochMillis)
    }

    @Test
    fun `reconnecting and stopped close the active session once`() {
        open(1)
        history.onState(1, VpnConnectionState.Reconnecting("p", "m", code = "NET-102"))
        epoch += 1000
        history.onState(1, VpnConnectionState.Stopped)
        val session = history.snapshot().single()
        assertEquals("error:NET-102", session.endReason)
        assertEquals(1_000_000L, session.endedAtEpochMillis)
        assertFalse(session.active)
        assertNull(history.current())
    }

    @Test
    fun `error after a policy stop refines the reason with the error code`() {
        open(1)
        history.onStopRequested("policy_stop")
        history.onState(2, VpnConnectionState.Error("m", code = "VPN-300"))
        assertEquals("error:VPN-300", history.snapshot().single().endReason)
    }

    @Test
    fun `proxy traffic shows a stalled server behind growing direct traffic`() {
        open(1)
        history.markConnected(1, epoch)
        history.recordTraffic(1, elapsed, 100, 1_000, proxyUplinkTotal = 50, proxyDownlinkTotal = 500, proxyConnections = 2)
        elapsed += 30_000
        // Прямые RU-сайты качают, через сервер — ничего нового.
        history.recordTraffic(1, elapsed, 400, 90_000, proxyUplinkTotal = 300, proxyDownlinkTotal = 500, proxyConnections = 3)

        val session = history.snapshot().single()
        assertEquals(0L, session.secondsSinceLastDownlink)
        assertEquals(30L, session.secondsSinceLastProxyDownlink)
        assertEquals(300L, session.proxyUplinkBytes)
        assertEquals(500L, session.proxyDownlinkBytes)
        assertEquals(3, session.proxyConnections)
    }

    @Test
    fun `without proxy totals the proxy fields stay unknown`() {
        open(1)
        history.recordTraffic(1, elapsed, 1, 1)
        val session = history.snapshot().single()
        assertNull(session.proxyDownlinkBytes)
        assertNull(session.secondsSinceLastProxyDownlink)
    }

    @Test
    fun `traffic tracks totals, peak and the last downlink growth`() {
        open(1)
        history.markConnected(1, epoch)
        history.recordTraffic(1, elapsed, uplinkTotal = 100, downlinkTotal = 1_000)
        elapsed += 5_000
        epoch += 5_000
        history.recordTraffic(1, elapsed, uplinkTotal = 600, downlinkTotal = 51_000) // 10 000 B/s
        val growthAt = epoch
        elapsed += 5_000
        epoch += 5_000
        history.recordTraffic(1, elapsed, uplinkTotal = 900, downlinkTotal = 51_000) // downlink flat
        elapsed += 20_000

        val session = history.snapshot().single()
        assertEquals(900, session.uplinkBytes)
        assertEquals(51_000, session.downlinkBytes)
        assertEquals(10_000, session.peakDownlinkBytesPerSecond)
        assertEquals(growthAt, session.lastDownlinkAtEpochMillis)
        assertEquals(25L, session.secondsSinceLastDownlink)
        assertEquals(20L, session.secondsSinceLastSample)
        assertEquals(3, session.sampleCount)
    }

    @Test
    fun `traffic for a closed or unknown generation is ignored`() {
        open(1)
        history.onStopRequested("user_stop")
        history.recordTraffic(1, elapsed, 5, 5)
        history.recordTraffic(99, elapsed, 5, 5)
        assertEquals(0, history.snapshot().single().sampleCount)
    }

    @Test
    fun `server addresses never reach the history`() {
        open(1)
        history.updateTarget(
            1,
            DiagnosticTargetContext(
                profileRef = "profile-aaaa",
                outboundTag = "203.0.113.7",
                endpoint = "ep-0123456789ab:443",
                protocol = "vless",
            ),
        )
        history.recordKeyEvent(1, "Путь: сервер 203.0.113.7:443 и node.example.org не отвечают")
        history.recordCoreLine(1, 2, "ERROR dial tcp 198.51.100.9:8443: i/o timeout")

        val session = history.snapshot().single()
        val all = listOfNotNull(session.serverTag, session.serverRef) + session.events.map { it.text }
        all.forEach { text ->
            assertFalse(text, text.contains("203.0.113.7"))
            assertFalse(text, text.contains("198.51.100.9"))
            assertFalse(text, text.contains("node.example.org"))
        }
        assertEquals("ep-0123456789ab:443", session.serverRef)
        assertEquals("vless", session.protocol)
        assertEquals("profile-aaaa", session.profileRef)
    }

    @Test
    fun `core lines are always collected only when they matter`() {
        open(1)
        history.recordCoreLine(1, 5, "inbound/tun[tun-in]: inbound packet connection from 10.0.0.2")
        history.recordCoreLine(1, 6, "dial debug line with timeout")
        history.recordCoreLine(1, 5, "outbound/hysteria2[s1]: connection closed: timeout: no recent network activity")
        history.recordCoreLine(1, 5, "dns: exchange failed for x: i/o timeout")
        history.recordCoreLine(1, 3, "outbound/vless[s2]: anything at warn level")
        history.recordCoreLine(1, 5, "wireguard: sending handshake initiation")
        history.recordCoreLine(1, 5, "quic: stream reset")
        history.recordCoreLine(1, 5, "dial tcp: lookup failed")

        val texts = history.snapshot().single().events.map { it.text }
        assertFalse(texts.any { it.contains("packet connection") })
        assertFalse(texts.any { it.contains("debug line") })
        assertTrue(texts.any { it.contains("no recent network activity") })
        assertTrue(texts.any { it.contains("exchange failed") })
        assertTrue(texts.any { it.contains("warn level") })
        assertTrue(texts.any { it.contains("handshake") })
        assertTrue(texts.any { it.contains("quic") })
        assertTrue(texts.any { it.contains("dial tcp") })
        assertTrue(SessionHistory.isKeyCoreLine(2, "anything"))
        assertFalse(SessionHistory.isKeyCoreLine(5, "routine"))
        assertFalse(SessionHistory.isKeyCoreLine(7, "reconnect trace"))
    }

    @Test
    fun `unknown generation falls back to the newest session`() {
        open(1)
        history.recordKeyEvent(7, "orphan event")
        assertEquals("orphan event", history.snapshot().single().events.single().text)
    }
}
