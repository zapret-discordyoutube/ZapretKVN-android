package io.github.zapretkvn.runtimelog

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Подробный журнал на диске: быстрая запись, потолок объёма, ничего не теряется до потолка.
 *
 * Устройство (владелец 2026-11-08: «пиши на диск», «быстро и компактно», «выбрасывать только
 * при файле больше 100 МБ»):
 * - [log] не блокирует вызывающего: запись кладётся в очередь, пишет отдельный поток. Поток ядра
 *   (libbox) никогда не ждёт диск. Очередь большая; если она всё же переполнена, строка
 *   считается в [LogStoreInfo.droppedLines] и в журнал пишется явная пометка, молча ничего не теряется.
 * - Файлы лежат сегментами по [segmentBytes]; когда сумма доходит до [maxTotalBytes], удаляется
 *   старейший сегмент целиком. Сумма не превышает потолок ни в какой момент.
 * - Шум по [noiseFilter] не пишется вовсе и считается в [LogStoreInfo.filteredLines].
 * - Сегмент это текст в формате [RuntimeLogCodec]; архив собирается при выгрузке ([writeZip]),
 *   где текст сжимается в разы.
 */
class RuntimeLogStore(
    private val directory: File,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val segmentBytes: Long = DEFAULT_SEGMENT_BYTES,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val noiseFilter: LogNoiseFilter = LogNoiseFilter.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Правка записи на потоке записи, перед кодированием (например, маскирование секретов): тяжёлое
     * не выполняется на потоке вызывающего, [log] остаётся мгновенным.
     */
    private val transform: (LogRecord) -> LogRecord = { it },
) : Closeable {
    init {
        require(segmentBytes in 1 until maxTotalBytes) { "segmentBytes must be below maxTotalBytes" }
    }

    private sealed interface Item
    private class Entry(val record: LogRecord) : Item
    private class Flush(val done: CountDownLatch) : Item
    private class Clear(val done: CountDownLatch) : Item
    private object Stop : Item

    private class Segment(val file: File, val sequence: Long, val startEpochMillis: Long) {
        val size = AtomicLong(0)
    }

    private val queue = ArrayBlockingQueue<Item>(queueCapacity)
    private val segments = ConcurrentSkipListMap<Long, Segment>()
    private val totalBytes = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val filtered = AtomicLong(0)
    private val written = AtomicLong(0)

    // Только поток записи.
    private var out: BufferedOutputStream? = null
    private var current: Segment? = null
    private var nextSequence = 1L
    private var pendingDropped = 0L
    private var lastFlushNanos = System.nanoTime()
    private var lastOpenFailureNanos = System.nanoTime() - REOPEN_NANOS
    private val line = StringBuilder(512)

    @Volatile
    private var closed = false

    private val writer = Thread(::writeLoop, "runtime-log-writer").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
    }

    init {
        directory.mkdirs()
        scanExisting()
        writer.start()
    }

    /** Записать строку. Не блокирует; `false` — отброшена (шум или переполнение очереди). */
    fun log(layer: LogLayer, level: Int, text: String): Boolean {
        if (closed) return false
        if (noiseFilter.isNoise(layer, level, text)) {
            filtered.incrementAndGet()
            return false
        }
        if (!queue.offer(Entry(LogRecord(clock(), layer, level, text)))) {
            dropped.incrementAndGet()
            return false
        }
        return true
    }

    /** Дождаться, пока всё принятое лежит в файле (не дольше [timeoutMillis]). */
    fun flush(timeoutMillis: Long = FLUSH_TIMEOUT_MILLIS): Boolean {
        if (closed) return true
        val done = CountDownLatch(1)
        if (!offerControl(Flush(done), timeoutMillis)) return false
        return done.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** Удалить все сегменты (кнопка «очистить журнал»). */
    fun clear(timeoutMillis: Long = FLUSH_TIMEOUT_MILLIS): Boolean {
        if (closed) return false
        val done = CountDownLatch(1)
        if (!offerControl(Clear(done), timeoutMillis)) return false
        return done.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    fun info(): LogStoreInfo = LogStoreInfo(
        totalBytes = totalBytes.get(),
        segmentCount = segments.size,
        oldestEpochMillis = segments.firstEntry()?.value?.startEpochMillis,
        writtenLines = written.get(),
        droppedLines = dropped.get(),
        filteredLines = filtered.get(),
        maxTotalBytes = maxTotalBytes,
    )

    /**
     * Архив для выгрузки: [extraEntries] (сводка диагностики и т. п.), затем сегменты по
     * порядку. Текст сжимается; уровень 3 даёт ~8–10× за секунды даже на 100 МБ.
     */
    fun writeZip(output: OutputStream, extraEntries: List<ZipExtra> = emptyList()) {
        flush()
        val snapshot = segments.values.toList()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(ZIP_LEVEL)
            extraEntries.forEach { extra ->
                zip.putNextEntry(ZipEntry(extra.name))
                zip.write(extra.bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("log-format.txt"))
            zip.write(FORMAT_README.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            snapshot.forEach { segment ->
                // Размер берётся сейчас: последний сегмент ещё растёт, читаем ровно столько.
                val limit = segment.size.get()
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                try {
                    segment.file.inputStream().use { input ->
                        zip.putNextEntry(ZipEntry("runtime-log/${segment.file.name}"))
                        var remaining = limit
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) break
                            zip.write(buffer, 0, read)
                            remaining -= read
                        }
                        zip.closeEntry()
                    }
                } catch (_: java.io.FileNotFoundException) {
                    // Старейший сегмент удалили по потолку, пока шла выгрузка: он уже не нужен.
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        offerControl(Stop, FLUSH_TIMEOUT_MILLIS)
        writer.join(FLUSH_TIMEOUT_MILLIS)
    }

    private fun offerControl(item: Item, timeoutMillis: Long): Boolean =
        queue.offer(item, timeoutMillis, TimeUnit.MILLISECONDS)

    // ── поток записи ────────────────────────────────────────────────────────────────

    private fun writeLoop() {
        val batch = ArrayList<Item>(BATCH_SIZE)
        var running = true
        while (running) {
            val first = try {
                queue.poll(IDLE_FLUSH_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }
            if (first == null) {
                flushStream()
                continue
            }
            batch += first
            queue.drainTo(batch, BATCH_SIZE - 1)
            for (item in batch) {
                when (item) {
                    is Entry -> write(item.record)
                    is Flush -> {
                        flushStream()
                        item.done.countDown()
                    }
                    is Clear -> {
                        doClear()
                        item.done.countDown()
                    }
                    Stop -> running = false
                }
            }
            batch.clear()
            val missed = dropped.get()
            if (missed > pendingDropped && running) {
                write(LogRecord(clock(), LogLayer.App, 3, "журнал: очередь переполнена, потеряно строк: ${missed - pendingDropped}"))
                pendingDropped = missed
            }
            if (System.nanoTime() - lastFlushNanos > FLUSH_EVERY_NANOS) flushStream()
        }
        flushStream()
        closeSegment()
    }

    private fun write(original: LogRecord) {
        val record = try {
            transform(original)
        } catch (_: RuntimeException) {
            // Сбой преобразователя не должен ни ронять запись, ни пропускать строку без маскирования.
            original.copy(text = "<строка не записана: сбой маскирования>")
        }
        if (out == null) {
            // Сегмент не открылся (диск недоступен): повтор не чаще раза в REOPEN_NANOS,
            // между попытками строки считаются потерянными, VPN от этого не страдает.
            if (System.nanoTime() - lastOpenFailureNanos < REOPEN_NANOS) {
                dropped.incrementAndGet()
                return
            }
            current = null
        }
        val segment = current ?: openSegment().also { current = it }
        if (out == null) {
            lastOpenFailureNanos = System.nanoTime()
            dropped.incrementAndGet()
            return
        }
        line.setLength(0)
        RuntimeLogCodec.encode(segment.startEpochMillis, record, line)
        val bytes = line.toString().toByteArray(Charsets.UTF_8)
        if (segment.size.get() + bytes.size > segmentBytes && segment.size.get() > headerLength(segment)) {
            val next = rotate()
            writeBytes(next, bytes)
        } else {
            writeBytes(segment, bytes)
        }
        written.incrementAndGet()
    }

    private fun writeBytes(segment: Segment, bytes: ByteArray) {
        val stream = out ?: return
        try {
            stream.write(bytes)
        } catch (_: java.io.IOException) {
            // Диск недоступен: журнал не должен ронять работу VPN. Строка считается потерянной.
            dropped.incrementAndGet()
            return
        }
        segment.size.addAndGet(bytes.size.toLong())
        totalBytes.addAndGet(bytes.size.toLong())
    }

    private fun headerLength(segment: Segment): Long =
        RuntimeLogCodec.header(segment.startEpochMillis, segment.sequence).toByteArray(Charsets.UTF_8).size.toLong()

    private fun rotate(): Segment {
        flushStream()
        closeSegment()
        val next = openSegment()
        current = next
        return next
    }

    private fun openSegment(): Segment {
        // Место под новый сегмент освобождается заранее: сумма не должна превысить потолок.
        enforceCap(reserve = segmentBytes)
        val sequence = nextSequence++
        val segment = Segment(File(directory, segmentName(sequence)), sequence, clock())
        val stream = try {
            BufferedOutputStream(FileOutputStream(segment.file, true), STREAM_BUFFER_BYTES)
        } catch (_: java.io.IOException) {
            out = null
            return segment
        }
        out = stream
        segments[sequence] = segment
        val header = RuntimeLogCodec.header(segment.startEpochMillis, sequence).toByteArray(Charsets.UTF_8)
        writeBytes(segment, header)
        return segment
    }

    private fun closeSegment() {
        try {
            out?.close()
        } catch (_: java.io.IOException) {
        }
        out = null
        current = null
    }

    private fun flushStream() {
        try {
            out?.flush()
        } catch (_: java.io.IOException) {
        }
        lastFlushNanos = System.nanoTime()
    }

    /** Удаляет старейшие сегменты, пока сумма вместе с [reserve] не помещается в потолок. */
    private fun enforceCap(reserve: Long) {
        while (totalBytes.get() + reserve > maxTotalBytes) {
            val oldest = segments.firstEntry() ?: return
            if (oldest.value === current) return
            segments.remove(oldest.key)
            val size = oldest.value.size.get()
            oldest.value.file.delete()
            totalBytes.addAndGet(-size)
        }
    }

    private fun doClear() {
        closeSegment()
        segments.values.forEach { it.file.delete() }
        segments.clear()
        totalBytes.set(0)
    }

    private fun scanExisting() {
        directory.listFiles().orEmpty()
            .mapNotNull { file -> SEGMENT_NAME.matchEntire(file.name)?.let { file to it.groupValues[1].toLong() } }
            .sortedBy { it.second }
            .forEach { (file, sequence) ->
                val start = file.bufferedReader(Charsets.UTF_8).use { it.readLine() }
                    ?.let(RuntimeLogCodec::parseHeader) ?: file.lastModified()
                val segment = Segment(file, sequence, start)
                segment.size.set(file.length())
                segments[sequence] = segment
                totalBytes.addAndGet(file.length())
                nextSequence = maxOf(nextSequence, sequence + 1)
            }
        enforceCap(reserve = segmentBytes)
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES = 100L * 1024 * 1024
        const val DEFAULT_SEGMENT_BYTES = 4L * 1024 * 1024
        const val DEFAULT_QUEUE_CAPACITY = 32_768

        private const val BATCH_SIZE = 512
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val IDLE_FLUSH_MILLIS = 400L
        private const val FLUSH_EVERY_NANOS = 500_000_000L
        private const val REOPEN_NANOS = 5_000_000_000L
        private const val FLUSH_TIMEOUT_MILLIS = 3_000L
        private const val ZIP_LEVEL = 3
        private val SEGMENT_NAME = Regex("""log-(\d{6})\.zkvn""")

        private fun segmentName(sequence: Long): String = String.format(Locale.ROOT, "log-%06d.zkvn", sequence)

        private val FORMAT_README = """
            Zapret KVN runtime log
            Segment file: first line "#zkvn-log 1 <start epoch ms> <sequence>", then one record per line:
              <ms since segment start, base36> <layer letter><level digit> <text>
            Layers: A app, N network, D dns, R routing, T tun, O outbound, F traffic, C core (other core lines).
            Levels: 0 panic, 1 fatal, 2 error, 3 warn, 4 info, 5 debug, 6 trace.
            In the text only backslash, newline and carriage return are escaped (\\ \n \r).
            Domains and IP addresses are kept as they are.
        """.trimIndent() + "\n"
    }
}

/** Файл в выгрузке рядом с сегментами. */
class ZipExtra(val name: String, val bytes: ByteArray)

/** Состояние журнала для экрана диагностики и JSON-отчёта (содержимого строк здесь нет). */
data class LogStoreInfo(
    val totalBytes: Long,
    val segmentCount: Int,
    val oldestEpochMillis: Long?,
    val writtenLines: Long,
    val droppedLines: Long,
    val filteredLines: Long,
    val maxTotalBytes: Long,
)
