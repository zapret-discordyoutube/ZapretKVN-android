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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun interface SubscriptionFetcher {
    fun fetch(source: SubscriptionSource): String
}

class HttpSubscriptionFetcher(
    private val device: DeviceIdentity = DeviceIdentity(),
    private val appVersion: String = "",
) : SubscriptionFetcher {
    override fun fetch(source: SubscriptionSource): String {
        var current = validatedUrl(source.url)
        val origin = URI(current).host.orEmpty()
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val connection = (URL(current).openConnection() as? HttpURLConnection)
                ?: throw ImportException("URL подписки не является HTTP(S).")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.useCaches = false
                // Идентификатор устройства принадлежит только исходному провайдеру:
                // на редиректе к чужому хосту он снимается, как браузеры снимают Authorization.
                val sameOrigin = URI(current).host.orEmpty().equals(origin, ignoreCase = true)
                val effective = if (sameOrigin) source else source.copy(sendHwid = false, hwid = "")
                SubscriptionIdentity.requestHeaders(effective, device, appVersion)
                    .forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirectIndex == MAX_REDIRECTS) {
                        throw ImportException("Слишком много перенаправлений подписки.")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw ImportException("Сервер вернул перенаправление без адреса.")
                    val next = runCatching { URI(current).resolve(location).toString() }
                        .map(::validatedUrl)
                        .getOrElse {
                            throw ImportException("Сервер вернул некорректный адрес перенаправления.")
                        }
                    if (current.startsWith("https://", true) && next.startsWith("http://", true)) {
                        throw ImportException("Переход подписки с HTTPS на HTTP запрещён.")
                    }
                    current = next
                    return@repeat
                }
                if (status !in 200..299) {
                    throw ImportException(
                        SubscriptionIdentity.describeHttpFailure(
                            status,
                            connection.headerFields.orEmpty().keys,
                        ),
                    )
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

    suspend fun get(profileId: String): SubscriptionSource? = withContext(Dispatchers.IO) {
        mutex.withLock { read()[profileId] }
    }

    suspend fun ids(): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { read().keys }
    }

    suspend fun put(profileId: String, source: SubscriptionSource) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = read().toMutableMap()
            entries[profileId] = source.copy(
                url = HttpSubscriptionFetcher.validatedUrl(source.url),
                hwid = if (source.sendHwid) SubscriptionIdentity.validateHwid(source.hwid) else "",
            )
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

    private fun read(): Map<String, SubscriptionSource> {
        if (!file.isFile) return emptyMap()
        return try {
            val rootObject = JsonConfig.parse(file.readText(Charsets.UTF_8)) as? JsonObject
                ?: throw ImportException("Хранилище источников подписок повреждено.")
            rootObject.mapNotNull { (id, value) -> parseEntry(value)?.let { id to it } }.toMap()
        } catch (error: ImportException) {
            throw error
        } catch (error: Exception) {
            throw ImportException("Не удалось прочитать источники подписок.", error)
        }
    }

    /** Записи до появления настроек идентификации хранились одной строкой URL. */
    private fun parseEntry(value: JsonElement): SubscriptionSource? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.let(::SubscriptionSource)
        is JsonObject -> value.string(URL_KEY)?.let { url ->
            SubscriptionSource(
                url = url,
                clientProfile = SubscriptionClientProfile.parse(value.string(CLIENT_PROFILE_KEY)),
                sendHwid = value.boolean(SEND_HWID_KEY),
                hwid = value.string(HWID_KEY).orEmpty(),
                userAgent = value.string(USER_AGENT_KEY).orEmpty(),
            )
        }
        else -> null
    }

    private fun write(entries: Map<String, SubscriptionSource>) {
        val json = buildJsonObject {
            entries.toSortedMap().forEach { (id, source) ->
                putJsonObject(id) {
                    put(URL_KEY, source.url)
                    put(CLIENT_PROFILE_KEY, source.clientProfile.id)
                    put(SEND_HWID_KEY, source.sendHwid)
                    if (source.sendHwid && source.hwid.isNotBlank()) put(HWID_KEY, source.hwid)
                    if (source.userAgent.isNotBlank()) put(USER_AGENT_KEY, source.userAgent)
                }
            }
        }
        writer.writeAtomic(file, JsonConfig.format(json).toByteArray(Charsets.UTF_8))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private val file: File get() = File(root, "index.json")

    private companion object {
        const val URL_KEY = "url"
        const val CLIENT_PROFILE_KEY = "client_profile"
        const val SEND_HWID_KEY = "send_hwid"
        const val HWID_KEY = "hwid"
        const val USER_AGENT_KEY = "user_agent"
    }
}
