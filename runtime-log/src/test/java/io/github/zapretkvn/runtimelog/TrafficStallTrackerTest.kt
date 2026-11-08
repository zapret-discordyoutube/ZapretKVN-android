package io.github.zapretkvn.runtimelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficStallTrackerTest {
    private fun TrafficStallTracker.at(seconds: Int, up: Long, down: Long, connections: Int = 6) =
        onSample(seconds * 1_000L, up, down, connections)

    @Test
    fun `first sample is a baseline only`() {
        assertNull(TrafficStallTracker().at(0, 100, 100))
    }

    @Test
    fun `two windows of uplink without downlink start a stall and recovery ends it`() {
        val tracker = TrafficStallTracker()
        tracker.at(0, 0, 0)
        assertNull(tracker.at(5, 2_000, 9_000_000)!!.event)            // здоровое окно
        assertNull(tracker.at(10, 3_700, 9_000_300)!!.event)           // первое плохое: ещё не подвисание
        val started = tracker.at(15, 5_400, 9_000_600)!!.event             // второе: подтверждено
        assertEquals(TrafficStallTracker.Event.Started(5_000L), started)  // начало первого плохого окна
        assertNull(tracker.at(20, 7_000, 9_000_900)!!.event)           // продолжается
        val ended = tracker.at(25, 7_500, 9_500_000)!!.event               // поток вернулся
        assertEquals(TrafficStallTracker.Event.Ended(20_000L, recovered = true), ended)
    }

    @Test
    fun `idle traffic is not a stall and ends one without recovery`() {
        val tracker = TrafficStallTracker()
        tracker.at(0, 0, 0)
        tracker.at(5, 1_000, 10)                                   // окно 0–5 с уже плохое
        assertTrue(tracker.at(10, 2_000, 20)!!.event is TrafficStallTracker.Event.Started)
        val ended = tracker.at(15, 2_000, 30)!!.event // ничего не уходит и не приходит
        // подвисание началось с первого плохого окна (0 с), к 15 с оно длится 15 секунд
        assertEquals(TrafficStallTracker.Event.Ended(15_000L, recovered = false), ended)
        // без соединений тишина — не подвисание
        assertNull(tracker.at(20, 3_000, 40, connections = 0)!!.event)
        assertNull(tracker.at(25, 4_000, 50, connections = 0)!!.event)
    }

    @Test
    fun `reset starts a new session and forgets an open stall`() {
        val tracker = TrafficStallTracker()
        tracker.at(0, 0, 0)
        tracker.at(5, 1_000, 10)
        assertTrue(tracker.at(10, 2_000, 20)!!.event is TrafficStallTracker.Event.Started)
        tracker.reset()
        assertNull(tracker.at(15, 50, 50))                       // снова только отсчёт
        assertNull(tracker.at(20, 60, 9_000_000)!!.event)         // старое подвисание не закрывается событием
    }

    @Test
    fun `a counter that goes back restarts the baseline`() {
        val tracker = TrafficStallTracker()
        tracker.at(0, 5_000, 5_000)
        assertNull(tracker.at(5, 100, 100))
        val step = tracker.at(10, 1_100, 400_000)!!
        assertEquals(1_000L, step.uplinkBytes)
        assertEquals(399_900L, step.downlinkBytes)
    }
}
