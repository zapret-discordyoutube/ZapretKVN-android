package io.github.zapretkvn.runtimelog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeedHistoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val mb = 1024L * 1024

    @Test
    fun `targets are cache nodes root domains or address networks`() {
        assertEquals("yt:sn-1giz7n7l", SpeedHistory.targetOf("rr2---sn-1giz7n7l.googlevideo.com", ""))
        assertEquals("yt:sn-1giz7n7l", SpeedHistory.targetOf("rr5.sn-1giz7n7l.googlevideo.com.", ""))
        assertEquals("yt:sn-oj1gi5-5b", SpeedHistory.targetOf("RR4---SN-OJ1GI5-5B.GoogleVideo.com", ""))
        assertEquals("yt:googlevideo", SpeedHistory.targetOf("redirector.googlevideo.com", ""))
        assertEquals("ajay.app", SpeedHistory.targetOf("sponsor.ajay.app", ""))
        assertEquals("ip:74.125.162.0/24", SpeedHistory.targetOf("", "74.125.162.225:443"))
        assertEquals("ip6:2001:4860:4840", SpeedHistory.targetOf("", "[2001:4860:4840:400::]:443"))
        assertEquals("?", SpeedHistory.targetOf("", ""))
    }

    @Test
    fun `speed is a running average and slow and fast flows are counted`() {
        val history = SpeedHistory()
        val host = "rr2---sn-x.googlevideo.com"
        history.recordFlow("Hipge", host, "", 100 * 1024, 10_000)       // 10 КБ/с: очень медленно
        history.recordFlow("Hipge", host, "", 100 * 1024, 10_000)
        history.recordFlow("Hipge", host, "", 6 * mb, 10_000)           // 614 КБ/с: быстро
        history.recordFlow("Hipge", host, "", 1024, 100)                // слишком мало данных: только счётчик
        val entry = history.entry("Hipge", host)!!
        assertEquals(4, entry.flows)
        assertEquals(3, entry.graded)
        assertEquals(2, entry.slow)
        assertEquals(1, entry.fast)
        // 100 КБ за 10 с = 10 240 Б/с (дважды), затем 6 МБ за 10 с = 629 145 Б/с (614 КБ/с)
        assertEquals("ewma: (10240*7 + 629145*3) / 10", (10_240L * 7 + 629_145L * 3) / 10, entry.ewma)
    }

    @Test
    fun `a cache node is known slow only after enough mostly slow graded flows`() {
        val history = SpeedHistory()
        val host = "rr5---sn-1giz7n7l.googlevideo.com"
        repeat(2) { history.recordFlow("Hipge", host, "", 80 * 1024, 20_000) }
        assertFalse("two flows are too few", history.isKnownSlow("Hipge", host))
        history.recordFlow("Hipge", host, "", 80 * 1024, 20_000)
        assertTrue(history.isKnownSlow("Hipge", host))
        assertFalse("another server keeps its own history", history.isKnownSlow("Senge", host))
        repeat(6) { history.recordFlow("Senge", host, "", 8 * mb, 10_000) }
        assertFalse(history.isKnownSlow("Senge", host))
    }

    @Test
    fun `resets are counted per server and target`() {
        val history = SpeedHistory()
        history.recordReset("Hipge", "rr2---sn-x.googlevideo.com")
        history.recordReset("Hipge", "rr9.sn-x.googlevideo.com")
        assertEquals(2, history.entry("Hipge", "rr1---sn-x.googlevideo.com")!!.resets)
    }

    @Test
    fun `nothing is lost when the history is compacted`() {
        val history = SpeedHistory(maxEntries = 20)
        repeat(200) { i -> history.recordFlow("Hipge", "rr1---sn-n$i.googlevideo.com", "", 1 * mb, 10_000) }
        assertTrue("bounded: ${history.entryCount()}", history.entryCount() <= 21)
        val kept = (0 until 200).mapNotNull { history.entry("Hipge", "rr1---sn-n$it.googlevideo.com") }
        val other = history.otherFor("Hipge")
        assertNotNull("the merged totals live in the other bucket", other)
        assertEquals("flows survive", 200L, kept.sumOf { it.flows } + other!!.flows)
        assertEquals("bytes survive", 200 * mb, kept.sumOf { it.bytes } + other.bytes)
    }

    @Test
    fun `the history survives a restart through the file and is written atomically`() {
        val file = File(temp.newFolder("h"), "speed-history.txt")
        var now = 1_794_000_000_000L
        val first = SpeedHistory(file, { now })
        first.recordFlow("🇩🇪 Hipge", "rr2---sn-x.googlevideo.com", "", 3 * mb, 10_000)
        first.recordReset("🇩🇪 Hipge", "rr2---sn-x.googlevideo.com")
        first.flushIfDirty(force = true)
        assertTrue(file.isFile)
        assertFalse("no temporary file is left", File(file.parentFile, file.name + ".tmp").exists())

        val second = SpeedHistory(file, { now })
        val entry = second.entry("🇩🇪 Hipge", "rr2---sn-x.googlevideo.com")!!
        assertEquals(1, entry.flows)
        assertEquals(1, entry.resets)
        assertEquals(3 * mb, entry.bytes)
        assertEquals(first.entry("🇩🇪 Hipge", "rr2---sn-x.googlevideo.com")!!.ewma, entry.ewma)
    }

    @Test
    fun `writes are throttled and a damaged file is ignored`() {
        val file = File(temp.newFolder("h2"), "speed-history.txt")
        var now = 1_794_000_000_000L
        val history = SpeedHistory(file, { now })
        history.recordFlow("S", "a.example.com", "", 1 * mb, 5_000)
        history.flushIfDirty(minIntervalMillis = 60_000)
        val firstWrite = file.length()
        history.recordFlow("S", "b.example.com", "", 1 * mb, 5_000)
        now += 10_000
        history.flushIfDirty(minIntervalMillis = 60_000)
        assertEquals("too early: not rewritten", firstWrite, file.length())
        now += 60_000
        history.flushIfDirty(minIntervalMillis = 60_000)
        assertTrue(file.length() > firstWrite)

        file.writeText("garbage\nnot a history", Charsets.UTF_8)
        assertEquals(0, SpeedHistory(file).entryCount())
        file.writeText("${SpeedHistory.HEADER}\nbroken\tline\n", Charsets.UTF_8)
        assertEquals(0, SpeedHistory(file).entryCount())
    }

    @Test
    fun `the summary names the server the speed class and the slow and fast cache nodes`() {
        val history = SpeedHistory()
        repeat(3) { history.recordFlow("🇩🇪 Hipge", "rr5---sn-1giz7n7l.googlevideo.com", "", 80 * 1024, 20_000) }
        repeat(3) { history.recordFlow("🇩🇪 Hipge", "rr1---sn-u15hn5-51.googlevideo.com", "", 6 * mb, 10_000) }
        history.recordReset("🇩🇪 Hipge", "rr5---sn-1giz7n7l.googlevideo.com")
        val lines = history.summary()
        assertTrue(lines.first().startsWith("история скоростей: записей 2"))
        val server = lines.single { it.startsWith("история 🇩🇪 Hipge:") }
        assertTrue(server, "сбросов 1" in server)
        assertTrue(server, "медленные узлы: sn-1giz7n7l" in server)
        assertTrue(server, "быстрые узлы: sn-u15hn5-51" in server)
    }

    @Test
    fun `an empty history has no summary`() {
        assertTrue(SpeedHistory().summary().isEmpty())
    }
}
