package io.github.zapretkvn.android.updates

/** Полуинтервал байт файла [start, endExclusive). */
data class ByteRange(val start: Long, val endExclusive: Long) {
    init {
        require(start in 0..endExclusive) { "Некорректный диапазон $start..$endExclusive" }
    }

    val length: Long get() = endExclusive - start
}

/** Сжатые данные записи берутся из установленного APK: [length] байт с [oldOffset] в [newOffset] нового файла. */
data class ReuseCopy(val name: String, val oldOffset: Long, val newOffset: Long, val length: Long)

/**
 * После загрузки по [offset] обязан лежать локальный заголовок записи [name] длиной ровно [headerLength]:
 * иначе смещение данных вычислено неверно и дельта отбрасывается.
 */
data class HeaderCheck(val name: String, val offset: Long, val headerLength: Long)

/**
 * План сборки нового APK: что скопировать из установленного, а что скачать диапазонами.
 * Байты хвоста (подпись, центральный каталог, EOCD) уже скачаны при чтении каталога и лежат в
 * [prefetched]; [prefetchedNetworkBytes] — сколько сети это стоило вместе с пробой хвоста.
 */
class DeltaPlan(
    val fileSize: Long,
    val reuse: List<ReuseCopy>,
    val downloads: List<ByteRange>,
    val headerChecks: List<HeaderCheck>,
    val prefetchedOffset: Long,
    val prefetched: ByteArray,
    val prefetchedNetworkBytes: Long,
    /** Начало APK Signing Block: его первые 8 байт проверяются после загрузки. */
    val signingBlockOffset: Long?,
) {
    val reusedBytes: Long = reuse.sumOf(ReuseCopy::length)
    val rangeBytes: Long = downloads.sumOf(ByteRange::length)

    /** Сколько байт будет получено по сети всего (проба хвоста, каталог и диапазоны). */
    val networkBytes: Long = prefetchedNetworkBytes + rangeBytes

    /** Во сколько байт обошлась бы полная загрузка, минус реальная сеть. */
    val savedBytes: Long get() = (fileSize - networkBytes).coerceAtLeast(0)
}

object DeltaPlanner {
    /** Записи короче порога проще скачать, чем собирать: они склеиваются в общие диапазоны. */
    const val MIN_REUSE_BYTES = 64L * 1024
    const val MERGE_GAP_BYTES = 64L * 1024
    const val MAX_CHUNK_BYTES = 4L * 1024 * 1024
    private const val MAX_LOCAL_EXTRA = 0xFFFF

    /**
     * @param prefetchedOffset начало уже скачанного хвоста (последние 24 байта до каталога, каталог, EOCD)
     * @return план; если повторно использовать нечего, `reuse` пуст, а `downloads` покрывает весь файл до хвоста
     * @throws DeltaUnsupportedException если структура нового APK вызывает сомнения
     */
    fun plan(
        directory: ZipDirectory,
        old: LocalApk,
        prefetchedOffset: Long,
        prefetched: ByteArray,
        prefetchedNetworkBytes: Long,
        minReuseBytes: Long = MIN_REUSE_BYTES,
        mergeGapBytes: Long = MERGE_GAP_BYTES,
    ): DeltaPlan {
        if (prefetchedOffset < 0 || prefetchedOffset + prefetched.size != directory.fileSize) {
            throw DeltaUnsupportedException("Хвост не доходит до конца файла.")
        }
        val sorted = directory.entries.sortedBy(ZipEntryInfo::localHeaderOffset)
        val entriesEnd = directory.entriesEnd
        if (sorted.first().localHeaderOffset < 0 || sorted.last().localHeaderOffset >= entriesEnd) {
            throw DeltaUnsupportedException("Записи выходят за границы файла.")
        }
        val abiMatch = directory.nativeAbis == old.directory.nativeAbis
        val reuse = ArrayList<ReuseCopy>()
        val checks = ArrayList<HeaderCheck>()
        if (abiMatch) {
            for (index in sorted.indices) {
                val entry = sorted[index]
                val regionEnd = if (index + 1 < sorted.size) sorted[index + 1].localHeaderOffset else entriesEnd
                val region = regionEnd - entry.localHeaderOffset
                if (region <= 0) throw DeltaUnsupportedException("Пустая область записи.")
                val candidate = reusable(entry, region, old, minReuseBytes) ?: continue
                val header = region - entry.compressedSize
                val newOffset = entry.localHeaderOffset + header
                // Данные не должны залезать в уже скачанный хвост.
                if (newOffset + entry.compressedSize > prefetchedOffset) continue
                reuse += ReuseCopy(entry.name, candidate, newOffset, entry.compressedSize)
                checks += HeaderCheck(entry.name, entry.localHeaderOffset, header)
            }
        }
        reuse.sortBy(ReuseCopy::newOffset)
        for (index in 1 until reuse.size) {
            if (reuse[index].newOffset < reuse[index - 1].newOffset + reuse[index - 1].length) {
                throw DeltaUnsupportedException("Пересекающиеся области повторного использования.")
            }
        }
        val needed = ArrayList<ByteRange>()
        var cursor = 0L
        for (copy in reuse) {
            if (copy.newOffset > cursor) needed += ByteRange(cursor, copy.newOffset)
            cursor = copy.newOffset + copy.length
        }
        if (cursor < prefetchedOffset) needed += ByteRange(cursor, prefetchedOffset)
        val downloads = mergeRanges(needed, mergeGapBytes)
        // Область, поглощённая общим диапазоном, всё равно скачивается: копировать её незачем.
        val kept = reuse.filter { copy ->
            downloads.none { it.start <= copy.newOffset && copy.newOffset + copy.length <= it.endExclusive }
        }
        val keptNames = kept.mapTo(hashSetOf(), ReuseCopy::name)
        return DeltaPlan(
            fileSize = directory.fileSize,
            reuse = kept,
            downloads = downloads,
            headerChecks = checks.filter { it.name in keptNames },
            prefetchedOffset = prefetchedOffset,
            prefetched = prefetched,
            prefetchedNetworkBytes = prefetchedNetworkBytes,
            signingBlockOffset = directory.signingBlockOffset,
        )
    }

    /** Смещение данных в старом APK, если запись можно взять оттуда; иначе null. */
    private fun reusable(entry: ZipEntryInfo, region: Long, old: LocalApk, minReuseBytes: Long): Long? {
        if (entry.compressedSize < minReuseBytes || !entry.hasPlainLayout) return null
        val oldEntry = old.entry(entry.name) ?: return null
        if (!oldEntry.hasPlainLayout || !oldEntry.sameContentAs(entry)) return null
        val header = region - entry.compressedSize
        val minHeader = ZipFormat.LOCAL_HEADER_MIN_SIZE + entry.name.length.toLong()
        if (header < minHeader || header > minHeader + MAX_LOCAL_EXTRA) return null
        return old.dataOffset(oldEntry)
    }

    /** Склеивает диапазоны, между которыми меньше [maxGap] байт. Вход может быть неотсортирован. */
    fun mergeRanges(ranges: List<ByteRange>, maxGap: Long): List<ByteRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.filter { it.length > 0 }.sortedBy(ByteRange::start)
        if (sorted.isEmpty()) return emptyList()
        val merged = ArrayList<ByteRange>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.start - current.endExclusive < maxGap) {
                current = ByteRange(current.start, maxOf(current.endExclusive, next.endExclusive))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    /** Режет длинные диапазоны на куски не длиннее [maxChunk] — для параллельной загрузки и повторов. */
    fun splitRanges(ranges: List<ByteRange>, maxChunk: Long = MAX_CHUNK_BYTES): List<ByteRange> {
        require(maxChunk > 0)
        val chunks = ArrayList<ByteRange>()
        for (range in ranges) {
            var start = range.start
            while (start < range.endExclusive) {
                val end = minOf(range.endExclusive, start + maxChunk)
                chunks += ByteRange(start, end)
                start = end
            }
        }
        return chunks
    }
}
