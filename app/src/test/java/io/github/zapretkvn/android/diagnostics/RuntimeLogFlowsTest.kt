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
            RuntimeLog.install(File(temp.newFolder("runtime-log"), "x"), "test start")
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
