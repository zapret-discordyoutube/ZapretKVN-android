package io.github.zapretkvn.android.updates

import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Ответ на GET; тело читает вызывающий и обязан закрыть ответ. */
class UpdateResponse(
    /** 200 (диапазон проигнорирован), 206 (частичный ответ) или 416 (диапазон за пределами файла). */
    val status: Int,
    /** Первый байт тела в файле: 0 для 200, начало диапазона для 206. */
    val rangeStart: Long,
    /** Полный размер файла, если сервер его сообщил; иначе -1. */
    val totalLength: Long,
    /** Длина тела, если известна; иначе -1. */
    val contentLength: Long,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            body.close()
        } catch (_: IOException) {
        } finally {
            onClose()
        }
    }
}

interface UpdateHttpClient {
    /**
     * GET [url]. Если [start] > 0 или задан [endInclusive], отправляется заголовок Range.
     * Редиректы внутри Forgejo обрабатывает реализация.
     */
    fun open(url: String, start: Long = 0, endInclusive: Long? = null): UpdateResponse

    /** Входная страница со списком имён (не Forgejo): хосты — только [ForgejoHosts.ENTRY_HOSTS]. */
    fun readEntryList(url: String, maxBytes: Int): String = readText(url, maxBytes)

    fun readText(url: String, maxBytes: Int): String = open(url).use { response ->
        if (response.contentLength > maxBytes) {
            throw UpdateException("Ответ Forgejo слишком большой.", retryViaVpn = true)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        try {
            while (true) {
                val count = response.body.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw UpdateException("Ответ Forgejo слишком большой.", retryViaVpn = true)
                }
                output.write(buffer, 0, count)
            }
        } catch (error: IOException) {
            throw UpdateException("Не удалось связаться с Forgejo.", cause = error, retryViaVpn = true)
        }
        output.toString(Charsets.UTF_8.name())
    }
}

fun interface UpdateReleaseSource {
    fun latest(channel: UpdateChannel): UpdateCandidate
}

/**
 * Один маршрут к Forgejo.
 *
 * @param direct соединения, привязанные к физической сети в обход активного VPN. Используются
 *   только для [UpdateRoute.Direct]; [UpdateRoute.Vpn] идёт по маршруту процесса по умолчанию,
 *   то есть в туннель, когда временное правило updater включено.
 */
class ForgejoHttpsClient(
    private val direct: UpdateDirectConnections? = null,
    private val route: UpdateRoute = UpdateRoute.Direct,
) : UpdateHttpClient {
    // Адрес, на который в прошлый раз привели редиректы: следующим диапазонным запросам не нужно
    // заново проходить всю цепочку. Хост всё равно проверяется validatedUrl.
    private val resolved = ConcurrentHashMap<String, String>()

    override fun open(url: String, start: Long, endInclusive: Long?): UpdateResponse {
        val original = validatedUrl(url)
        val cached = resolved[original]
        if (cached != null) {
            try {
                return openFrom(cached, original, start, endInclusive, remember = false)
            } catch (_: UpdateException) {
                resolved.remove(original)
            }
        }
        return openFrom(original, original, start, endInclusive, remember = true)
    }

    private fun openFrom(
        first: String,
        original: String,
        start: Long,
        endInclusive: Long?,
        remember: Boolean,
    ): UpdateResponse {
        var current = first
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val connection = open(URL(current))
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json, application/octet-stream, */*")
                // Без сжатия: иначе HttpURLConnection сам добавит gzip и длины диапазонов перестанут сходиться.
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (start > 0 || endInclusive != null) {
                    connection.setRequestProperty("Range", "bytes=$start-${endInclusive ?: ""}")
                }
                when (val status = connection.responseCode) {
                    in REDIRECTS -> {
                        if (redirectIndex == MAX_REDIRECTS) throw UpdateException("Слишком много перенаправлений Forgejo.")
                        val location = connection.getHeaderField("Location")
                            ?: throw UpdateException("Forgejo вернул перенаправление без адреса.")
                        current = validatedUrl(URI(current).resolve(location).toString())
                        connection.disconnect()
                        return@repeat
                    }
                    200, 206 -> {
                        if (remember && current != original) resolved[original] = current
                        return response(connection, status, start)
                    }
                    416 -> return UpdateResponse(
                        status = 416,
                        rangeStart = 0,
                        totalLength = contentRangeTotal(connection.getHeaderField("Content-Range")),
                        contentLength = 0,
                        body = ByteArrayInputStream(ByteArray(0)),
                        onClose = connection::disconnect,
                    )
                    in 201..299 -> throw UpdateException("Forgejo вернул неожиданный HTTP $status.")
                    403, 429, 451 -> throw UpdateException(
                        "Прямой доступ к Forgejo отклонён (HTTP $status).",
                        retryViaVpn = true,
                    )
                    404 -> throw UpdateException("Forgejo Release пока не опубликован.")
                    in 500..599 -> throw UpdateException(
                        "Forgejo временно недоступен (HTTP $status).",
                        retryViaVpn = true,
                    )
                    else -> throw UpdateException("Forgejo вернул HTTP $status.")
                }
            } catch (error: UpdateException) {
                connection.disconnect()
                throw error
            } catch (error: IOException) {
                connection.disconnect()
                throw UpdateException(
                    "Не удалось связаться с Forgejo.",
                    cause = error,
                    retryViaVpn = true,
                )
            }
        }
        throw UpdateException("Не удалось получить файл Forgejo Release.")
    }

    private fun response(connection: HttpsURLConnection, status: Int, requestedStart: Long): UpdateResponse {
        val contentRange = connection.getHeaderField("Content-Range")
        val declared = connection.contentLengthLong
        return if (status == 206) {
            val range = parseContentRange(contentRange)
                ?: throw UpdateException("Forgejo вернул некорректный Content-Range.", retryViaVpn = true)
            if (range.first != requestedStart) {
                throw UpdateException("Forgejo вернул другой диапазон.", retryViaVpn = true)
            }
            UpdateResponse(206, range.first, range.third, declared, connection.inputStream, connection::disconnect)
        } else {
            UpdateResponse(200, 0, declared, declared, connection.inputStream, connection::disconnect)
        }
    }

    override fun readEntryList(url: String, maxBytes: Int): String {
        var current = entryUrl(url)
        repeat(MAX_REDIRECTS + 1) {
            val connection = open(URL(current))
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                when (connection.responseCode) {
                    in REDIRECTS -> current = entryUrl(
                        URI(current).resolve(
                            connection.getHeaderField("Location")
                                ?: throw UpdateException("Входная страница вернула перенаправление без адреса."),
                        ).toString(),
                    )
                    200 -> return connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            if (output.size() > maxBytes) throw UpdateException("Входная страница слишком большая.")
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                    else -> throw UpdateException("Входная страница ответила ${connection.responseCode}.", retryViaVpn = true)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateException("Слишком много перенаправлений входной страницы.")
    }

    private fun entryUrl(raw: String): String {
        val uri = try {
            URI(raw)
        } catch (error: Exception) {
            throw UpdateException("Некорректный адрес входной страницы.", error)
        }
        if (uri.scheme != "https" || uri.host?.lowercase() !in ForgejoHosts.ENTRY_HOSTS || uri.userInfo != null ||
            uri.port !in setOf(-1, 443)
        ) {
            throw UpdateException("Входная страница вне разрешённых хостов.")
        }
        return uri.toASCIIString()
    }

    private fun open(url: URL): HttpsURLConnection {
        val bound = try {
            if (route == UpdateRoute.Direct) direct?.open(url) else null
        } catch (error: IOException) {
            throw UpdateException("Не удалось связаться с Forgejo.", cause = error, retryViaVpn = true)
        }
        return (bound ?: url.openConnection()) as? HttpsURLConnection
            ?: throw UpdateException("Для обновлений разрешён только HTTPS.")
    }

    companion object {
        /** Нейтральный User-Agent обычного мобильного браузера: приложение не объявляет себя в запросе. */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"

        fun validatedUrl(raw: String): String {
            val uri = try {
                URI(raw)
            } catch (error: Exception) {
                throw UpdateException("Forgejo вернул некорректный URL.", error)
            }
            val host = uri.host?.lowercase()
            val allowedHost = ForgejoHosts.isKnown(host)
            if (uri.scheme != "https" || !allowedHost || uri.userInfo != null ||
                uri.fragment != null || uri.port !in setOf(-1, 443)
            ) {
                throw UpdateException("Перенаправление за пределы Forgejo запрещено.")
            }
            return uri.toASCIIString()
        }

        /** "bytes 100-199/1000" -> (100, 199, 1000); "*" вместо размера даёт -1. */
        internal fun parseContentRange(value: String?): Triple<Long, Long, Long>? {
            val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
            val first = match.groupValues[1].toLongOrNull() ?: return null
            val last = match.groupValues[2].toLongOrNull() ?: return null
            val total = if (match.groupValues[3] == "*") -1L else match.groupValues[3].toLongOrNull() ?: return null
            if (last < first) return null
            return Triple(first, last, total)
        }

        private fun contentRangeTotal(value: String?): Long =
            Regex("""bytes\s+\*/(\d+)""").matchEntire(value?.trim().orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: -1L

        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 20_000
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
    }
}

class ForgejoUpdateSource(
    repository: String,
    private val applicationId: String,
    private val http: UpdateHttpClient = ForgejoHttpsClient(),
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
) : UpdateReleaseSource {
    private val repository = repository.also {
        if (!REPOSITORY.matches(it)) throw IllegalArgumentException("Invalid Forgejo repository: $it")
    }

    // Имя, под которым ответил список выпусков: с него же берутся файлы выпуска.
    @Volatile
    private var answeredHost: String = ForgejoHosts.BUILT_IN.first()

    override fun latest(channel: UpdateChannel): UpdateCandidate {
        val releases = UpdateJson.releases(readReleaseList())
            .filterNot(ForgejoRelease::draft)
            .filter { release ->
                when (channel) {
                    UpdateChannel.Stable -> !release.prerelease
                    UpdateChannel.Beta -> release.prerelease
                }
            }
            // Release lists can group prereleases by SemVer tag instead of
            // publication time (for example, -test.* before a newer -beta.*).
            // ISO-8601 UTC timestamps sort lexicographically; missing legacy values
            // stay in the original stable API order behind timestamped releases.
            .sortedByDescending { it.publishedAt.orEmpty() }
        if (releases.isEmpty()) throw UpdateException("В выбранном канале нет Forgejo Releases.")

        return candidate(releases.first())
    }

    /**
     * Список выпусков под первым ответившим именем. Молчат все известные имена — новые имена
     * берутся со входной страницы (ForgejoHosts.ENTRY_LISTS) и пробуются они; ошибка последнего — наружу.
     */
    private fun readReleaseList(): String {
        val known = listOf(answeredHost) + ForgejoHosts.all().filter { it != answeredHost }
        var failure: UpdateException? = null
        for (host in known) {
            try {
                return readReleaseListFrom(host)
            } catch (error: UpdateException) {
                failure = error
            }
        }
        for (url in ForgejoHosts.ENTRY_LISTS) {
            val fresh = try {
                ForgejoHosts.learnFromEntry(http.readEntryList(url, MAX_ENTRY_LIST_BYTES)).filter { it !in known }
            } catch (error: UpdateException) {
                failure = failure ?: error
                continue
            }
            for (host in fresh) {
                try {
                    return readReleaseListFrom(host)
                } catch (error: UpdateException) {
                    failure = error
                }
            }
            if (fresh.isNotEmpty()) break
        }
        throw failure ?: UpdateException("Не удалось связаться с Forgejo.", retryViaVpn = true)
    }

    private fun readReleaseListFrom(host: String): String {
        val text = http.readText("https://$host/api/v1/repos/$repository/releases?limit=20", MAX_RELEASE_JSON_BYTES)
        answeredHost = host
        return text
    }

    private fun candidate(release: ForgejoRelease): UpdateCandidate {
        val matrixAssets = release.assets.filter { it.name == MATRIX_METADATA_FILE }
        val legacyAssets = release.assets.filter { it.name == LEGACY_METADATA_FILE }
        val metadataAsset = when {
            matrixAssets.size == 1 -> matrixAssets.single()
            matrixAssets.size > 1 -> throw UpdateException("Release содержит повторяющийся $MATRIX_METADATA_FILE.")
            legacyAssets.size == 1 -> legacyAssets.single()
            else -> throw UpdateException("Release должен содержать ровно один файл metadata.")
        }
        val metadata = UpdateJson.metadata(
            http.readText(releaseAssetUrl(metadataAsset.downloadUrl), MAX_METADATA_BYTES),
            supportedAbis,
        )
        if (metadata.applicationId != applicationId) {
            throw UpdateException("Release предназначен для другого Android package.")
        }
        if (metadata.versionName != release.tag.removePrefix("v")) {
            throw UpdateException("Версия metadata не совпадает с Forgejo tag.")
        }
        val apk = release.assets.singleOrNull { it.name == metadata.apkFile }
            ?: throw UpdateException("Release должен содержать ровно один заявленный APK.")
        releaseAssetUrl(apk.downloadUrl)
        if (apk.size != metadata.apkSize) throw UpdateException("Размер APK не совпадает с metadata.")
        apk.digest?.let { digest ->
            if (UpdateJson.normalizedSha256(digest) != metadata.apkSha256) {
                throw UpdateException("Forgejo digest APK не совпадает с metadata.")
            }
        }
        val checksum = release.assets.singleOrNull { it.name == "${metadata.apkFile}.sha256" }
            ?: throw UpdateException("Release не содержит SHA-256 asset.")
        val published = UpdateJson.checksum(
            http.readText(releaseAssetUrl(checksum.downloadUrl), MAX_CHECKSUM_BYTES),
            metadata.apkFile,
        )
        if (published != metadata.apkSha256) throw UpdateException("Опубликованные SHA-256 не совпадают.")
        return UpdateCandidate(release, metadata, apk, checksum)
    }

    private fun releaseAssetUrl(value: String): String {
        val trusted = ForgejoHttpsClient.validatedUrl(value)
        val expectedPrefix = "https://$answeredHost/$repository/releases/download/"
        if (!trusted.startsWith(expectedPrefix)) {
            throw UpdateException("Forgejo вернул asset из другого репозитория.")
        }
        return trusted
    }

    private companion object {
        const val LEGACY_METADATA_FILE = "release-metadata.json"
        const val MATRIX_METADATA_FILE = "release-metadata-v2.json"
        const val MAX_RELEASE_JSON_BYTES = 1024 * 1024
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_CHECKSUM_BYTES = 4 * 1024
        const val MAX_ENTRY_LIST_BYTES = 64 * 1024
        val REPOSITORY = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    }
}

/**
 * Имена Forgejo проекта: один сайт под несколькими именами. git.zapret.moe в России режут по имени
 * (TLS SNI), поэтому зеркала идут первыми; API, спрошенный под зеркалом, отдаёт ссылки на файлы на
 * том же зеркале. С 30.10.2026 людям раздаются зеркала со случайными именами (cwdnvtsqdc.zapret.moe):
 * их приложение узнаёт со входной страницы на большой платформе (Google Apps Script, gist GitHub;
 * их пишет zapret-beacon на сервере, pve-admin servers.md «Entry pages»), когда молчат все известные
 * имена. Принимается только одна метка под zapret.moe, имя с приставкой — только git: чужой хост
 * в проверку адресов ([ForgejoHttpsClient.validatedUrl]) так не попадёт. Тот же принцип, что у
 * zapret_update/mirrors.py в Windows-версии.
 */
object ForgejoHosts {
    val BUILT_IN = listOf("git2.zapret.moe", "git3.zapret.moe", "git4.zapret.moe", "git.zapret.moe")
    val ENTRY_LISTS = listOf(
        "https://script.google.com/macros/s/AKfycbzcEPk67WW6fYC0vPVm397QyDFHd4m-SrfKDBnfvG8jAD-n_HXVv8AqQnEHZ6A42oIh/exec?format=json",
        "https://gist.githubusercontent.com/fuckyourkn/ecb9b6a03f53bddcdd47023ebc12d4f9/raw/targets.json",
    )
    val ENTRY_HOSTS = setOf("script.google.com", "script.googleusercontent.com", "gist.githubusercontent.com")
    private val NAME = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.zapret\\.moe")
    private val PREFIXED = Regex("(git|wiki)\\d*\\.zapret\\.moe")

    @Volatile
    private var learned: List<String> = emptyList()

    /** Имена в порядке, в котором их пробовать: узнанные со входной страницы, затем встроенные. */
    fun all(): List<String> = (learned + BUILT_IN).distinct()

    fun isKnown(host: String?): Boolean = host != null && host in all()

    fun accepts(name: String): Boolean {
        if (!NAME.matches(name)) return false
        val family = PREFIXED.matchEntire(name)?.groupValues?.get(1)
        return family == null || family == "git"
    }

    /** Имена git со входной страницы (схема 1, families.git.hosts); запоминаются. */
    fun learnFromEntry(text: String): List<String> {
        val hosts = try {
            val document = Json.parseToJsonElement(text).jsonObject
            if (document["schema"]?.jsonPrimitive?.intOrNull != 1) return emptyList()
            document["families"]?.jsonObject?.get("git")?.jsonObject?.get("hosts")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }
                ?.filter(::accepts)?.distinct().orEmpty()
        } catch (error: SerializationException) {
            throw UpdateException("Входная страница вернула не JSON.", error)
        } catch (error: IllegalArgumentException) {
            throw UpdateException("Входная страница вернула неожиданный JSON.", error)
        }
        if (hosts.isNotEmpty()) learned = hosts
        return hosts
    }

    internal fun forgetForTests() {
        learned = emptyList()
    }
}
