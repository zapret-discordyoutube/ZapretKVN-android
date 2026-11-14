package io.github.zapretkvn.runtimelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowVideoDetectorTest {
    private val kb = 1024L

    private fun video(
        id: String,
        created: Long,
        up: Long,
        down: Long,
        domain: String = "rr1.sn-1giz7n7l.googlevideo.com",
        network: String = "tcp",
    ) = FlowSnap(id, network, domain, "74.125.11.70:443", "app.youtube", "hy2", created, 0, up, down)

    /** Данные архива 0.5.39: здоровый поток берёт 555 КБ за 3 с, потом плеер сидит на потоке через плохой узел. */
    @Test
    fun `the starving stream of the field archive is recognized within a few seconds`() {
        val detector = SlowVideoDetector()
        val start = 100_000L
        // здоровый поток, 3 секунды жизни, потом закрывается (в снимках больше не виден)
        val first = detector.onSnapshot(start + 3_000, listOf(video("fast", start, 3 * kb, 555 * kb), video("slow", start + 1_000, 4 * kb, 8 * kb)))
        assertTrue("a healthy flow is present", first.healthy)
        assertFalse(first.starving)
        // дальше только «сочащийся» поток: 28 КБ за 5 с, он живёт, отправляет не меньше, чем получает
        var starvingAt = -1L
        var down = 8 * kb
        var up = 4 * kb
        var now = start + 3_000
        while (now < start + 20_000 && starvingAt < 0) {
            now += 1_500
            down += 8 * kb
            up += 9 * kb
            val verdict = detector.onSnapshot(now, listOf(video("slow", start + 1_000, up, down)))
            if (verdict.starving) starvingAt = now - start
        }
        assertTrue("recognized after ${starvingAt / 1000.0}s of the stream's life", starvingAt in 1..10_000)
    }

    @Test
    fun `a healthy stream next to a slow one means there is no starvation`() {
        val detector = SlowVideoDetector()
        val start = 50_000L
        detector.onSnapshot(start + 5_000, listOf(video("a", start, 5 * kb, 100 * kb), video("b", start, 5 * kb, 100 * kb)))
        val verdict = detector.onSnapshot(start + 6_500, listOf(video("a", start, 6 * kb, 100 * kb + 8 * kb), video("b", start, 6 * kb, 100 * kb + 2_000 * kb)))
        assertEquals(1, verdict.healthyFlows)
        assertFalse(verdict.starving)
        assertFalse(detector.onSnapshot(start + 8_000, listOf(video("a", start, 7 * kb, 130 * kb), video("b", start, 7 * kb, 4_000 * kb))).starving)
    }

    /** Архив 0.5.48 (2026-11-14 10:18): на рабочем сервере видео взяло МБ, потом две паузы по 5 с и хвост 36 КБ/с. */
    @Test
    fun `a player that was just fed megabytes and pauses is not starving`() {
        val detector = SlowVideoDetector()
        val start = 200_000L
        // поток набирает МБ: три отсчёта по ~1 МБ
        var down = 0L
        var now = start
        repeat(3) {
            now += 5_000
            down += 1_100 * kb
            detector.onSnapshot(now, listOf(video("main", start, 40 * kb, down), video("tail", start, 20 * kb, 60 * kb)))
        }
        // плеер сыт: основной поток молчит, хвост сочится
        var starved = false
        repeat(3) {
            now += 5_000
            starved = starved || detector.onSnapshot(now, listOf(video("main", start, 40 * kb, down), video("tail", start, 20 * kb, 60 * kb + 5 * kb))).starving
        }
        assertFalse("fed player must not be called starving", starved)
    }

    @Test
    fun `a stream that moved megabytes long ago and now only trickles is starving`() {
        val detector = SlowVideoDetector()
        val start = 300_000L
        detector.onSnapshot(start + 5_000, listOf(video("main", start, 40 * kb, 4_000 * kb), video("tail", start, 20 * kb, 60 * kb)))
        var now = start + 5_000
        var starved = false
        repeat(12) {
            now += 5_000
            starved = starved || detector.onSnapshot(now, listOf(video("tail", start, 20 * kb, 60 * kb + (it + 1) * 8 * kb))).starving
        }
        assertTrue("megabytes older than 30 s no longer feed the player", starved)
    }

    @Test
    fun `redirector other hosts udp handshakes and idle preconnects are not video streams`() {
        val detector = SlowVideoDetector()
        val start = 10_000L
        val flows = { down: Long ->
            listOf(
                video("redirector", start, 4 * kb, down, domain = "redirector.googlevideo.com"),
                video("other", start, 4 * kb, down, domain = "youtubei.googleapis.com"),
                video("udp", start, 4 * kb, down, network = "udp"),
                video("handshake", start, 4 * kb, 2 * kb),          // получил меньше 16 КБ: рукопожатие
                video("noname", start, 4 * kb, down, domain = ""),
            )
        }
        detector.onSnapshot(start + 6_000, flows(40 * kb))
        for (step in 1..6) {
            val verdict = detector.onSnapshot(start + 6_000 + step * 1_500L, flows(40 * kb + step * kb))
            assertFalse("step $step: $verdict", verdict.starving)
        }
        assertEquals(1, detector.onSnapshot(start + 20_000, flows(41 * kb)).videoFlows) // только «handshake» это googlevideo
    }

    @Test
    fun `a flow that moved megabytes is not a slow one even when it pauses`() {
        val detector = SlowVideoDetector()
        val start = 10_000L
        detector.onSnapshot(start + 6_000, listOf(video("big", start, 5 * kb, 4_000 * kb)))
        repeat(5) { step ->
            val verdict = detector.onSnapshot(start + 7_500 + step * 1_500L, listOf(video("big", start, 5 * kb + step, 4_000 * kb + step)))
            assertTrue(verdict.slowFlows.isEmpty())
            assertFalse(verdict.starving)
        }
    }

    @Test
    fun `starvation needs consecutive observations and a recovering stream resets it`() {
        val detector = SlowVideoDetector(observationsRequired = 3)
        val start = 10_000L
        var down = 30 * kb
        val slow = { d: Long -> listOf(video("s", start, 9 * kb, d)) }
        assertFalse("observation 1", detector.onSnapshot(start + 5_000, slow(down)).starving)
        down += 5 * kb
        assertFalse("observation 2", detector.onSnapshot(start + 6_500, slow(down)).starving)
        down += 5 * kb
        assertTrue("observation 3 confirms", detector.onSnapshot(start + 8_000, slow(down)).starving)
        // поток ожил: 400 КБ за 1,5 с, голодание снято
        down += 400 * kb
        val recovered = detector.onSnapshot(start + 9_500, slow(down))
        assertFalse(recovered.starving)
        assertTrue(recovered.healthy)
        // новое замедление снова требует трёх отсчётов подряд
        assertFalse(detector.onSnapshot(start + 11_000, slow(down + 5 * kb)).starving)
    }

    @Test
    fun `videoActive tells whether to sample faster and reset forgets everything`() {
        val detector = SlowVideoDetector()
        assertFalse(detector.videoActive)
        detector.onSnapshot(5_000, listOf(video("a", 1_000, kb, 100 * kb)))
        assertTrue(detector.videoActive)
        detector.onSnapshot(6_500, emptyList())
        assertFalse(detector.videoActive)
        detector.reset()
        assertEquals(SlowVideoDetector.Verdict.Idle, detector.onSnapshot(9_000, emptyList()).copy(videoFlows = 0))
    }
}
