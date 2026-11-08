package io.github.zapretkvn.runtimelog

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeLogStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private val stores = mutableListOf<RuntimeLogStore>()

    @Before
    fun setUp() {
        dir = temp.newFolder("log")
    }

    @After
    fun tearDown() = stores.forEach { it.close() }

    private fun store(
        max: Long = 10_000,
        segment: Long = 2_000,
        filter: LogNoiseFilter = LogNoiseFilter.Default,
        queue: Int = 4_096,
    ) = RuntimeLogStore(dir, max, segment, queue, filter).also(stores::add)

    private fun segmentFiles() = dir.listFiles().orEmpty().filter { it.name.endsWith(".zkvn") }.sortedBy { it.name }

    private fun decodeAll(): List<LogRecord> = segmentFiles().flatMap { file ->
        val lines = file.readLines()
        val base = RuntimeLogCodec.parseHeader(lines.first())!!
        lines.drop(1).mapNotNull { RuntimeLogCodec.decode(base, it) }
    }

    @Test
    fun `everything accepted is on disk after flush and keeps domains and addresses`() {
        val store = store(max = 1_000_000, segment = 100_000)
        store.log(LogLayer.Dns, 5, "dns: exchanged rr3---sn-x.googlevideo.com. IN A 142.250.1.5 78ms")
        store.log(LogLayer.Outbound, 2, "connection closed: received a stateless reset")
        assertTrue(store.flush())

        val records = decodeAll()
        assertEquals(2, records.size)
        assertTrue(records[0].text.contains("googlevideo.com") && records[0].text.contains("142.250.1.5"))
        assertEquals(LogLayer.Outbound, records[1].layer)
        assertEquals(2, store.info().writtenLines.toInt())
        assertEquals(0L, store.info().droppedLines)
    }

    @Test
    fun `total size never exceeds the cap and the oldest segments go first`() {
        val store = store(max = 10_000, segment = 2_000)
        repeat(400) { store.log(LogLayer.Core, 4, "line number $it padding padding padding padding") }
        assertTrue(store.flush())

        assertTrue("total ${store.info().totalBytes}", store.info().totalBytes <= 10_000)
        assertTrue("disk ${segmentFiles().sumOf { it.length() }}", segmentFiles().sumOf { it.length() } <= 10_000)
        val records = decodeAll()
        assertTrue("newest line kept", records.last().text.startsWith("line number 399"))
        assertFalse("oldest line gone", records.any { it.text.startsWith("line number 0 ") })
        assertTrue("sequence only grows", segmentFiles().map { it.name } == segmentFiles().map { it.name }.sorted())
        assertTrue(store.info().segmentCount >= 2)
    }

    @Test
    fun `nothing is thrown away below the cap`() {
        val store = store(max = 1_000_000, segment = 2_000)
        repeat(300) { store.log(LogLayer.Traffic, 4, "sample $it") }
        assertTrue(store.flush())
        assertEquals(300, decodeAll().size)
        assertTrue("rotated into several segments", segmentFiles().size > 1)
    }

    @Test
    fun `noise is not written and is counted`() {
        val store = store(max = 1_000_000, segment = 100_000)
        store.log(LogLayer.Core, 6, "TRACE[0830] [1600097123 1m23s] connection: packet upload closed")
        store.log(LogLayer.Core, 6, "TRACE[0830] [1600097123 1m23s] connection: packet download closed")
        store.log(LogLayer.Core, 6, "TRACE[0830] [1 1ms] something else useful")
        store.log(LogLayer.Core, 4, "INFO connection: packet upload closed")
        assertTrue(store.flush())
        assertEquals(2, decodeAll().size)
        assertEquals(2L, store.info().filteredLines)
    }

    @Test
    fun `a new store continues the sequence and keeps the old segments within the cap`() {
        store(max = 1_000_000, segment = 100_000).also {
            it.log(LogLayer.App, 4, "first run")
            it.flush()
            it.close()
        }
        val second = store(max = 1_000_000, segment = 100_000)
        second.log(LogLayer.App, 4, "second run")
        assertTrue(second.flush())
        assertEquals(listOf("first run", "second run"), decodeAll().map { it.text })
        assertEquals(2, segmentFiles().size)
    }

    @Test
    fun `an existing log over the cap is trimmed on start`() {
        store(max = 10_000, segment = 2_000).also {
            repeat(300) { n -> it.log(LogLayer.Core, 4, "old line $n with some padding padding padding") }
            it.flush()
            it.close()
        }
        val reopened = store(max = 6_000, segment = 2_000)
        assertTrue(reopened.info().totalBytes <= 6_000)
    }

    @Test
    fun `zip holds the extra files and every segment and survives a concurrent writer`() {
        val store = store(max = 1_000_000, segment = 2_000)
        repeat(100) { store.log(LogLayer.Dns, 5, "dns: query $it example.org") }
        val out = ByteArrayOutputStream()
        store.writeZip(out, listOf(ZipExtra("diagnostic.json", "{\"ok\":true}".toByteArray())))

        val names = mutableListOf<String>()
        var json = ""
        var logBytes = 0
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                names += entry.name
                val bytes = zip.readBytes()
                if (entry.name == "diagnostic.json") json = String(bytes)
                if (entry.name.startsWith("runtime-log/")) logBytes += bytes.size
            }
        }
        assertEquals("{\"ok\":true}", json)
        assertTrue("log-format.txt" in names)
        assertTrue(names.count { it.startsWith("runtime-log/log-") } >= 2)
        assertEquals(store.info().totalBytes.toInt(), logBytes)
    }

    @Test
    fun `clear removes every segment and the store keeps working`() {
        val store = store(max = 1_000_000, segment = 2_000)
        repeat(100) { store.log(LogLayer.Core, 4, "before clear $it") }
        assertTrue(store.clear())
        assertEquals(0L, store.info().totalBytes)
        assertEquals(0, segmentFiles().size)
        store.log(LogLayer.Core, 4, "after clear")
        assertTrue(store.flush())
        assertEquals(listOf("after clear"), decodeAll().map { it.text })
    }

    @Test
    fun `lines are not accepted after close and close is idempotent`() {
        val store = store()
        store.log(LogLayer.App, 4, "kept")
        store.close()
        store.close()
        assertFalse(store.log(LogLayer.App, 4, "late"))
        assertEquals(listOf("kept"), decodeAll().map { it.text })
    }

    @Test
    fun `an overfull queue is counted and announced in the log instead of vanishing silently`() {
        val store = store(max = 1_000_000, segment = 500_000, queue = 2)
        var accepted = 0
        repeat(5_000) { if (store.log(LogLayer.Core, 4, "burst $it")) accepted++ }
        assertTrue(store.flush())
        val info = store.info()
        assertEquals(5_000L, accepted + info.droppedLines)
        assertNotNull(info)
        if (info.droppedLines > 0) {
            assertTrue(decodeAll().any { it.text.startsWith("журнал: очередь переполнена") })
        }
    }

    @Test
    fun `transform runs on the writer side and a failing transform never leaks the raw line`() {
        val store = RuntimeLogStore(
            dir, 1_000_000, 100_000, 4_096, LogNoiseFilter.None,
            transform = { record ->
                if (record.text.contains("boom")) error("redactor failed")
                record.copy(text = record.text.replace("secret-token", "•••"))
            },
        ).also(stores::add)
        store.log(LogLayer.App, 4, "auth secret-token for rr1.googlevideo.com")
        store.log(LogLayer.App, 4, "boom secret-token")
        assertTrue(store.flush())

        val texts = decodeAll().map { it.text }
        assertEquals("auth ••• for rr1.googlevideo.com", texts[0])
        assertFalse("raw line must not be written when the transform fails", texts[1].contains("secret-token"))
    }

    @Test
    fun `segment size must stay below the total cap`() {
        val result = runCatching { RuntimeLogStore(dir, 1_000, 1_000) }
        assertTrue(result.isFailure)
    }
}
