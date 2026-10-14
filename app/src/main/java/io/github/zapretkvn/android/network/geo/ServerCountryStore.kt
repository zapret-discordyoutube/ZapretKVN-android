package io.github.zapretkvn.android.network.geo

import io.github.zapretkvn.android.config.JsonConfig
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Разрешение имени сервера в адреса. Только безопасный путь: реализация не должна
 * отправлять имя открытым текстом (см. DohHostAddressResolver).
 *
 * Результат: `null` — сети нет, пробовать нечем (попытка не засчитывается);
 * пустой список — имя не разрешилось; иначе адреса.
 */
fun interface HostAddressResolver {
    suspend fun resolve(host: String): List<InetAddress>?
}

/**
 * Страна каждого сервера профиля по его адресу: `host → код страны`.
 *
 * Хост — ключ из `endpointHostKey`. IP-литерал определяется по офлайн-таблице сразу, без сети.
 * Имя разрешается один раз и запоминается: следующая попытка для хоста — не раньше чем через
 * [REFRESH_MILLIS] (7 суток), после неудачи — не раньше чем через [RETRY_MILLIS] (сутки).
 * Запросы идут пачкой по инициативе приложения (импорт, обновление подписки, запуск), без
 * таймеров: не больше [PARALLELISM] имён одновременно, в случайном порядке, с паузой
 * между запросами, и не больше [MAX_PER_RUN] имён за пачку.
 * Кэш лежит в noBackupFilesDir; адрес в интерфейсе не показывается и наружу не выдаётся.
 */
class ServerCountryStore(
    private val file: File,
    private val geo: CountryLookup,
    private val resolver: HostAddressResolver,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val pause: suspend () -> Unit = { delay(random.nextLong(PAUSE_MIN_MILLIS, PAUSE_MAX_MILLIS)) },
) {
    private data class Entry(val country: String?, val address: String?, val at: Long)

    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private var loaded = false
    private val mutableCountries = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Хост → код страны; только те, для кого страна известна. */
    val countries: StateFlow<Map<String, String>> = mutableCountries.asStateFlow()

    private class Request(val hosts: Set<String>, val priority: Set<String>)

    private val requests = Channel<Request>(Channel.CONFLATED)
    private var worker: Job? = null

    init {
        // Показать сохранённое сразу после старта, без ожидания первого запроса.
        scope.launch { lock.withLock { ensureLoaded(); publish() } }
    }

    /**
     * Попросить определить страны для [hosts] (ключи хостов; [priority] — хосты активного профиля,
     * они идут первыми). Не блокирует; повторные вызовы с тем же набором ничего не стоят.
     */
    fun request(hosts: Collection<String>, priority: Collection<String> = emptyList()) {
        if (hosts.isEmpty()) return
        synchronized(this) {
            if (worker == null) {
                worker = scope.launch {
                    for (request in requests) {
                        try {
                            refresh(request.hosts, request.priority)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Сбой одной пачки не должен останавливать очередь.
                        }
                    }
                }
            }
        }
        requests.trySend(Request(hosts.toSet(), priority.toSet()))
    }

    /** Одна пачка: IP-литералы — сразу, имена — по очереди. Публичная для тестов. */
    suspend fun refresh(hosts: Set<String>, priority: Set<String> = emptySet()) {
        if (hosts.isEmpty()) return
        val due = lock.withLock {
            ensureLoaded()
            hosts.filter { isDue(entries[it]) }
        }
        if (due.isEmpty()) {
            lock.withLock { prune(hosts) }
            return
        }
        // Таблицы нет (повреждённый ассет) — ничего не записываем, чтобы не закрепить «страны нет» на неделю.
        if (geo.databaseId == UNAVAILABLE) return

        val (literals, names) = due.partition { IpLiteral.parse(it) != null }
        lock.withLock {
            for (host in literals) {
                val bytes = IpLiteral.parse(host) ?: continue
                entries[host] = Entry(geo.lookup(bytes), host, now())
            }
            publish()
            save()
        }
        val queue = (names.filter { it in priority }.shuffled(random) + names.filter { it !in priority }.shuffled(random))
            .take(MAX_PER_RUN)
        val noNetwork = AtomicBoolean(false)
        val gate = Semaphore(PARALLELISM)
        coroutineScope {
            queue.map { host ->
                async {
                    gate.acquire()
                    try {
                        if (noNetwork.get()) return@async
                        pause()
                        if (noNetwork.get()) return@async
                        val addresses = resolver.resolve(host)
                        if (addresses == null) {
                            noNetwork.set(true)
                            return@async
                        }
                        lock.withLock {
                            entries[host] = classify(addresses)
                            publish()
                            save()
                        }
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll()
        }
        lock.withLock {
            prune(hosts)
            save()
        }
    }

    private fun classify(addresses: List<InetAddress>): Entry {
        val stamp = now()
        if (addresses.isEmpty()) return Entry(null, null, stamp)
        val byCountry = addresses.mapNotNull { address ->
            val bytes = address.address
            geo.lookup(bytes)?.let { it to (address.hostAddress ?: return@let null) }
        }
        val best = byCountry.groupBy({ it.first }, { it.second }).maxByOrNull { it.value.size }
        return if (best != null) {
            Entry(best.key, best.value.first(), stamp)
        } else {
            // Адрес получен, но страны у него нет (служебный диапазон): не мучаем сеть неделю.
            Entry(null, addresses.first().hostAddress, stamp)
        }
    }

    private fun isDue(entry: Entry?): Boolean {
        if (entry == null) return true
        val age = (now() - entry.at).coerceAtLeast(0)
        return age >= if (entry.address != null) REFRESH_MILLIS else RETRY_MILLIS
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = withContext(Dispatchers.IO) { readFile() } ?: return
        val currentDb = geo.databaseId
        stored.second.forEach { (host, entry) ->
            // Новая таблица (после обновления приложения): страну пересчитываем по сохранённому
            // адресу, без сети. Имя при этом не разрешается заново.
            val recomputed = if (stored.first != currentDb && currentDb != UNAVAILABLE && entry.address != null) {
                val country = IpLiteral.parse(entry.address)?.let(geo::lookup)
                entry.copy(country = country)
            } else {
                entry
            }
            entries[host] = recomputed
        }
    }

    private fun publish() {
        mutableCountries.value = entries.mapNotNull { (host, entry) -> entry.country?.let { host to it } }.toMap()
    }

    private fun prune(requested: Set<String>) {
        val limit = now() - FORGET_MILLIS
        val stale = entries.filter { (host, entry) -> host !in requested && entry.at < limit }.keys.toList()
        stale.forEach(entries::remove)
        if (entries.size > MAX_ENTRIES) {
            entries.entries
                .filter { it.key !in requested }
                .sortedBy { it.value.at }
                .take(entries.size - MAX_ENTRIES)
                .forEach { entries.remove(it.key) }
        }
    }

    private fun readFile(): Pair<String, Map<String, Entry>>? {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return null
        return runCatching {
            val root = JsonConfig.parse(file.readText()) as? JsonObject ?: return null
            val db = (root["db"] as? JsonPrimitive)?.contentOrNull ?: return null
            val items = (root["entries"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val host = (item["host"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val at = (item["at"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val country = (item["cc"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.length == 2 && it.all(Char::isLetter) }?.uppercase()
                val address = (item["addr"] as? JsonPrimitive)?.contentOrNull
                host to Entry(country, address, at)
            }.toMap()
            db to items
        }.getOrNull()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val payload = JsonConfig.format(
                buildJsonObject {
                    put("version", 1)
                    put("db", geo.databaseId)
                    put(
                        "entries",
                        buildJsonArray {
                            entries.forEach { (host, entry) ->
                                add(
                                    buildJsonObject {
                                        put("host", host)
                                        entry.country?.let { put("cc", it) }
                                        entry.address?.let { put("addr", it) }
                                        put("at", entry.at)
                                    },
                                )
                            }
                        },
                    )
                },
            ).encodeToByteArray()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeBytes(payload)
            if (!temporary.renameTo(file)) {
                file.delete()
                check(temporary.renameTo(file)) { "rename failed" }
            }
        }
    }

    companion object {
        const val REFRESH_MILLIS = 7L * 24 * 60 * 60 * 1_000
        const val RETRY_MILLIS = 24L * 60 * 60 * 1_000
        const val FORGET_MILLIS = 30L * 24 * 60 * 60 * 1_000
        const val PARALLELISM = 3
        const val MAX_PER_RUN = 64
        const val MAX_ENTRIES = 512
        private const val MAX_FILE_BYTES = 256L * 1024
        private const val PAUSE_MIN_MILLIS = 150L
        private const val PAUSE_MAX_MILLIS = 1_200L
        private const val UNAVAILABLE = "unavailable"
    }
}
