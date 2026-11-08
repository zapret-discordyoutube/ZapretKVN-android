package io.github.zapretkvn.android.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeLogFlowsTest {
    companion object {
        // RuntimeLog одиночка на процесс: первая установка побеждает, поэтому папка и установка одни на весь класс.
        @get:ClassRule
        @JvmStatic
        val temp = TemporaryFolder()

        @BeforeClass
        @JvmStatic
        fun install() {
            RuntimeLog.install(
                File(temp.newFolder("runtime-log"), "x"),
                "test start",
                File(temp.newFolder("history"), "speed-history.txt"),
            )
        }
    }

    private fun logText(): String {
        val out = ByteArrayOutputStream()
        RuntimeLog.writeZip(out, emptyList())
        val builder = StringBuilder()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val bytes = zip.readBytes()
                if (entry.name.startsWith("runtime-log/")) builder.append(String(bytes, Charsets.UTF_8))
            }
        }
        return builder.toString()
    }

    @Test
    fun `flow snapshot json is parsed into log lines and moves the closed cursor`() {
        RuntimeLog.sessionStarted(1)
        assertEquals(0L, RuntimeLog.flowCursor())

        val json = """
            {"at":1794170000000,
             "active":[{"id":"a1","net":"tcp","domain":"rr2.sn-x.googlevideo.com","dest":"74.125.162.226:443",
                        "pkg":"app.youtube","out":"hy2","created":1794169990000,"up":2048,"down":3145728}],
             "closed":[{"id":"c1","net":"udp","dest":"142.250.1.5:443","pkg":"app.youtube","out":"hy2",
                        "created":1794169980000,"closed":1794169995000,"up":100,"down":2000}]}
        """.trimIndent()
        RuntimeLog.flows(json)

        assertEquals("cursor follows the newest closed flow", 1794169995000L, RuntimeLog.flowCursor())
        val text = logText()
        assertTrue(text, "закрыт udp 142.250.1.5:443 (app.youtube, hy2): жил 15.0с" in text)
        assertTrue(text, "потоки: живых 1" in text && "rr2.sn-x.googlevideo.com tcp ↓3.0МБ" in text)
    }

    private fun stalledSnapshot(id: String, domain: String = "rr5.sn-x.googlevideo.com", down: Long = 647): String {
        val created = System.currentTimeMillis() - 20_000
        return """{"at":0,"active":[{"id":"$id","net":"tcp","domain":"$domain","dest":"74.125.11.74:443",
            "pkg":"app.youtube","out":"hy2","created":$created,"up":900,"down":$down}],"closed":[]}"""
    }

    @Test
    fun `a stalled googlevideo flow is returned for closing and the reset is logged`() {
        RuntimeLog.configure(true)
        RuntimeLog.sessionStarted(2)
        val ids = RuntimeLog.flows(stalledSnapshot("stalled-1")).stalledIds
        assertEquals(listOf("stalled-1"), ids)
        val text = logText()
        assertTrue(text, "сброс зависшего потока rr5.sn-x.googlevideo.com tcp" in text)
    }

    @Test
    fun `healthy flows other sites and a disabled switch return nothing`() {
        RuntimeLog.configure(true)
        RuntimeLog.sessionStarted(3)
        assertTrue("healthy", RuntimeLog.flows(stalledSnapshot("healthy", down = 5_000_000)).stalledIds.isEmpty())
        assertTrue("other site", RuntimeLog.flows(stalledSnapshot("other", domain = "sponsor.ajay.app")).stalledIds.isEmpty())
        RuntimeLog.configure(false)
        assertTrue("switched off", RuntimeLog.flows(stalledSnapshot("off")).stalledIds.isEmpty())
        RuntimeLog.configure(true)
    }

    private fun videoSnapshot(id: String, up: Long, down: Long): String {
        val created = System.currentTimeMillis() - 6_000
        return """{"at":0,"active":[{"id":"$id","net":"tcp","domain":"rr1.sn-slow.googlevideo.com","dest":"74.125.11.70:443",
            "pkg":"app.youtube","out":"hy2","created":$created,"up":$up,"down":$down}],"closed":[]}"""
    }

    @Test
    fun `two snapshots of a starving video stream give the starving verdict, a log line and the ids to drop`() {
        RuntimeLog.configure(false) // сброс отключён: проверяется только вердикт
        RuntimeLog.sessionStarted(20)
        assertFalse(RuntimeLog.videoActive())
        val first = RuntimeLog.flows(videoSnapshot("v1", 9_000, 30_000))
        assertFalse("one observation is not enough", first.verdict.starving)
        assertTrue(RuntimeLog.videoActive())
        val second = RuntimeLog.flows(videoSnapshot("v1", 18_000, 34_000))
        assertTrue("second observation confirms: ${second.verdict}", second.verdict.starving)
        assertEquals(listOf("v1"), RuntimeLog.videoFlowIds())
        assertTrue(logText(), "ВИДЕО ГОЛОДАЕТ" in logText())
        RuntimeLog.resetVideoDetector()
        assertFalse(RuntimeLog.videoActive())
        assertTrue(RuntimeLog.videoFlowIds().isEmpty())
        RuntimeLog.configure(true)
    }

    @Test
    fun `tunnel statistics lines are written one per server and blank input is ignored`() {
        RuntimeLog.tunnelStats(null)
        RuntimeLog.tunnelStats("")
        RuntimeLog.tunnelStats("hy2-a: tunnel quic: rtt min=78ms latest=91ms\nhy2-b: tunnel quic: rtt min=40ms latest=44ms\n\n")
        val text = logText()
        assertTrue(text, "hy2-a: tunnel quic: rtt min=78ms" in text && "hy2-b: tunnel quic: rtt min=40ms" in text)
    }

    @Test
    fun `tunnel statistics lines get a channel grade`() {
        RuntimeLog.tunnelStats(
            "hy2-g: tunnel quic: rtt min=51ms latest=54ms smoothed=56ms dev=3ms; total sent 1703 pkts 1MB, recv 9 pkts 1MB, lost 1 pkts",
        )
        RuntimeLog.tunnelStats(
            "hy2-b: tunnel quic: rtt min=51ms latest=54ms smoothed=300ms dev=3ms; total sent 1000 pkts 1MB, recv 9 pkts 1MB, lost 1 pkts",
        )
        val text = logText()
        assertTrue(text, "hy2-g: tunnel quic: rtt min=51ms" in text && "канал: хороший" in text)
        assertTrue(text, "канал: плохой" in text)
    }

    @Test
    fun `a closed googlevideo flow goes into the speed history and is shown in the summary`() {
        RuntimeLog.sessionStarted(9)
        val created = System.currentTimeMillis() - 40_000
        val closed = created + 20_000
        // 100 КБ за 20 с = 5 КБ/с: «очень медленно»; три таких потока делают узел «известным медленным»
        val flows = (1..3).joinToString(",") { n ->
            """{"id":"c$n","net":"tcp","domain":"rr$n---sn-slowtest.googlevideo.com","dest":"74.125.11.74:443","pkg":"app.youtube",
                "out":"HistoryServer","created":$created,"closed":$closed,"up":50000,"down":102400}"""
        }
        RuntimeLog.flows("""{"at":0,"active":[],"closed":[$flows]}""")
        val summary = RuntimeLog.historySummary().joinToString("\n")
        assertTrue(summary, "история HistoryServer: YouTube ср." in summary && "очень медленно" in summary)
        assertTrue(summary, "медленные узлы: sn-slowtest" in summary)
        // и эта сводка попадает в журнал при старте следующей сессии
        RuntimeLog.sessionStarted(10)
        assertTrue(logText().contains("история HistoryServer:"))
        // а файл истории едет в архив диагностики
        val out = ByteArrayOutputStream()
        RuntimeLog.writeZip(out, emptyList())
        val names = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip -> generateSequence { zip.nextEntry }.forEach { names += it.name } }
        assertTrue(names.toString(), "speed-history.txt" in names)
    }

    @Test
    fun `broken empty or null snapshots do not throw and do not move the cursor`() {
        val cursor = RuntimeLog.flowCursor()
        RuntimeLog.flows(null)
        RuntimeLog.flows("")
        RuntimeLog.flows("{not json")
        RuntimeLog.flows("""{"active":"nope","closed":[1,2,{"x":1}]}""")
        assertEquals(cursor, RuntimeLog.flowCursor())
    }
}
