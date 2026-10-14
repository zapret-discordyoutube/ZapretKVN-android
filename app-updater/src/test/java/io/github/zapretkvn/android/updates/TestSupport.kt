package io.github.zapretkvn.android.updates

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Описание записи синтетического ZIP/APK. */
class TestEntry(
    val name: String,
    val data: ByteArray,
    val deflate: Boolean = false,
    /** Выравнивание данных stored-записи через extra локального заголовка, как делает zipalign. */
    val align: Int = 0,
    val dataDescriptor: Boolean = false,
)

object TestZips {
    fun randomBytes(size: Int, seed: Long): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun le(value: Long, bytes: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array().copyOf(bytes)

    private fun deflated(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(data) }
        return out.toByteArray()
    }

    /**
     * Собирает ZIP с контролем всех полей: выравнивание, data descriptor, APK Signing Block и комментарий EOCD.
     */
    fun build(
        entries: List<TestEntry>,
        signingPayload: ByteArray? = null,
        comment: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val directory = ByteArrayOutputStream()
        for (entry in entries) {
            val name = entry.name.toByteArray(Charsets.ISO_8859_1)
            val compressed = if (entry.deflate) deflated(entry.data) else entry.data
            val crc = CRC32().also { it.update(entry.data) }.value
            val offset = out.size().toLong()
            val padding = if (entry.align > 0 && !entry.deflate) {
                ((entry.align - ((offset + 30 + name.size) % entry.align)) % entry.align).toInt()
            } else {
                0
            }
            val flags = if (entry.dataDescriptor) 0x8 else 0
            val method = if (entry.deflate) 8 else 0
            out.write(le(0x04034b50, 4)); out.write(le(20, 2)); out.write(le(flags.toLong(), 2))
            out.write(le(method.toLong(), 2)); out.write(le(0, 2)); out.write(le(0x21, 2))
            out.write(le(if (entry.dataDescriptor) 0 else crc, 4))
            out.write(le(if (entry.dataDescriptor) 0 else compressed.size.toLong(), 4))
            out.write(le(if (entry.dataDescriptor) 0 else entry.data.size.toLong(), 4))
            out.write(le(name.size.toLong(), 2)); out.write(le(padding.toLong(), 2))
            out.write(name); out.write(ByteArray(padding)); out.write(compressed)
            if (entry.dataDescriptor) {
                out.write(le(0x08074b50, 4)); out.write(le(crc, 4))
                out.write(le(compressed.size.toLong(), 4)); out.write(le(entry.data.size.toLong(), 4))
            }
            directory.write(le(0x02014b50, 4)); directory.write(le(20, 2)); directory.write(le(20, 2))
            directory.write(le(flags.toLong(), 2)); directory.write(le(method.toLong(), 2))
            directory.write(le(0, 2)); directory.write(le(0x21, 2))
            directory.write(le(crc, 4)); directory.write(le(compressed.size.toLong(), 4))
            directory.write(le(entry.data.size.toLong(), 4)); directory.write(le(name.size.toLong(), 2))
            directory.write(le(0, 2)); directory.write(le(0, 2)); directory.write(le(0, 2))
            directory.write(le(0, 2)); directory.write(le(0, 4)); directory.write(le(offset, 4))
            directory.write(name)
        }
        if (signingPayload != null) {
            val size = signingPayload.size + 24L
            out.write(le(size, 8)); out.write(signingPayload); out.write(le(size, 8))
            out.write("APK Sig Block 42".toByteArray(Charsets.ISO_8859_1))
        }
        val cdOffset = out.size().toLong()
        val cd = directory.toByteArray()
        out.write(cd)
        out.write(le(0x06054b50, 4)); out.write(le(0, 2)); out.write(le(0, 2))
        out.write(le(entries.size.toLong(), 2)); out.write(le(entries.size.toLong(), 2))
        out.write(le(cd.size.toLong(), 4)); out.write(le(cdOffset, 4)); out.write(le(comment.size.toLong(), 2))
        out.write(comment)
        return out.toByteArray()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun tempDir(): File = Files.createTempDirectory("zapret-updater-test").toFile().also(File::deleteOnExit)

    /** Пара «установленный» и «новый» APK: тяжёлые библиотеки совпадают, остальное изменено. */
    fun apkPair(): Pair<ByteArray, ByteArray> {
        fun apk(version: Int, signing: Long) = build(
            listOf(
                TestEntry("AndroidManifest.xml", randomBytes(2_000, 100L + version), deflate = true),
                TestEntry("classes.dex", randomBytes(150_000, 200L + version), deflate = true),
                TestEntry("lib/arm64-v8a/libbox.so", randomBytes(400_000, 7), align = 4096),
                TestEntry("res/small.png", randomBytes(3_000, 8), align = 4),
                TestEntry("lib/arm64-v8a/libgojni.so", randomBytes(250_000, 9), align = 4096),
                TestEntry("resources.arsc", randomBytes(30_000, 300L + version), align = 4),
            ),
            signingPayload = randomBytes(1_500, signing),
        )
        return apk(1, 11) to apk(2, 22)
    }
}

/** Сервер с поддержкой Range; считает отданные байты и запросы. */
class FakeRangeHttp(
    val files: MutableMap<String, ByteArray> = mutableMapOf(),
    private val supportsRange: Boolean = true,
    /** Сколько первых чтений тела оборвать на середине (проверка докачки). */
    var interruptFirstReads: Int = 0,
    var corruptAt: Long = -1,
) : UpdateHttpClient {
    val requests = mutableListOf<Pair<Long, Long?>>()
    var servedBytes = 0L

    override fun open(url: String, start: Long, endInclusive: Long?): UpdateResponse {
        val data = files[url] ?: throw UpdateException("Forgejo Release пока не опубликован.")
        requests += start to endInclusive
        val ranged = start > 0 || endInclusive != null
        if (ranged && !supportsRange) {
            return UpdateResponse(200, 0, data.size.toLong(), data.size.toLong(), data.inputStream())
        }
        if (start >= data.size) return UpdateResponse(416, 0, data.size.toLong(), 0, ByteArray(0).inputStream())
        val end = minOf(endInclusive ?: (data.size - 1L), data.size - 1L)
        var slice = data.copyOfRange(start.toInt(), (end + 1).toInt())
        if (corruptAt in start..end) slice[(corruptAt - start).toInt()] = (slice[(corruptAt - start).toInt()] + 1).toByte()
        servedBytes += slice.size
        val interrupt = interruptFirstReads > 0 && slice.size > 10
        if (interrupt) {
            interruptFirstReads--
            val half = slice.size / 2
            servedBytes -= slice.size - half
            slice = slice.copyOf(half)
        }
        val body = if (interrupt) {
            object : java.io.InputStream() {
                private val inner = slice.inputStream()
                override fun read(): Int = inner.read().takeIf { it >= 0 } ?: throw java.io.IOException("reset")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val count = inner.read(b, off, len)
                    return if (count < 0) throw java.io.IOException("reset") else count
                }
            }
        } else {
            slice.inputStream()
        }
        return if (ranged) {
            UpdateResponse(206, start, data.size.toLong(), end - start + 1, body)
        } else {
            UpdateResponse(200, 0, data.size.toLong(), data.size.toLong(), body)
        }
    }
}
