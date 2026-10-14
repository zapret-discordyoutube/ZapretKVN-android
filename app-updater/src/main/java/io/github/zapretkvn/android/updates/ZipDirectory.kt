package io.github.zapretkvn.android.updates

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Любая неожиданность в структуре ZIP: дельта-загрузка в этом случае не используется,
 * приложение скачивает APK целиком. Это не ошибка пользователя и не повод для сообщения.
 */
class DeltaUnsupportedException(message: String) : Exception(message)

/** Запись центрального каталога; имя хранится в ISO-8859-1, чтобы сравнение шло по байтам. */
data class ZipEntryInfo(
    val name: String,
    val flags: Int,
    val method: Int,
    val crc32: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
) {
    /** Бит 0 — шифрование, бит 3 — размеры в data descriptor после данных. */
    val hasPlainLayout: Boolean get() = flags and (FLAG_ENCRYPTED or FLAG_DATA_DESCRIPTOR) == 0

    fun sameContentAs(other: ZipEntryInfo): Boolean =
        name == other.name &&
            method == other.method &&
            crc32 == other.crc32 &&
            compressedSize == other.compressedSize &&
            uncompressedSize == other.uncompressedSize

    companion object {
        const val FLAG_ENCRYPTED = 0x1
        const val FLAG_DATA_DESCRIPTOR = 0x8
    }
}

data class ZipEndOfCentralDirectory(
    val entryCount: Int,
    val centralDirectorySize: Long,
    val centralDirectoryOffset: Long,
)

/** Разобранный каталог ZIP/APK: записи, границы каталога и (если есть) APK Signing Block. */
data class ZipDirectory(
    val fileSize: Long,
    val entries: List<ZipEntryInfo>,
    val centralDirectoryOffset: Long,
    val centralDirectorySize: Long,
    /** Начало APK Signing Block, либо null, если блока нет (подпись только v1). */
    val signingBlockOffset: Long?,
) {
    /** Конец последней записи с данными: всё после него — подпись, каталог, EOCD. */
    val entriesEnd: Long get() = signingBlockOffset ?: centralDirectoryOffset

    /** ABI, чьи нативные библиотеки лежат в APK. Пустое множество — нативных библиотек нет. */
    val nativeAbis: Set<String>
        get() = entries.mapNotNullTo(linkedSetOf()) { entry ->
            if (!entry.name.startsWith("lib/")) return@mapNotNullTo null
            entry.name.removePrefix("lib/").substringBefore('/', missingDelimiterValue = "")
                .takeIf(String::isNotEmpty)
        }
}

object ZipFormat {
    const val EOCD_SIGNATURE = 0x06054b50L
    const val CD_SIGNATURE = 0x02014b50L
    const val LOCAL_SIGNATURE = 0x04034b50L
    const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
    const val EOCD_MIN_SIZE = 22
    const val CD_ENTRY_MIN_SIZE = 46
    const val LOCAL_HEADER_MIN_SIZE = 30
    const val MAX_COMMENT = 0xFFFF
    const val MAX_ENTRIES = 20_000
    const val MAX_CENTRAL_DIRECTORY_BYTES = 8L * 1024 * 1024
    private val SIGNING_MAGIC = "APK Sig Block 42".toByteArray(Charsets.ISO_8859_1)
    const val SIGNING_FOOTER_SIZE = 24

    /** Сколько байт с конца файла достаточно, чтобы найти EOCD вместе с максимальным комментарием. */
    const val TAIL_PROBE_BYTES = 64 * 1024

    /**
     * Ищет EOCD в хвосте файла. [tail] — последние байты файла, [fileSize] — полный размер.
     * ZIP64 и многотомные архивы не поддерживаются: бросается [DeltaUnsupportedException].
     */
    fun parseEndOfCentralDirectory(tail: ByteArray, fileSize: Long): ZipEndOfCentralDirectory {
        if (tail.size < EOCD_MIN_SIZE) throw DeltaUnsupportedException("Хвост ZIP слишком короткий.")
        val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var position = tail.size - EOCD_MIN_SIZE
        val lowest = maxOf(0, tail.size - EOCD_MIN_SIZE - MAX_COMMENT)
        while (position >= lowest) {
            if (buffer.getInt(position).toLong() and 0xFFFFFFFFL == EOCD_SIGNATURE) {
                val commentLength = buffer.getShort(position + 20).toInt() and 0xFFFF
                // Подпись внутри комментария не считается: комментарий обязан кончаться ровно в конце файла.
                if (position + EOCD_MIN_SIZE + commentLength == tail.size) {
                    return eocdAt(buffer, tail, position, fileSize)
                }
            }
            position--
        }
        throw DeltaUnsupportedException("EOCD не найден.")
    }

    private fun eocdAt(
        buffer: ByteBuffer,
        tail: ByteArray,
        position: Int,
        fileSize: Long,
    ): ZipEndOfCentralDirectory {
        val disk = buffer.getShort(position + 4).toInt() and 0xFFFF
        val cdDisk = buffer.getShort(position + 6).toInt() and 0xFFFF
        val entriesOnDisk = buffer.getShort(position + 8).toInt() and 0xFFFF
        val entries = buffer.getShort(position + 10).toInt() and 0xFFFF
        val cdSize = buffer.getInt(position + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = buffer.getInt(position + 16).toLong() and 0xFFFFFFFFL
        if (disk != 0 || cdDisk != 0 || entriesOnDisk != entries) {
            throw DeltaUnsupportedException("Многотомный ZIP.")
        }
        if (entries == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw DeltaUnsupportedException("ZIP64.")
        }
        if (position >= 20 &&
            buffer.getInt(position - 20).toLong() and 0xFFFFFFFFL == ZIP64_LOCATOR_SIGNATURE
        ) {
            throw DeltaUnsupportedException("ZIP64 locator.")
        }
        if (entries <= 0 || entries > MAX_ENTRIES) throw DeltaUnsupportedException("Неожиданное число записей.")
        if (cdSize <= 0 || cdSize > MAX_CENTRAL_DIRECTORY_BYTES) {
            throw DeltaUnsupportedException("Неожиданный размер каталога.")
        }
        val eocdStart = fileSize - (tail.size - position)
        if (cdOffset + cdSize != eocdStart) {
            // Между каталогом и EOCD что-то лежит (ZIP64 EOCD или мусор) — не разбираемся.
            throw DeltaUnsupportedException("Каталог не примыкает к EOCD.")
        }
        return ZipEndOfCentralDirectory(entries, cdSize, cdOffset)
    }

    /** Разбирает байты центрального каталога. Размер и число записей обязаны сойтись точно. */
    fun parseCentralDirectory(bytes: ByteArray, eocd: ZipEndOfCentralDirectory): List<ZipEntryInfo> {
        if (bytes.size.toLong() != eocd.centralDirectorySize) {
            throw DeltaUnsupportedException("Размер каталога не совпал.")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val entries = ArrayList<ZipEntryInfo>(eocd.entryCount)
        var position = 0
        while (position < bytes.size) {
            if (bytes.size - position < CD_ENTRY_MIN_SIZE ||
                buffer.getInt(position).toLong() and 0xFFFFFFFFL != CD_SIGNATURE
            ) {
                throw DeltaUnsupportedException("Повреждена запись каталога.")
            }
            val flags = buffer.getShort(position + 8).toInt() and 0xFFFF
            val method = buffer.getShort(position + 10).toInt() and 0xFFFF
            val crc = buffer.getInt(position + 16).toLong() and 0xFFFFFFFFL
            val compressed = buffer.getInt(position + 20).toLong() and 0xFFFFFFFFL
            val uncompressed = buffer.getInt(position + 24).toLong() and 0xFFFFFFFFL
            val nameLength = buffer.getShort(position + 28).toInt() and 0xFFFF
            val extraLength = buffer.getShort(position + 30).toInt() and 0xFFFF
            val commentLength = buffer.getShort(position + 32).toInt() and 0xFFFF
            val offset = buffer.getInt(position + 42).toLong() and 0xFFFFFFFFL
            if (compressed == 0xFFFFFFFFL || uncompressed == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) {
                throw DeltaUnsupportedException("ZIP64-запись.")
            }
            val total = CD_ENTRY_MIN_SIZE + nameLength + extraLength + commentLength
            if (position + total > bytes.size) throw DeltaUnsupportedException("Запись выходит за каталог.")
            val name = String(bytes, position + CD_ENTRY_MIN_SIZE, nameLength, Charsets.ISO_8859_1)
            entries += ZipEntryInfo(name, flags, method, crc, compressed, uncompressed, offset)
            position += total
            if (entries.size > eocd.entryCount) throw DeltaUnsupportedException("Записей больше заявленного.")
        }
        if (entries.size != eocd.entryCount) throw DeltaUnsupportedException("Записей меньше заявленного.")
        if (entries.map(ZipEntryInfo::localHeaderOffset).toSet().size != entries.size) {
            throw DeltaUnsupportedException("Повторяющиеся смещения записей.")
        }
        if (entries.map(ZipEntryInfo::name).toSet().size != entries.size) {
            throw DeltaUnsupportedException("Повторяющиеся имена записей.")
        }
        return entries
    }

    /**
     * Если прямо перед каталогом стоит подпись APK Signing Block, возвращает начало блока.
     * [footer] — [SIGNING_FOOTER_SIZE] байт, лежащих непосредственно перед каталогом.
     */
    fun signingBlockOffset(footer: ByteArray, centralDirectoryOffset: Long): Long? {
        if (footer.size != SIGNING_FOOTER_SIZE) return null
        val magicStart = SIGNING_FOOTER_SIZE - SIGNING_MAGIC.size
        for (index in SIGNING_MAGIC.indices) {
            if (footer[magicStart + index] != SIGNING_MAGIC[index]) return null
        }
        val sizeField = ByteBuffer.wrap(footer, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        // Размер в блоке не включает собственное 8-байтовое поле; минимум — footer (24 байта).
        if (sizeField < SIGNING_FOOTER_SIZE || sizeField > centralDirectoryOffset - 8) {
            throw DeltaUnsupportedException("Некорректный размер APK Signing Block.")
        }
        return centralDirectoryOffset - (sizeField + 8)
    }

    /** Длина локального заголовка по его первым 30 байтам; null, если это не локальный заголовок. */
    fun localHeaderLength(header: ByteArray, offset: Int = 0): Long? {
        if (header.size - offset < LOCAL_HEADER_MIN_SIZE) return null
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(offset).toLong() and 0xFFFFFFFFL != LOCAL_SIGNATURE) return null
        val nameLength = buffer.getShort(offset + 26).toInt() and 0xFFFF
        val extraLength = buffer.getShort(offset + 28).toInt() and 0xFFFF
        return (LOCAL_HEADER_MIN_SIZE + nameLength + extraLength).toLong()
    }

    /** Имя из локального заголовка (ISO-8859-1) либо null. */
    fun localHeaderName(header: ByteArray): String? {
        val length = localHeaderLength(header) ?: return null
        val nameLength = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(26).toInt() and 0xFFFF
        if (header.size < LOCAL_HEADER_MIN_SIZE + nameLength || length < LOCAL_HEADER_MIN_SIZE) return null
        return String(header, LOCAL_HEADER_MIN_SIZE, nameLength, Charsets.ISO_8859_1)
    }
}

/** Установленный APK, прочитанный с диска: каталог и доступ к данным записей. */
class LocalApk private constructor(
    private val file: File,
    val directory: ZipDirectory,
) {
    private val byName = directory.entries.associateBy(ZipEntryInfo::name)

    fun entry(name: String): ZipEntryInfo? = byName[name]

    /**
     * Смещение сжатых данных записи в этом файле. Читает и проверяет локальный заголовок:
     * сигнатура, имя и то, что данные целиком лежат до подписи.
     */
    fun dataOffset(entry: ZipEntryInfo): Long? = RandomAccessFile(file, "r").use { raf ->
        val fixed = ByteArray(ZipFormat.LOCAL_HEADER_MIN_SIZE)
        raf.seek(entry.localHeaderOffset)
        raf.readFully(fixed)
        val headerLength = ZipFormat.localHeaderLength(fixed) ?: return null
        val nameLength = (fixed[26].toInt() and 0xFF) or ((fixed[27].toInt() and 0xFF) shl 8)
        val name = ByteArray(nameLength)
        raf.readFully(name)
        if (String(name, Charsets.ISO_8859_1) != entry.name) return null
        val start = entry.localHeaderOffset + headerLength
        if (start + entry.compressedSize > directory.entriesEnd) return null
        start
    }

    companion object {
        /** Читает каталог APK с диска. Любая странность — [DeltaUnsupportedException]. */
        fun open(file: File): LocalApk {
            val size = file.length()
            if (size < ZipFormat.EOCD_MIN_SIZE) throw DeltaUnsupportedException("Файл слишком мал.")
            RandomAccessFile(file, "r").use { raf ->
                val tailLength = minOf(size, ZipFormat.TAIL_PROBE_BYTES.toLong()).toInt()
                val tail = ByteArray(tailLength)
                raf.seek(size - tailLength)
                raf.readFully(tail)
                val eocd = ZipFormat.parseEndOfCentralDirectory(tail, size)
                val cd = ByteArray(eocd.centralDirectorySize.toInt())
                raf.seek(eocd.centralDirectoryOffset)
                raf.readFully(cd)
                val entries = ZipFormat.parseCentralDirectory(cd, eocd)
                var signing: Long? = null
                if (eocd.centralDirectoryOffset >= ZipFormat.SIGNING_FOOTER_SIZE) {
                    val footer = ByteArray(ZipFormat.SIGNING_FOOTER_SIZE)
                    raf.seek(eocd.centralDirectoryOffset - ZipFormat.SIGNING_FOOTER_SIZE)
                    raf.readFully(footer)
                    signing = ZipFormat.signingBlockOffset(footer, eocd.centralDirectoryOffset)
                }
                return LocalApk(
                    file,
                    ZipDirectory(size, entries, eocd.centralDirectoryOffset, eocd.centralDirectorySize, signing),
                )
            }
        }
    }
}
