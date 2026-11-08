package io.github.zapretkvn.runtimelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowTrackerTest {
    private fun snap(
        id: String,
        domain: String = "rr2.sn-x.googlevideo.com",
        network: String = "tcp",
        created: Long = 1_000,
        closed: Long = 0,
        up: Long = 0,
        down: Long = 0,
        pkg: String = "app.youtube",
    ) = FlowSnap(id, network, domain, "74.125.162.226:443", pkg, "hysteria2", created, closed, up, down)

    @Test
    fun `a closed flow becomes one line with lifetime and both byte totals`() {
        val tracker = FlowTracker()
        tracker.onSnapshot(2_000, listOf(snap("a", up = 2_048, down = 1_000_000)), emptyList())
        val lines = tracker.onSnapshot(13_500, emptyList(), listOf(snap("a", closed = 13_500, up = 2_048, down = 1_048_576)))

        val closed = lines.first().text
        assertTrue(closed, closed.startsWith("закрыт tcp rr2.sn-x.googlevideo.com (app.youtube, hysteria2)"))
        assertTrue(closed, "жил 12.5с" in closed && "вверх 2КБ" in closed && "вниз 1.0МБ" in closed)
        assertTrue("average speed over the lifetime: $closed", "ср. вниз 82КБ/с" in closed)
        assertTrue("window summary follows", lines.last().text.startsWith("потоки: живых 0"))
    }

    @Test
    fun `closed flows are written oldest first although the core lists the newest first`() {
        val tracker = FlowTracker()
        val lines = tracker.onSnapshot(
            10_000,
            emptyList(),
            listOf(snap("new", domain = "newest.example", closed = 9_000), snap("old", domain = "oldest.example", closed = 5_000)),
        )
        val texts = lines.filter { it.text.startsWith("закрыт") }.map { it.text }
        assertEquals(2, texts.size)
        assertTrue(texts[0], "oldest.example" in texts[0] && "newest.example" in texts[1])
    }

    @Test
    fun `window counts only the growth since the previous snapshot`() {
        val tracker = FlowTracker()
        tracker.onSnapshot(5_000, listOf(snap("a", down = 1_000_000)), emptyList())
        val second = tracker.onSnapshot(10_000, listOf(snap("a", down = 1_500_000)), emptyList()).first().text
        assertTrue(second, "вниз 488КБ" in second)
        val third = tracker.onSnapshot(15_000, listOf(snap("a", down = 1_500_000)), emptyList()).first().text
        assertTrue(third, "вниз 0Б" in third && "топ" !in third)
    }

    @Test
    fun `top flows are ordered by incoming bytes and limited`() {
        val tracker = FlowTracker(topN = 2)
        val text = tracker.onSnapshot(
            5_000,
            listOf(
                snap("a", domain = "big.example", down = 3_000_000),
                snap("b", domain = "mid.example", down = 500_000),
                snap("c", domain = "small.example", down = 1_000),
            ),
            emptyList(),
        ).first().text
        assertTrue(text, text.startsWith("потоки: живых 3"))
        assertTrue(text, "big.example" in text && "mid.example" in text && "small.example" !in text)
        assertTrue("order by downlink", text.indexOf("big.example") < text.indexOf("mid.example"))
    }

    @Test
    fun `old flows that sent data and received nothing in the window are listed as waiting`() {
        val tracker = FlowTracker(waitingAfterMillis = 10_000)
        tracker.onSnapshot(5_000, listOf(snap("a", created = 1, up = 100, down = 50), snap("b", domain = "fine.example", created = 1, up = 100, down = 50)), emptyList())
        val lines = tracker.onSnapshot(
            20_000,
            listOf(snap("a", created = 1, up = 4_196, down = 50), snap("b", domain = "fine.example", created = 1, up = 200, down = 90_050)),
            emptyList(),
        )
        val waiting = lines.single { it.level == 3 }.text
        assertTrue(waiting, waiting.startsWith("ждут ответ (1): rr2.sn-x.googlevideo.com"))
        assertTrue(waiting, "fine.example" !in waiting && "вверх 4КБ" in waiting)
    }

    @Test
    fun `a young silent flow is not reported as waiting`() {
        val tracker = FlowTracker(waitingAfterMillis = 10_000)
        tracker.onSnapshot(5_000, listOf(snap("a", created = 4_000, up = 10)), emptyList())
        val lines = tracker.onSnapshot(8_000, listOf(snap("a", created = 4_000, up = 500)), emptyList())
        assertTrue(lines.none { it.level == 3 })
    }

    @Test
    fun `a flow that vanished without a close record is forgotten`() {
        val tracker = FlowTracker()
        tracker.onSnapshot(5_000, listOf(snap("a"), snap("b")), emptyList())
        val text = tracker.onSnapshot(10_000, listOf(snap("b")), emptyList()).first().text
        assertTrue(text, text.startsWith("потоки: живых 1"))
    }

    @Test
    fun `reset forgets everything so a new session starts without old deltas`() {
        val tracker = FlowTracker()
        tracker.onSnapshot(5_000, listOf(snap("a", down = 9_000_000)), emptyList())
        tracker.reset()
        val text = tracker.onSnapshot(10_000, listOf(snap("a", down = 9_000_100)), emptyList()).first().text
        assertTrue("after reset the whole total counts as the first growth: $text", "вниз 8.6МБ" in text)
    }

    @Test
    fun `the table is bounded when close records are lost`() {
        val tracker = FlowTracker(maxTracked = 10)
        val many = (0 until 100).map { snap("id$it") }
        // Живых больше предела: таблица не растёт без границы, лишние самые старые вытесняются.
        val text = tracker.onSnapshot(5_000, many, emptyList()).first().text
        assertTrue(text, text.startsWith("потоки: живых 10"))
    }

    @Test
    fun `byte and duration formatting is stable and locale independent`() {
        assertEquals("0Б", FlowTracker.formatBytes(0))
        assertEquals("1КБ", FlowTracker.formatBytes(1_024))
        assertEquals("1.5МБ", FlowTracker.formatBytes(1_572_864))
        assertEquals("250мс", FlowTracker.formatDuration(250))
        assertEquals("1.5с", FlowTracker.formatDuration(1_500))
        assertEquals("2м05с", FlowTracker.formatDuration(125_000))
    }
}
