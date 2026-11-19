package io.github.zapretkvn.clientreports

import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Куда слать отчёты (решение владельца 19.11.2026): reports.zapret.moe в России обычно закрыт — зону
 * zapret.moe режут по имени (SNI). Приёмник поэтому есть и под случайными именами доменов пула
 * zapret-links (служба `reports`: мост на тот же приёмник, сеть отправителя он видит по-прежнему).
 *
 * Имена берутся из общей ленты zapret-links: `services.reports.hosts` её входных страниц
 * (script.google.com и gist GitHub — их не режут, обе отдают targets.json как есть). В приложении —
 * только неизменная затравка: имена пула не удаляются никогда (zapret-links лишь перестаёт их
 * раздавать), поэтому имена на день выпуска остаются рабочим запасом.
 *
 * Порядок попыток разносит отправку по разным именам и доменам: каждый пакет начинает со случайного
 * имени, подряд идут имена разных доменов, только что не ответившие — в конце, закрытое по имени
 * reports.zapret.moe — последним (через туннель оно работает).
 */
object IngestHosts {
    const val FIXED = "reports.zapret.moe"

    /** Имена службы `reports` на 19.11.2026 (zapret-domain service add reports) и прежнее имя. */
    val SEED = listOf("oeifnxndqq.fuckingspeedtest.com", "wkjjjdcycb.osjcpjzipyb.click", FIXED)

    /** Входные страницы zapret-links: Apps Script (`?format=json`) и gist, обе отдают targets.json. */
    val FEEDS = listOf(
        "https://script.google.com/macros/s/AKfycbzcEPk67WW6fYC0vPVm397QyDFHd4m-SrfKDBnfvG8jAD-n_HXVv8AqQnEHZ6A42oIh/exec?format=json",
        "https://gist.githubusercontent.com/fuckyourkn/ecb9b6a03f53bddcdd47023ebc12d4f9/raw/targets.json",
    )

    const val MAX_FEED_BYTES = 64 * 1024
    const val MAX_HOSTS = 8

    /** Имён на один путь (напрямую или через туннель) за одну отправку. */
    const val TRIES_PER_PATH = 4

    /**
     * Тело одного запроса. ТСПУ «замораживает» TCP-соединение с частью зарубежных сетей после ~25
     * пакетов в обе стороны (≈16 КБ), счётчик на соединение. Рукопожатие TLS с сертификатом занимает
     * ~6–8 пакетов, 8 КБ тела — ~6, ответ — 1–2: один запрос остаётся около 15 пакетов, а каждый
     * пакет отчётов идёт своим новым соединением.
     */
    const val MAX_BODY_BYTES = 8 * 1024

    /** Сколько помнить имя, которое не ответило: в эту отправку оно идёт последним. */
    const val FAILED_FOR_MILLIS = 30L * 60 * 1000

    private val HOST = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")

    fun domainOf(host: String): String = host.substringAfter('.')

    fun isHostName(host: String): Boolean = host.length <= 253 && HOST.matches(host)

    /**
     * `services.reports.hosts` входной страницы или links.json. Имя принимается только под доменом из
     * `domains` того же документа (а без него — под доменом затравки), чтобы лента не увела отчёты на
     * чужой хост; не больше [MAX_HOSTS]. Битый документ — пустой список.
     */
    fun parseFeed(text: String): List<String> {
        val document = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val domains = (document["domains"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.lowercase() }
            ?.takeIf { it.isNotEmpty() }
            ?: SEED.map(::domainOf)
        val hosts = ((document["services"] as? JsonObject)?.get("reports") as? JsonObject)?.get("hosts") as? JsonArray
            ?: return emptyList()
        return hosts.asSequence()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()?.lowercase() }
            .filter { isHostName(it) && domains.any { domain -> it.endsWith(".$domain") } }
            .distinct()
            .take(MAX_HOSTS)
            .toList()
    }

    /**
     * Порядок имён для одной отправки: известные из ленты и затравка, перемешанные, с чередованием
     * доменов; [failed] (недавно не ответившие) — в конце, [FIXED] — последним из рабочих.
     */
    fun order(learned: List<String>, failed: Set<String>, random: Random): List<String> {
        val all = (learned + SEED).distinct()
        val fresh = all.filter { it !in failed }
        val pool = fresh.filter { it != FIXED }.shuffled(random)
        val byDomain = pool.groupBy(::domainOf).values.map { it.toMutableList() }.toMutableList()
        val interleaved = mutableListOf<String>()
        while (byDomain.any { it.isNotEmpty() }) {
            for (group in byDomain) if (group.isNotEmpty()) interleaved += group.removeAt(0)
        }
        return interleaved + fresh.filter { it == FIXED } + all.filter { it in failed }
    }

    /**
     * Первый пакет очереди: подряд идущие записи, пока тело не больше [maxBytes] и записей не больше
     * [maxCount]; одна запись идёт всегда (она меньше килобайта).
     */
    fun <T> takeBatch(records: List<T>, maxCount: Int, maxBytes: Int, bodyBytes: (List<T>) -> Int): List<T> {
        var size = 0
        for (count in 1..minOf(maxCount, records.size)) {
            if (count > 1 && bodyBytes(records.subList(0, count)) > maxBytes) break
            size = count
        }
        return records.subList(0, size)
    }
}

/**
 * Имена приёмника, узнанные из ленты, и когда их узнали. Лента спрашивается по событию (отправка),
 * не по таймеру: если имена старше суток или ни одно не ответило, но не чаще раза в час.
 */
class IngestHostCache(private val file: File) {
    @Volatile
    private var loaded = false
    private var hosts: List<String> = emptyList()
    private var fetchedAtMillis = 0L
    private var lastAttemptMillis = Long.MIN_VALUE

    @Synchronized
    fun hosts(): List<String> {
        load()
        return hosts
    }

    /** Пора ли спросить ленту: имена устарели или ни одно не ответило, и прошлый раз был давно. */
    @Synchronized
    fun feedDue(nowMillis: Long, nothingAnswered: Boolean): Boolean {
        load()
        if (lastAttemptMillis != Long.MIN_VALUE && nowMillis - lastAttemptMillis in 0 until RETRY_AFTER_MILLIS) return false
        return nothingAnswered || hosts.isEmpty() || nowMillis - fetchedAtMillis !in 0 until MAX_AGE_MILLIS
    }

    @Synchronized
    fun noteFeedAttempt(nowMillis: Long) {
        lastAttemptMillis = nowMillis
    }

    @Synchronized
    fun store(learned: List<String>, nowMillis: Long) {
        if (learned.isEmpty()) return
        load()
        hosts = learned.take(IngestHosts.MAX_HOSTS)
        fetchedAtMillis = nowMillis
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText((listOf(nowMillis.toString()) + hosts).joinToString("\n", postfix = "\n"))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val lines = file.readLines(Charsets.UTF_8).map(String::trim).filter(String::isNotEmpty)
            fetchedAtMillis = lines.first().toLong()
            // Домены имён проверены при записи (по `domains` ленты); здесь только вид имени.
            hosts = lines.drop(1).map(String::lowercase).filter(IngestHosts::isHostName).distinct().take(IngestHosts.MAX_HOSTS)
        }
    }

    companion object {
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        const val RETRY_AFTER_MILLIS = 60L * 60 * 1000
    }
}
