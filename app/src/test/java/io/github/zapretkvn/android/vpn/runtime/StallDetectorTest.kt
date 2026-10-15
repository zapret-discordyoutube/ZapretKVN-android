package io.github.zapretkvn.android.vpn.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StallDetectorTest {
    private val detector = StallDetector()
    private var at = 100_000L
    private var up = 0L
    private var down = 0L

    /** Один отсчёт ядра раз в 5 с с заданными приростами. */
    private fun tick(
        uplink: Long,
        downlink: Long,
        connections: Int = 4,
        step: Long = 5_000,
    ): StallEvent? {
        at += step
        up += uplink
        down += downlink
        return detector.onSample(TrafficSample(at, up, down, connections))
    }

    private fun blackHole() = tick(uplink = 2_000, downlink = 0)

    @Test
    fun `silence is not death`() {
        repeat(20) { assertNull(tick(uplink = 0, downlink = 0, connections = 3)) }
        assertEquals(0, detector.streak)
    }

    @Test
    fun `black hole is suspected after three stalled windows`() {
        assertNull(tick(0, 0)) // первый отсчёт открывает окно
        assertNull(blackHole())
        assertNull(blackHole())
        val event = blackHole() as StallEvent.Suspected
        assertEquals(StallKind.Traffic, event.kind)
    }

    @Test
    fun `two stalled windows are not enough`() {
        tick(0, 0)
        assertNull(blackHole())
        assertNull(blackHole())
        assertNull(tick(uplink = 0, downlink = 0))
        assertEquals(0, detector.streak)
    }

    @Test
    fun `downlink growth in a window breaks the streak`() {
        tick(0, 0)
        blackHole()
        blackHole()
        assertNull(tick(uplink = 2_000, downlink = 4_000))
        assertNull(blackHole())
        assertNull(blackHole())
        assertTrue(blackHole() is StallEvent.Suspected)
    }

    @Test
    fun `silence between stalled windows breaks the streak`() {
        tick(0, 0)
        blackHole()
        blackHole()
        assertNull(tick(uplink = 10, downlink = 0))
        assertNull(blackHole())
        assertNull(blackHole())
    }

    @Test
    fun `no open connections is not a stall`() {
        tick(0, 0)
        repeat(6) { assertNull(tick(uplink = 2_000, downlink = 0, connections = 0)) }
    }

    @Test
    fun `one hz samples are folded into five second windows`() {
        tick(0, 0, step = 1_000)
        var event: StallEvent? = null
        repeat(14) { if (event == null) event = tick(uplink = 400, downlink = 0, step = 1_000) }
        // 14 с < 3 окон по 5 с + граница окна: ещё рано.
        assertNull(event)
        repeat(3) { if (event == null) event = tick(uplink = 400, downlink = 0, step = 1_000) }
        assertTrue(event is StallEvent.Suspected)
    }

    @Test
    fun `large gap between samples resets observation`() {
        tick(0, 0)
        blackHole()
        blackHole()
        assertNull(tick(uplink = 2_000, downlink = 0, step = 60_000))
        assertEquals(0, detector.streak)
        assertNull(blackHole())
        assertNull(blackHole())
        assertTrue(blackHole() is StallEvent.Suspected)
    }

    @Test
    fun `counter rollback of a new session resets observation`() {
        tick(0, 0)
        blackHole()
        blackHole()
        at += 5_000
        assertNull(detector.onSample(TrafficSample(at, 10, 0, 4)))
        assertEquals(0, detector.streak)
    }

    @Test
    fun `reset starts a new observation`() {
        tick(0, 0)
        blackHole()
        blackHole()
        detector.reset()
        assertEquals(0, detector.streak)
        assertNull(blackHole())
        assertNull(blackHole())
        assertNull(blackHole())
        assertTrue(blackHole() is StallEvent.Suspected)
    }

    @Test
    fun `alarm is rate limited to one per five minutes`() {
        tick(0, 0)
        repeat(2) { blackHole() }
        assertTrue(blackHole() is StallEvent.Suspected)
        // Чёрная дыра продолжается, но повторной тревоги нет до конца окна антифлапа.
        var repeated = 0
        var elapsed = 0L
        while (elapsed < StallDetector.ALARM_COOLDOWN_MILLIS - 10_000) {
            if (blackHole() != null) repeated++
            elapsed += 5_000
        }
        assertEquals(0, repeated)
        var next: StallEvent? = null
        repeat(10) { if (next == null) next = blackHole() }
        assertTrue(next is StallEvent.Suspected)
    }

    @Test
    fun `reset keeps the cooldown unless a new session starts`() {
        tick(0, 0)
        repeat(2) { blackHole() }
        assertTrue(blackHole() is StallEvent.Suspected)
        detector.reset(keepCooldown = true)
        tick(0, 0)
        repeat(5) { assertNull(blackHole()) }
        detector.reset(keepCooldown = false)
        tick(0, 0)
        repeat(2) { blackHole() }
        assertTrue(blackHole() is StallEvent.Suspected)
    }

    @Test
    fun `solid downlink growth reports recovery`() {
        tick(0, 0)
        assertEquals(StallEvent.Recovered, tick(uplink = 500, downlink = 200_000))
        // Мелкий фоновый шум восстановлением не считается.
        assertNull(tick(uplink = 500, downlink = 700))
    }

    // ---- DNS ----

    private fun warmUp(seconds: Int = 30, downlink: Long = 0) {
        repeat(seconds / 5) { tick(uplink = 0, downlink = downlink, connections = 2) }
    }

    @Test
    fun `dns failure series with flat downlink is suspected`() {
        warmUp()
        assertNull(detector.onDnsFailure(at, "a.example"))
        assertNull(detector.onDnsFailure(at + 1_000, "b.example"))
        val event = detector.onDnsFailure(at + 2_000, "c.example") as StallEvent.Suspected
        assertEquals(StallKind.Dns, event.kind)
    }

    @Test
    fun `repeated failures of one name count once`() {
        warmUp()
        repeat(10) { assertNull(detector.onDnsFailure(at + it * 100L, "same.example")) }
    }

    @Test
    fun `failures spread over more than twenty seconds do not add up`() {
        warmUp()
        assertNull(detector.onDnsFailure(at, "a.example"))
        assertNull(detector.onDnsFailure(at + 12_000, "b.example"))
        assertNull(detector.onDnsFailure(at + 24_000, "c.example"))
    }

    @Test
    fun `dns failures while downlink grows are ignored`() {
        warmUp(30, downlink = 50_000)
        assertNull(detector.onDnsFailure(at, "a.example"))
        assertNull(detector.onDnsFailure(at + 500, "b.example"))
        assertNull(detector.onDnsFailure(at + 1_000, "c.example"))
    }

    @Test
    fun `dns signal needs observed traffic samples`() {
        // Источник отсчётов не подключён или наблюдение только началось.
        assertNull(detector.onDnsFailure(at, "a.example"))
        assertNull(detector.onDnsFailure(at + 1, "b.example"))
        assertNull(detector.onDnsFailure(at + 2, "c.example"))
        tick(0, 0)
        assertNull(detector.onDnsFailure(at, "d.example"))
        assertNull(detector.onDnsFailure(at + 1, "e.example"))
        assertNull(detector.onDnsFailure(at + 2, "f.example"))
    }

    @Test
    fun `dns alarm shares the cooldown with the traffic alarm`() {
        warmUp()
        detector.onDnsFailure(at, "a.example")
        detector.onDnsFailure(at, "b.example")
        assertTrue(detector.onDnsFailure(at, "c.example") is StallEvent.Suspected)
        warmUp(30)
        detector.onDnsFailure(at, "d.example")
        detector.onDnsFailure(at, "e.example")
        assertNull(detector.onDnsFailure(at, "f.example"))
    }

    @Test
    fun `reset forgets dns failures`() {
        warmUp()
        detector.onDnsFailure(at, "a.example")
        detector.onDnsFailure(at, "b.example")
        detector.reset()
        warmUp()
        assertNull(detector.onDnsFailure(at, "c.example"))
    }
}
