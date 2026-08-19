package io.github.zapretkvn.android.importer

import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.profiles.AndroidAtomicProfileWriter
import io.github.zapretkvn.android.profiles.AtomicProfileWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

fun interface SubscriptionFetcher {
    fun fetch(url: String): String
}

class HttpSubscriptionFetcher : SubscriptionFetcher {
    override fun fetch(url: String): String {
        var current = validatedUrl(url)
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val connection = (URL(current).openConnection() as? HttpURLConnection)
                ?: throw ImportException("URL подписки не является HTTP(S).")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json, text/plain, */*")
                connection.setRequestProperty("User-Agent", "Zapret-KVN-Android")
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirectIndex == MAX_REDIRECTS) {
                        throw ImportException("Слишком много перенаправлений подписки.")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw ImportException("Сервер вернул перенаправление без адреса.")
                    val next = validatedUrl(URI(current).resolve(location).toString())
                    if (current.startsWith("https://", true) && next.startsWith("http://", true)) {
                        throw ImportException("Переход подписки с HTTPS на HTTP запрещён.")
                    }
                    current = next
                    return@repeat
                }
                if (status !in 200..299) {
                    throw ImportException("Сервер подписки вернул HTTP $status.")
                }
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_IMPORT_BYTES) {
                    throw ImportException("Подписка больше 4 МБ.")
                }
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMPORT_BYTES) throw ImportException("Подписка больше 4 МБ.")
                        output.write(buffer, 0, count)
                    }
                    return output.toString(Charsets.UTF_8.name())
                }
            } finally {
                connection.disconnect()
            }
        }
        throw ImportException("Не удалось получить подписку.")
    }

    companion object {
        fun validatedUrl(raw: String): String {
            val value = raw.trim()
            val uri = runCatching { URI(value) }
                .getOrElse { throw ImportException("Некорректный URL подписки.") }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                throw ImportException("Поддерживаются только полные HTTP(S) URL подписок.")
            }
            // Fragments are client-side labels and are never sent in an HTTP request.
            // Drop them before fetching and persisting the refresh source.
            val fetchUri = if (uri.rawFragment != null) URI(value.substringBefore('#')) else uri
            return fetchUri.toASCIIString()
        }

        private const val TIMEOUT_MILLIS = 15_000
        private const val MAX_REDIRECTS = 3
        private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** Stores refresh URLs outside profiles/index.json, which remains credentials-free UI metadata. */
class SubscriptionSourceStore(
    private val root: File,
    private val writer: AtomicProfileWriter = AndroidAtomicProfileWriter(),
) {
    private val mutex = Mutex()

    suspend fun get(profileId: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock { read()[profileId] }
    }

    suspend fun ids(): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { read().keys }
    }

    suspend fun put(profileId: String, url: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = read().toMutableMap()
            entries[profileId] = HttpSubscriptionFetcher.validatedUrl(url)
            write(entries)
        }
    }

    suspend fun remove(profileId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = read().toMutableMap()
            if (entries.remove(profileId) != null) write(entries)
        }
    }

    suspend fun retain(profileIds: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = read()
            val retained = entries.filterKeys(profileIds::contains)
            if (retained.size != entries.size) write(retained)
        }
    }

    private fun read(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return try {
            val rootObject = JsonConfig.parse(file.readText(Charsets.UTF_8)) as? JsonObject
                ?: throw ImportException("Хранилище источников подписок повреждено.")
            rootObject.mapNotNull { (id, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { id to it }
            }.toMap()
        } catch (error: ImportException) {
            throw error
        } catch (error: Exception) {
            throw ImportException("Не удалось прочитать источники подписок.", error)
        }
    }

    private fun write(entries: Map<String, String>) {
        val json = buildJsonObject {
            entries.toSortedMap().forEach { (id, url) -> put(id, url) }
        }
        writer.writeAtomic(file, JsonConfig.format(json).toByteArray(Charsets.UTF_8))
    }

    private val file: File get() = File(root, "index.json")
}
