package io.github.zapretkvn.android.updates

import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

interface UpdateHttpClient {
    fun readText(url: String, maxBytes: Int): String
    fun download(url: String, target: File, expectedBytes: Long, onProgress: (Long) -> Unit)
}

fun interface UpdateReleaseSource {
    fun latest(channel: UpdateChannel): UpdateCandidate
}

class ForgejoHttpsClient : UpdateHttpClient {
    override fun readText(url: String, maxBytes: Int): String = request(url) { connection ->
        val declared = connection.contentLengthLong
        if (declared > maxBytes) {
            throw UpdateException("Ответ Forgejo слишком большой.", retryViaVpn = true)
        }
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw UpdateException("Ответ Forgejo слишком большой.", retryViaVpn = true)
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    override fun download(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: (Long) -> Unit,
    ) = request(url) { connection ->
        if (expectedBytes <= 0 || expectedBytes > UpdateJson.MAX_APK_BYTES) {
            throw UpdateException("Некорректный размер APK.")
        }
        val declared = connection.contentLengthLong
        if (declared > 0 && declared != expectedBytes) {
            throw UpdateException(
                "Размер ответа не совпадает с Forgejo Release.",
                retryViaVpn = true,
            )
        }
        target.outputStream().buffered().use { output ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > expectedBytes || total > UpdateJson.MAX_APK_BYTES) {
                        throw UpdateException(
                            "Ответ больше опубликованного размера APK.",
                            retryViaVpn = true,
                        )
                    }
                    output.write(buffer, 0, count)
                    onProgress(total)
                }
                output.flush()
                if (total != expectedBytes) {
                    throw UpdateException("Загрузка APK прервана.", retryViaVpn = true)
                }
            }
        }
    }

    private fun <T> request(rawUrl: String, block: (HttpsURLConnection) -> T): T {
        var current = validatedUrl(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val connection = URL(current).openConnection() as? HttpsURLConnection
                ?: throw UpdateException("Для обновлений разрешён только HTTPS.")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json, application/octet-stream")
                connection.setRequestProperty("User-Agent", "Zapret-KVN-Android-Updater")
                when (val status = connection.responseCode) {
                    in REDIRECTS -> {
                        if (redirectIndex == MAX_REDIRECTS) throw UpdateException("Слишком много перенаправлений Forgejo.")
                        val location = connection.getHeaderField("Location")
                            ?: throw UpdateException("Forgejo вернул перенаправление без адреса.")
                        current = validatedUrl(URI(current).resolve(location).toString())
                        return@repeat
                    }
                    in 200..299 -> return block(connection)
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
                throw error
            } catch (error: IOException) {
                throw UpdateException(
                    "Не удалось связаться с Forgejo.",
                    cause = error,
                    retryViaVpn = true,
                )
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateException("Не удалось получить файл Forgejo Release.")
    }

    companion object {
        fun validatedUrl(raw: String): String {
            val uri = try {
                URI(raw)
            } catch (error: Exception) {
                throw UpdateException("Forgejo вернул некорректный URL.", error)
            }
            val host = uri.host?.lowercase()
            val allowedHost = host == FORGEJO_HOST
            if (uri.scheme != "https" || !allowedHost || uri.userInfo != null ||
                uri.fragment != null || uri.port !in setOf(-1, 443)
            ) {
                throw UpdateException("Перенаправление за пределы Forgejo запрещено.")
            }
            return uri.toASCIIString()
        }

        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MILLIS = 30_000
        private const val FORGEJO_HOST = "git.zapret.moe"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
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

    override fun latest(channel: UpdateChannel): UpdateCandidate {
        val endpoint = "https://git.zapret.moe/api/v1/repos/$repository/releases?limit=20"
        val releases = UpdateJson.releases(http.readText(endpoint, MAX_RELEASE_JSON_BYTES))
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
        val expectedPrefix = "https://git.zapret.moe/$repository/releases/download/"
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
        val REPOSITORY = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    }
}
