package io.github.zapretkvn.android.updates

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Как именно будет получен APK и сколько байт придётся скачать. */
sealed interface DownloadPlan {
    /** Сколько байт придёт по сети. */
    val networkBytes: Long

    /** Сколько байт не придётся скачивать (уже есть в установленном APK или в `.part`). */
    val savedBytes: Long

    data class Full(val size: Long, val resumeFrom: Long = 0) : DownloadPlan {
        override val networkBytes: Long get() = size - resumeFrom
        override val savedBytes: Long get() = resumeFrom
    }

    class Delta(val plan: DeltaPlan) : DownloadPlan {
        override val networkBytes: Long get() = plan.networkBytes
        override val savedBytes: Long get() = plan.savedBytes
    }
}

/** Прогресс: сколько получено, сколько всего нужно и сколько сэкономлено. */
fun interface DownloadProgress {
    fun update(downloadedBytes: Long, totalBytes: Long, savedBytes: Long)
}

/**
 * Скачивает APK: по возможности только изменившиеся части (по HTTP Range против установленного APK),
 * иначе целиком с докачкой `.part`. Итоговый SHA-256 сверяется с опубликованным здесь же; проверку
 * подписи выполняет вызывающий код.
 */
class ApkDownloader(
    private val installedApk: File?,
    private val parallelism: Int = 3,
    private val minReuseBytes: Long = DeltaPlanner.MIN_REUSE_BYTES,
    private val mergeGapBytes: Long = DeltaPlanner.MERGE_GAP_BYTES,
    private val minApkBytesForDelta: Long = 8L * 1024 * 1024,
    private val minSavingBytes: Long = 1024L * 1024,
    private val attempts: Int = 3,
) {
    /**
     * Решает, как качать. Сетевые сбои пробрасываются как [UpdateException]; любая неясность
     * в структуре ZIP даёт полную загрузку.
     */
    suspend fun plan(client: UpdateHttpClient, url: String, expectedSize: Long, partial: File): DownloadPlan {
        val full = DownloadPlan.Full(expectedSize, resumeFrom = resumableBytes(partial, expectedSize))
        if (expectedSize < minApkBytesForDelta) return full
        val installed = installedApk?.takeIf(File::isFile) ?: return full
        return try {
            val old = withContext(Dispatchers.IO) { LocalApk.open(installed) }
            val delta = buildDeltaPlan(client, url, expectedSize, old)
            val cheaper = delta.networkBytes + minSavingBytes <= full.networkBytes
            if (delta.reuse.isNotEmpty() && cheaper) DownloadPlan.Delta(delta) else full
        } catch (_: DeltaUnsupportedException) {
            full
        } catch (_: IOException) {
            // Установленный APK не читается — это не сетевая ошибка, качаем целиком.
            full
        }
    }

    /**
     * Выполняет план и возвращает, когда [partial] содержит весь APK с верным SHA-256.
     * При несовпадении SHA после дельты файл удаляется и APK скачивается целиком.
     */
    suspend fun download(
        client: UpdateHttpClient,
        url: String,
        expectedSize: Long,
        expectedSha256: String,
        plan: DownloadPlan,
        partial: File,
        progress: DownloadProgress,
    ) {
        var current = plan
        if (current is DownloadPlan.Delta) {
            val ok = try {
                executeDelta(client, url, current.plan, partial, progress)
                sha256(partial) == expectedSha256
            } catch (_: DeltaUnsupportedException) {
                false
            } catch (_: IOException) {
                false
            }
            if (ok) return
            partial.delete()
            current = DownloadPlan.Full(expectedSize)
        }
        var resumed = current is DownloadPlan.Full && current.resumeFrom > 0
        while (true) {
            executeFull(client, url, expectedSize, partial, progress)
            if (sha256(partial) == expectedSha256) return
            partial.delete()
            if (!resumed) throw UpdateException("SHA-256 загруженного APK не совпадает с опубликованным.")
            // Докачка могла подхватить повреждённый хвост: один раз качаем с нуля.
            resumed = false
        }
    }

    // ---- дельта ----

    private suspend fun buildDeltaPlan(
        client: UpdateHttpClient,
        url: String,
        size: Long,
        old: LocalApk,
    ): DeltaPlan {
        val probeLength = minOf(size, ZipFormat.TAIL_PROBE_BYTES.toLong())
        val probeStart = size - probeLength
        val probe = fetchBytes(client, url, ByteRange(probeStart, size))
        var networkBytes = probe.size.toLong()
        val eocd = ZipFormat.parseEndOfCentralDirectory(probe, size)
        val prefetchStart = eocd.centralDirectoryOffset - ZipFormat.SIGNING_FOOTER_SIZE
        if (prefetchStart < 0) throw DeltaUnsupportedException("Каталог начинается слишком рано.")
        val block = if (prefetchStart >= probeStart) {
            probe.copyOfRange((prefetchStart - probeStart).toInt(), probe.size)
        } else {
            val head = fetchBytes(client, url, ByteRange(prefetchStart, probeStart))
            networkBytes += head.size
            head + probe
        }
        val cdBytes = block.copyOfRange(
            ZipFormat.SIGNING_FOOTER_SIZE,
            ZipFormat.SIGNING_FOOTER_SIZE + eocd.centralDirectorySize.toInt(),
        )
        val entries = ZipFormat.parseCentralDirectory(cdBytes, eocd)
        val signing = ZipFormat.signingBlockOffset(
            block.copyOfRange(0, ZipFormat.SIGNING_FOOTER_SIZE),
            eocd.centralDirectoryOffset,
        )
        val directory = ZipDirectory(size, entries, eocd.centralDirectoryOffset, eocd.centralDirectorySize, signing)
        return DeltaPlanner.plan(
            directory = directory,
            old = old,
            prefetchedOffset = prefetchStart,
            prefetched = block,
            prefetchedNetworkBytes = networkBytes,
            minReuseBytes = minReuseBytes,
            mergeGapBytes = mergeGapBytes,
        )
    }

    private suspend fun executeDelta(
        client: UpdateHttpClient,
        url: String,
        plan: DeltaPlan,
        partial: File,
        progress: DownloadProgress,
    ) {
        val installed = installedApk ?: throw DeltaUnsupportedException("Нет установленного APK.")
        partial.delete()
        val total = plan.networkBytes
        val done = AtomicLong(plan.prefetchedNetworkBytes)
        progress.update(done.get(), total, plan.savedBytes)
        withContext(Dispatchers.IO) {
            RandomAccessFile(partial, "rw").use { target ->
                target.setLength(plan.fileSize)
                target.seek(plan.prefetchedOffset)
                target.write(plan.prefetched)
                val channel = target.channel
                val permits = Semaphore(parallelism.coerceAtLeast(1))
                coroutineScope {
                    DeltaPlanner.splitRanges(plan.downloads).map { chunk ->
                        async(Dispatchers.IO) {
                            permits.withPermit {
                                fetchInto(client, url, chunk, target) { count ->
                                    progress.update(done.addAndGet(count), total, plan.savedBytes)
                                }
                            }
                        }
                    }.awaitAll()
                }
                verifyLayout(plan, target)
                copyReused(plan, installed, channel)
            }
        }
        coroutineContext.ensureActive()
    }

    /** Локальные заголовки и подписанный блок обязаны лежать там, где их предсказал план. */
    private fun verifyLayout(plan: DeltaPlan, target: RandomAccessFile) {
        for (check in plan.headerChecks) {
            val fixed = ByteArray(ZipFormat.LOCAL_HEADER_MIN_SIZE)
            target.seek(check.offset)
            target.readFully(fixed)
            val length = ZipFormat.localHeaderLength(fixed)
            if (length != check.headerLength) {
                throw DeltaUnsupportedException("Заголовок ${check.name} не там, где ожидалось.")
            }
            val nameBytes = ByteArray(check.name.length)
            target.readFully(nameBytes)
            if (String(nameBytes, Charsets.ISO_8859_1) != check.name) {
                throw DeltaUnsupportedException("Имя в заголовке ${check.name} не совпало.")
            }
        }
        val signing = plan.signingBlockOffset
        if (signing != null) {
            val cdOffset = plan.prefetchedOffset + ZipFormat.SIGNING_FOOTER_SIZE
            val head = ByteArray(8)
            target.seek(signing)
            target.readFully(head)
            val declared = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).long
            if (declared != cdOffset - signing - 8) {
                throw DeltaUnsupportedException("Размер APK Signing Block не совпал.")
            }
        }
    }

    private fun copyReused(plan: DeltaPlan, installed: File, target: java.nio.channels.FileChannel) {
        if (plan.reuse.isEmpty()) return
        RandomAccessFile(installed, "r").use { source ->
            val oldChannel = source.channel
            val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
            for (copy in plan.reuse) {
                var copied = 0L
                while (copied < copy.length) {
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), copy.length - copied).toInt())
                    val read = oldChannel.read(buffer, copy.oldOffset + copied)
                    if (read <= 0) throw IOException("Установленный APK короче ожидаемого.")
                    buffer.flip()
                    var position = copy.newOffset + copied
                    while (buffer.hasRemaining()) position += target.write(buffer, position)
                    copied += read
                }
            }
        }
    }

    // ---- полная загрузка с докачкой ----

    private fun resumableBytes(partial: File, expectedSize: Long): Long =
        partial.takeIf(File::isFile)?.length()?.takeIf { it in 1 until expectedSize } ?: 0

    private suspend fun executeFull(
        client: UpdateHttpClient,
        url: String,
        size: Long,
        partial: File,
        progress: DownloadProgress,
    ) {
        var failure: UpdateException? = null
        repeat(attempts.coerceAtLeast(1)) {
            coroutineContext.ensureActive()
            var existing = partial.takeIf(File::isFile)?.length() ?: 0
            if (existing > size) {
                partial.delete()
                existing = 0
            }
            if (existing == size) return
            try {
                withContext(Dispatchers.IO) { appendFrom(client, url, size, partial, existing, progress) }
                if ((partial.takeIf(File::isFile)?.length() ?: 0) == size) return
                failure = UpdateException("Загрузка APK прервана.", retryViaVpn = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UpdateException) {
                if (!error.retryViaVpn) throw error
                failure = error
            } catch (error: IOException) {
                failure = UpdateException("Загрузка APK прервана.", error, retryViaVpn = true)
            }
        }
        throw failure ?: UpdateException("Загрузка APK прервана.", retryViaVpn = true)
    }

    private suspend fun appendFrom(
        client: UpdateHttpClient,
        url: String,
        size: Long,
        partial: File,
        existing: Long,
        progress: DownloadProgress,
    ) {
        client.open(url, start = existing).use { response ->
            var offset = existing
            when (response.status) {
                416 -> {
                    partial.delete()
                    throw UpdateException("Сервер отклонил докачку.", retryViaVpn = true)
                }
                206 -> {
                    if (response.rangeStart != existing || (response.totalLength > 0 && response.totalLength != size)) {
                        throw UpdateException("Размер ответа не совпадает с Forgejo Release.", retryViaVpn = true)
                    }
                }
                else -> {
                    // Сервер проигнорировал Range и отдаёт файл с начала.
                    if (response.contentLength > 0 && response.contentLength != size) {
                        throw UpdateException("Размер ответа не совпадает с Forgejo Release.", retryViaVpn = true)
                    }
                    offset = 0
                }
            }
            RandomAccessFile(partial, "rw").use { output ->
                output.setLength(offset)
                output.seek(offset)
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                progress.update(offset, size, 0)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = response.body.read(buffer)
                    if (count < 0) break
                    offset += count
                    if (offset > size || offset > UpdateJson.MAX_APK_BYTES) {
                        throw UpdateException("Ответ больше опубликованного размера APK.", retryViaVpn = true)
                    }
                    output.write(buffer, 0, count)
                    progress.update(offset, size, 0)
                }
            }
        }
    }

    // ---- диапазоны ----

    private suspend fun fetchBytes(client: UpdateHttpClient, url: String, range: ByteRange): ByteArray {
        if (range.length > MAX_IN_MEMORY_BYTES) throw DeltaUnsupportedException("Диапазон слишком велик.")
        val output = ByteArray(range.length.toInt())
        var filled = 0
        fetchRange(client, url, range) { buffer, count ->
            buffer.copyInto(output, filled, 0, count)
            filled += count
        }
        return output
    }

    private suspend fun fetchInto(
        client: UpdateHttpClient,
        url: String,
        range: ByteRange,
        target: RandomAccessFile,
        onBytes: (Long) -> Unit,
    ) {
        var position = range.start
        fetchRange(client, url, range) { buffer, count ->
            synchronized(target) {
                target.seek(position)
                target.write(buffer, 0, count)
            }
            position += count
            onBytes(count.toLong())
        }
    }

    /** Загружает [range] целиком, при обрыве докачивая остаток; кусок приходит в [sink]. */
    private suspend fun fetchRange(
        client: UpdateHttpClient,
        url: String,
        range: ByteRange,
        sink: (ByteArray, Int) -> Unit,
    ) {
        var done = 0L
        var failure: UpdateException? = null
        repeat(attempts.coerceAtLeast(1)) {
            try {
                client.open(url, range.start + done, range.endExclusive - 1).use { response ->
                    if (response.status != 206) {
                        // Сервер не умеет Range (200) или отверг диапазон (416) — дельта невозможна.
                        throw DeltaUnsupportedException("Сервер не поддерживает диапазоны (HTTP ${response.status}).")
                    }
                    if (response.rangeStart != range.start + done) {
                        throw DeltaUnsupportedException("Сервер вернул другой диапазон.")
                    }
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (done < range.length) {
                        coroutineContext.ensureActive()
                        val wanted = minOf(buffer.size.toLong(), range.length - done).toInt()
                        val count = response.body.read(buffer, 0, wanted)
                        if (count < 0) break
                        sink(buffer, count)
                        done += count
                    }
                }
                if (done == range.length) return
                failure = UpdateException("Диапазон получен не полностью.", retryViaVpn = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UpdateException) {
                if (!error.retryViaVpn) throw error
                failure = error
            } catch (error: IOException) {
                failure = UpdateException("Загрузка APK прервана.", error, retryViaVpn = true)
            }
        }
        throw failure ?: UpdateException("Загрузка APK прервана.", retryViaVpn = true)
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val MAX_IN_MEMORY_BYTES = 16L * 1024 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(COPY_BUFFER_BYTES).use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
