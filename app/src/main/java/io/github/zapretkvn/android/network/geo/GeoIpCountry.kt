package io.github.zapretkvn.android.network.geo

import java.io.InputStream
import java.nio.ByteBuffer

/** Страна по сырым байтам IP-адреса (4 или 16). Без сети и без DNS. */
interface CountryLookup {
    /** Идентификатор таблицы: при смене (новый месяц в APK) кэш стран пересчитывается. */
    val databaseId: String

    /** Код страны ISO 3166-1 alpha-2 или null: адрес служебный, частный или таблица его не знает. */
    fun lookup(address: ByteArray): String?
}

/**
 * Разбор IP-литерала без обращения к DNS: `InetAddress.getByName` на имени пошёл бы
 * в сеть, а здесь нужен только текст. Возвращает 4 или 16 байт, для имени — null.
 */
object IpLiteral {
    fun parse(host: String): ByteArray? {
        val text = host.trim().removeSurrounding("[", "]").substringBefore('%')
        if (text.isEmpty()) return null
        return if (':' in text) parseV6(text) else parseV4(text)
    }

    private fun parseV4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            out[index] = value.toByte()
        }
        return out
    }

    private fun parseV6(input: String): ByteArray? {
        var text = input
        // Хвост вида ::ffff:1.2.3.4 превращается в два шестнадцатеричных слова.
        if ('.' in text) {
            val cut = text.lastIndexOf(':')
            val v4 = parseV4(text.substring(cut + 1)) ?: return null
            val high = ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
            val low = ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
            text = text.substring(0, cut + 1) + Integer.toHexString(high) + ":" + Integer.toHexString(low)
        }
        val gap = text.indexOf("::")
        if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) return null
        fun groups(part: String): List<String>? =
            if (part.isEmpty()) emptyList() else part.split(':').takeIf { list -> list.none(String::isEmpty) }
        val words: List<String> = if (gap < 0) {
            groups(text)?.takeIf { it.size == 8 } ?: return null
        } else {
            val head = groups(text.substring(0, gap)) ?: return null
            val tail = groups(text.substring(gap + 2)) ?: return null
            val zeros = 8 - head.size - tail.size
            if (zeros < 1) return null
            head + List(zeros) { "0" } + tail
        }
        val out = ByteArray(16)
        words.forEachIndexed { index, word ->
            if (word.length > 4) return null
            val value = word.toIntOrNull(16) ?: return null
            out[index * 2] = (value shr 8).toByte()
            out[index * 2 + 1] = value.toByte()
        }
        return out
    }
}

/**
 * Таблица DB-IP «IP to Country Lite» в формате `ZGC1` (см. scripts/build-geoip-country.py):
 * две отсортированные последовательности «начало диапазона → страна», IPv4 по 32 бита,
 * IPv6 по старшим 48 битам. Начала хранятся разностями (varint); чтобы искать бинарно,
 * при разборе строится разрежённый индекс «ключ первой записи блока → смещение».
 * Байты остаются сжатыми в памяти (≈2 МБ), распаковывается только один блок на запрос.
 */
class GeoIpTable private constructor(
    private val data: ByteArray,
    private val codes: List<String>,
    private val v4: Stream,
    private val v6: Stream,
    val month: Int,
) : CountryLookup {
    override val databaseId: String get() = "zgc1-$month"

    private class Stream(
        val offset: Int,
        val end: Int,
        val count: Int,
        val blockKeys: LongArray,
        val blockOffsets: IntArray,
    )

    override fun lookup(address: ByteArray): String? = when (address.size) {
        4 -> lookupV4(address)
        16 -> lookupV6(address)
        else -> null
    }

    private fun lookupV4(a: ByteArray): String? {
        val b0 = a[0].toInt() and 0xFF
        val b1 = a[1].toInt() and 0xFF
        val b2 = a[2].toInt() and 0xFF
        val reserved = when {
            b0 == 0 || b0 == 10 || b0 == 127 || b0 >= 224 -> true
            b0 == 100 && b1 in 64..127 -> true
            b0 == 169 && b1 == 254 -> true
            b0 == 172 && b1 in 16..31 -> true
            b0 == 192 && b1 == 168 -> true
            b0 == 192 && b1 == 0 && b2 in 0..2 -> true
            b0 == 198 && b1 in 18..19 -> true
            b0 == 198 && b1 == 51 && b2 == 100 -> true
            b0 == 203 && b1 == 0 && b2 == 113 -> true
            else -> false
        }
        if (reserved) return null
        val key = (b0.toLong() shl 24) or (b1.toLong() shl 16) or (b2.toLong() shl 8) or (a[3].toLong() and 0xFF)
        return find(v4, key)
    }

    private fun lookupV6(a: ByteArray): String? {
        // IPv4-mapped (::ffff:a.b.c.d) — это IPv4 адрес.
        if ((0 until 10).all { a[it].toInt() == 0 } && a[10] == 0xFF.toByte() && a[11] == 0xFF.toByte()) {
            return lookupV4(a.copyOfRange(12, 16))
        }
        // Страны есть только у глобальных адресов 2000::/3; документационный 2001:db8::/32 — служебный.
        if ((a[0].toInt() and 0xE0) != 0x20) return null
        if (a[0].toInt() == 0x20 && a[1].toInt() == 0x01 && a[2].toInt() == 0x0D && (a[3].toInt() and 0xFF) == 0xB8) return null
        var key = 0L
        for (i in 0 until 6) key = (key shl 8) or (a[i].toLong() and 0xFF)
        return find(v6, key)
    }

    private fun find(stream: Stream, key: Long): String? {
        val keys = stream.blockKeys
        // Последний блок, чья первая запись не больше ключа.
        var low = 0
        var high = keys.size - 1
        var block = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (keys[mid] <= key) {
                block = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (block < 0) return null
        var position = stream.blockOffsets[block]
        var current = keys[block]
        var result = 0
        var entry = block * BLOCK
        val last = minOf(stream.count, entry + BLOCK)
        // Первая запись блока: разность пропускаем, абсолютный ключ уже известен.
        position = skipVarint(position)
        result = data[position].toInt() and 0xFF
        position++
        entry++
        while (entry < last) {
            var delta = 0L
            var shift = 0
            while (true) {
                val byte = data[position++].toInt()
                delta = delta or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
            if (current + delta > key) break
            current += delta
            result = data[position].toInt() and 0xFF
            position++
            entry++
        }
        return codes.getOrNull(result - 1)
    }

    private fun skipVarint(from: Int): Int {
        var position = from
        while (data[position].toInt() and 0x80 != 0) position++
        return position + 1
    }

    companion object {
        private const val BLOCK = 64
        internal val MAGIC_BYTES get() = MAGIC
        private val MAGIC = byteArrayOf('Z'.code.toByte(), 'G'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())

        fun parse(data: ByteArray): GeoIpTable {
            require(data.size >= 25 && data.copyOfRange(0, 4).contentEquals(MAGIC)) { "Не таблица ZGC1." }
            val header = ByteBuffer.wrap(data)
            val month = header.getInt(4)
            val countries = data[8].toInt() and 0xFF
            require(countries > 0) { "Пустой список стран." }
            var offset = 9
            val codes = List(countries) { index ->
                String(data, offset + index * 2, 2, Charsets.US_ASCII)
            }
            offset += countries * 2
            val n4 = header.getInt(offset)
            val b4 = header.getInt(offset + 4)
            val n6 = header.getInt(offset + 8)
            val b6 = header.getInt(offset + 12)
            offset += 16
            require(n4 >= 0 && b4 >= 0 && n6 >= 0 && b6 >= 0 && offset.toLong() + b4 + b6 == data.size.toLong()) {
                "Размеры таблицы не сходятся."
            }
            val v4 = index(data, offset, b4, n4, countries)
            val v6 = index(data, offset + b4, b6, n6, countries)
            return GeoIpTable(data, codes, v4, v6, month)
        }

        private fun index(data: ByteArray, offset: Int, length: Int, count: Int, countries: Int): Stream {
            val blocks = (count + BLOCK - 1) / BLOCK
            val keys = LongArray(blocks)
            val offsets = IntArray(blocks)
            var position = offset
            val end = offset + length
            var key = 0L
            for (entry in 0 until count) {
                require(position < end) { "Оборванная таблица." }
                val entryStart = position
                var delta = 0L
                var shift = 0
                while (true) {
                    require(position < end && shift < 64) { "Оборванная таблица." }
                    val byte = data[position++].toInt()
                    delta = delta or ((byte and 0x7F).toLong() shl shift)
                    if (byte and 0x80 == 0) break
                    shift += 7
                }
                require(position < end) { "Оборванная таблица." }
                val country = data[position++].toInt() and 0xFF
                require(country in 1..countries) { "Страна вне списка." }
                key += delta
                if (entry % BLOCK == 0) {
                    keys[entry / BLOCK] = key
                    offsets[entry / BLOCK] = entryStart
                }
            }
            require(position == end) { "Лишние байты в таблице." }
            return Stream(offset, end, count, keys, offsets)
        }

        fun parse(stream: InputStream): GeoIpTable = parse(stream.use { it.readBytes() })
    }
}

/** Таблица, которая читается из ассета один раз при первом запросе. Сбой чтения даёт «страны нет». */
class GeoIpCountry(private val open: () -> InputStream) : CountryLookup {
    private val table: GeoIpTable? by lazy {
        runCatching { GeoIpTable.parse(open()) }.getOrNull()
    }

    // Идентификатор читается по заголовку (8 байт), без разбора всей таблицы.
    private val id: String by lazy {
        runCatching {
            open().use { stream ->
                val head = ByteArray(8)
                var read = 0
                while (read < head.size) {
                    val n = stream.read(head, read, head.size - read)
                    if (n < 0) break
                    read += n
                }
                check(read == head.size && head.copyOfRange(0, 4).contentEquals(GeoIpTable.MAGIC_BYTES))
                "zgc1-" + ByteBuffer.wrap(head).getInt(4)
            }
        }.getOrDefault("unavailable")
    }

    override val databaseId: String get() = id

    override fun lookup(address: ByteArray): String? = table?.lookup(address)

    /** Страна по IP-литералу или null, если это имя, а не адрес. */
    fun lookupLiteral(host: String): String? = IpLiteral.parse(host)?.let(::lookup)
}
