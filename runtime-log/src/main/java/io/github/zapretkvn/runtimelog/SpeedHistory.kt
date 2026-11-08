package io.github.zapretkvn.runtimelog

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * История скоростей: чем дольше пользуешься приложением, тем лучше оно знает, какие серверы и какие кеш-узлы
 * YouTube быстрые, а какие нет (владелец 2026-11-08: «историю не удаляем, а храним компактно и показываем в логах»).
 *
 * Хранятся не потоки, а итоги по паре (сервер, цель): сколько потоков, сколько байт и времени, бегущая средняя
 * скорость (EWMA), сколько потоков медленные и быстрые, сколько раз сбрасывали. Цель для видео YouTube это
 * узел кеша (`yt:sn-1giz7n7l`: часть `sn-…` имени `rr2---sn-1giz7n7l.googlevideo.com`), для остальных доменов
 * корневая пара меток, для адресов без имени сеть /24.
 *
 * Ничего не удаляется: когда записей больше [maxEntries], самые малоинформативные (меньше всего потоков, давно не
 * встречались) сливаются в запись `*другие` того же сервера, итоги сохраняются. Файл маленький (одна строка на
 * запись) и пишется атомарно не чаще раза в минуту.
 */
class SpeedHistory(
    private val file: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 4_000,
) {
    class Entry(val server: String, val target: String) {
        var flows = 0L
        var graded = 0L
        var bytes = 0L
        var millis = 0L
        var ewma = 0L
        var slow = 0L
        var fast = 0L
        var resets = 0L
        var lastSeenDay = 0L

        val averageBytesPerSecond: Long get() = if (millis > 0) bytes * 1_000 / millis else 0L
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var dirty = false
    private var lastWriteAt = 0L

    init {
        load()
    }

    @Synchronized
    fun recordFlow(server: String, domain: String, destination: String, downlinkBytes: Long, livedMillis: Long) {
        val entry = entryFor(server, targetOf(domain, destination))
        entry.flows++
        entry.lastSeenDay = day()
        val speedClass = SpeedClass.ofFlow(downlinkBytes, livedMillis)
        if (speedClass != null) {
            val speed = downlinkBytes * 1_000 / livedMillis
            entry.graded++
            entry.bytes += downlinkBytes
            entry.millis += livedMillis
            entry.ewma = if (entry.graded == 1L) speed else (entry.ewma * 7 + speed * 3) / 10
            if (speedClass == SpeedClass.Slow || speedClass == SpeedClass.VerySlow) entry.slow++
            if (speedClass == SpeedClass.Fast) entry.fast++
        }
        dirty = true
    }

    @Synchronized
    fun recordReset(server: String, domain: String, destination: String = "") {
        val entry = entryFor(server, targetOf(domain, destination))
        entry.resets++
        entry.lastSeenDay = day()
        dirty = true
    }

    /**
     * Цель уже была медленной у этого сервера: не меньше [MIN_GRADED_TO_JUDGE] оценённых потоков, бегущая средняя
     * ниже границы «медленно» и не меньше 70% потоков медленные. Тогда зависание режется раньше.
     */
    @Synchronized
    fun isKnownSlow(server: String, domain: String): Boolean {
        val entry = entries[key(server, targetOf(domain, ""))] ?: return false
        return entry.graded >= MIN_GRADED_TO_JUDGE &&
            entry.ewma < SpeedClass.Slow.minBytesPerSecond &&
            entry.slow * 10 >= entry.graded * 7
    }

    /** Строки для журнала: по серверу итог по YouTube и самые медленные и быстрые узлы кеша. */
    @Synchronized
    fun summary(): List<String> {
        if (entries.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        lines += "история скоростей: записей ${entries.size}, серверов ${entries.values.map { it.server }.toSet().size}"
        val byServer = entries.values.groupBy { it.server }.entries.sortedByDescending { (_, list) -> list.sumOf { it.bytes } }
        for ((server, list) in byServer.take(MAX_SERVERS_IN_SUMMARY)) {
            val video = list.filter { it.target.startsWith("yt:") }
            val videoGraded = video.sumOf { it.graded }
            val videoText = if (videoGraded > 0) {
                val bytes = video.sumOf { it.bytes }
                val millis = video.sumOf { it.millis }
                val speed = if (millis > 0) bytes * 1_000 / millis else 0L
                "YouTube ср. ${FlowTracker.formatBytes(speed)}/с (${SpeedClass.of(speed).label}), оценённых потоков $videoGraded, " +
                    "медленных ${video.sumOf { it.slow }}, быстрых ${video.sumOf { it.fast }}, сбросов ${video.sumOf { it.resets }}"
            } else {
                "YouTube: данных нет"
            }
            val graded = video.filter { it.graded >= 2 }.sortedBy { it.ewma }
            val slowest = graded.take(3).filter { it.ewma < SpeedClass.Normal.minBytesPerSecond }
                .joinToString(", ") { "${it.target.removePrefix("yt:")} ${FlowTracker.formatBytes(it.ewma)}/с x${it.graded}" }
            val fastest = graded.asReversed().take(3).filter { it.ewma >= SpeedClass.Normal.minBytesPerSecond }
                .joinToString(", ") { "${it.target.removePrefix("yt:")} ${FlowTracker.formatBytes(it.ewma)}/с x${it.graded}" }
            lines += "история $server: $videoText" +
                (if (slowest.isNotEmpty()) "; медленные узлы: $slowest" else "") +
                (if (fastest.isNotEmpty()) "; быстрые узлы: $fastest" else "")
        }
        return lines
    }

    /** Пишет файл, если есть изменения и прошло не меньше [minIntervalMillis] (или [force]). */
    @Synchronized
    fun flushIfDirty(minIntervalMillis: Long = WRITE_INTERVAL_MILLIS, force: Boolean = false) {
        val target = file ?: return
        val now = clock()
        if (!dirty || (!force && now - lastWriteAt < minIntervalMillis)) return
        val text = render()
        try {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(text, Charsets.UTF_8)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            dirty = false
            lastWriteAt = now
        } catch (_: java.io.IOException) {
            // Диск недоступен: история остаётся в памяти, следующая попытка при следующей записи.
        }
    }

    /** Весь файл истории одной строкой (для записи на диск и для архива диагностики). */
    @Synchronized
    fun render(): String {
        val text = StringBuilder("$HEADER\n")
        for (entry in entries.values) {
            text.append(escape(entry.server)).append('\t').append(escape(entry.target)).append('\t')
                .append(entry.flows).append('\t').append(entry.graded).append('\t').append(entry.bytes).append('\t')
                .append(entry.millis).append('\t').append(entry.ewma).append('\t').append(entry.slow).append('\t')
                .append(entry.fast).append('\t').append(entry.resets).append('\t').append(entry.lastSeenDay).append('\n')
        }
        return text.toString()
    }

    @Synchronized
    fun entryCount(): Int = entries.size

    @Synchronized
    fun entry(server: String, domain: String, destination: String = ""): Entry? = entries[key(server, targetOf(domain, destination))]

    /** Запись `*другие` сервера: сюда сливаются малоинформативные записи при уплотнении. */
    @Synchronized
    fun otherFor(server: String): Entry? = entries[key(server, OTHER)]

    private fun entryFor(server: String, target: String): Entry {
        val existing = entries[key(server, target)]
        if (existing != null) return existing
        if (entries.size >= maxEntries) compact()
        return entries.getOrPut(key(server, target)) { Entry(server, target) }
    }

    /** Сливает самые малоинформативные записи в `*другие` того же сервера: итоги не теряются. */
    private fun compact() {
        val victims = entries.values.filter { it.target != OTHER }
            .sortedWith(compareBy<Entry> { it.flows }.thenBy { it.lastSeenDay })
            .take((maxEntries / 10).coerceAtLeast(1))
        for (victim in victims) {
            entries.remove(key(victim.server, victim.target))
            val other = entries.getOrPut(key(victim.server, OTHER)) { Entry(victim.server, OTHER) }
            other.ewma = if (other.graded + victim.graded == 0L) 0L else
                (other.ewma * other.graded + victim.ewma * victim.graded) / (other.graded + victim.graded)
            other.flows += victim.flows
            other.graded += victim.graded
            other.bytes += victim.bytes
            other.millis += victim.millis
            other.slow += victim.slow
            other.fast += victim.fast
            other.resets += victim.resets
            other.lastSeenDay = maxOf(other.lastSeenDay, victim.lastSeenDay)
        }
    }

    private fun load() {
        val source = file?.takeIf { it.isFile } ?: return
        val lines = try {
            source.readLines(Charsets.UTF_8)
        } catch (_: java.io.IOException) {
            return
        }
        if (lines.firstOrNull() != HEADER) return
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            if (parts.size != 11) continue
            val numbers = parts.drop(2).map { it.toLongOrNull() ?: return@map null }
            if (numbers.any { it == null }) continue
            val entry = Entry(unescape(parts[0]), unescape(parts[1]))
            entry.flows = numbers[0]!!
            entry.graded = numbers[1]!!
            entry.bytes = numbers[2]!!
            entry.millis = numbers[3]!!
            entry.ewma = numbers[4]!!
            entry.slow = numbers[5]!!
            entry.fast = numbers[6]!!
            entry.resets = numbers[7]!!
            entry.lastSeenDay = numbers[8]!!
            entries[key(entry.server, entry.target)] = entry
        }
    }

    private fun day(): Long = clock() / DAY_MILLIS

    private fun key(server: String, target: String) = "$server\u0000$target"

    companion object {
        const val HEADER = "#zkvn-speed 1"
        const val OTHER = "*другие"
        const val MIN_GRADED_TO_JUDGE = 3L
        private const val MAX_SERVERS_IN_SUMMARY = 6
        private const val WRITE_INTERVAL_MILLIS = 60_000L
        private const val DAY_MILLIS = 86_400_000L
        private val CACHE_NODE = Regex("""sn-[a-z0-9]+(?:-[a-z0-9]+)?""")

        /** Цель потока: узел кеша YouTube, корневая пара меток домена или сеть /24 адреса без имени. */
        fun targetOf(domain: String, destination: String): String {
            val name = domain.trim().trimEnd('.').lowercase(Locale.ROOT)
            if (name.isNotEmpty()) {
                if (name == "googlevideo.com" || name.endsWith(".googlevideo.com")) {
                    return "yt:" + (CACHE_NODE.find(name)?.value ?: "googlevideo")
                }
                val labels = name.split('.')
                return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else name
            }
            val address = destination.trim().removeSurrounding("[", "]").let { value ->
                if (value.startsWith("[")) value.substringAfter('[').substringBefore(']') else value.substringBeforeLast(':', value)
            }
            val v4 = address.split('.')
            if (v4.size == 4 && v4.all { it.toIntOrNull() != null }) return "ip:${v4[0]}.${v4[1]}.${v4[2]}.0/24"
            if (address.contains(':')) return "ip6:" + address.split(':').take(3).joinToString(":")
            return "?"
        }

        private fun escape(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unescape(value: String): String {
            if ('\\' !in value) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                if (value[i] == '\\' && i + 1 < value.length) {
                    out.append(when (value[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> value[i + 1] })
                    i += 2
                } else {
                    out.append(value[i])
                    i++
                }
            }
            return out.toString()
        }
    }
}
