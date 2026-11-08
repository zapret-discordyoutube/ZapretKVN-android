package io.github.zapretkvn.runtimelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedClassTest {
    private val kb = 1024L

    @Test
    fun `thresholds match what YouTube video needs`() {
        assertEquals(SpeedClass.VerySlow, SpeedClass.of(7 * kb))      // сочащийся поток из диагностики 0.5.38
        assertEquals(SpeedClass.VerySlow, SpeedClass.of(50 * kb - 1))
        assertEquals(SpeedClass.Slow, SpeedClass.of(50 * kb))         // 360p
        assertEquals(SpeedClass.Slow, SpeedClass.of(199 * kb))
        assertEquals(SpeedClass.Normal, SpeedClass.of(300 * kb))      // 720p
        assertEquals(SpeedClass.Normal, SpeedClass.of(600 * kb - 1))
        assertEquals(SpeedClass.Fast, SpeedClass.of(600 * kb))        // 1080p
        assertEquals(SpeedClass.Fast, SpeedClass.of(14L * 1024 * kb)) // 14 МБ/с из диагностики 0.5.33
    }

    @Test
    fun `short or tiny flows are not graded`() {
        assertNull(SpeedClass.ofFlow(10 * kb, 5_000))
        assertNull(SpeedClass.ofFlow(5L * 1024 * kb, 500))
        assertEquals(SpeedClass.Normal, SpeedClass.ofFlow(2L * 1024 * kb, 5_000)) // 2 МБ за 5 с = 410 КБ/с
    }

    @Test
    fun `tunnel grade uses window loss then total loss and the smoothed rtt`() {
        val base = "tunnel quic: rtt min=51ms latest=54ms smoothed=%dms dev=3ms; total sent %d pkts 1MB, recv 9 pkts 1MB, lost %d pkts"
        assertEquals("хороший", TunnelGrade.of(base.format(56, 1_000, 1)))
        assertEquals("средний", TunnelGrade.of(base.format(56, 1_000, 15)))   // 1.5% за всё время
        assertEquals("плохой", TunnelGrade.of(base.format(56, 1_000, 40)))
        assertEquals("средний", TunnelGrade.of(base.format(130, 1_000, 0)))
        assertEquals("плохой", TunnelGrade.of(base.format(300, 1_000, 0)))
        val windowed = base.format(56, 1_000, 40) + "; last 5.0s: sent 100 pkts 1KB (1KB/s), recv 5 pkts 1KB (1KB/s), lost 0 (0.00% of sent)"
        assertEquals("окно важнее всего времени", "хороший", TunnelGrade.of(windowed))
    }

    @Test
    fun `an unparsable line gives no grade`() {
        assertNull(TunnelGrade.of("something else"))
        assertNull(TunnelGrade.of("tunnel quic: rtt min=1ms"))
    }
}
