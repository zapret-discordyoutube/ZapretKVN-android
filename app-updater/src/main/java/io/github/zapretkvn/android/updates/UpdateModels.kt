package io.github.zapretkvn.android.updates

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class ForgejoAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val digest: String?,
)

data class ForgejoRelease(
    val tag: String,
    val title: String,
    val body: String,
    val pageUrl: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ForgejoAsset>,
    val publishedAt: String? = null,
)

data class ReleaseMetadata(
    val versionName: String,
    val versionCode: Long,
    val applicationId: String,
    val coreTag: String,
    val coreCommit: String,
    val abi: List<String>,
    val apkFile: String,
    val apkSha256: String,
    val apkSize: Long,
)

data class UpdateCandidate(
    val release: ForgejoRelease,
    val metadata: ReleaseMetadata,
    val apkAsset: ForgejoAsset,
    val checksumAsset: ForgejoAsset,
)

enum class UpdateChannel {
    Stable,
    Beta,
}

enum class UpdateOperation {
    Check,
    Download,
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Checking(val channel: String) : UpdateState
    data class RetryingViaVpn(
        val operation: UpdateOperation,
        val candidate: UpdateCandidate? = null,
    ) : UpdateState
    data class UpToDate(val checkedTag: String, val currentVersion: String) : UpdateState
    data class Available(val candidate: UpdateCandidate) : UpdateState
    data class Downloading(
        val candidate: UpdateCandidate,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState
    data class Ready(val candidate: UpdateCandidate) : UpdateState
    data class Failure(val message: String, val candidate: UpdateCandidate? = null) : UpdateState
}

class UpdateException(
    message: String,
    cause: Throwable? = null,
    val retryViaVpn: Boolean = false,
) : Exception(message, cause)

object UpdateJson {
    private val json = Json {
        explicitNulls = true
        isLenient = false
    }

    fun releases(raw: String): List<ForgejoRelease> {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (error: Exception) {
            throw UpdateException(
                "Forgejo вернул некорректный JSON.",
                cause = error,
                retryViaVpn = true,
            )
        }
        val objects = when (element) {
            is JsonObject -> listOf(element)
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            else -> throw UpdateException(
                "Forgejo вернул неожиданный формат release.",
                retryViaVpn = true,
            )
        }
        return objects.map(::release)
    }

    fun metadata(
        raw: String,
        supportedAbis: List<String> = listOf("arm64-v8a"),
    ): ReleaseMetadata {
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
                ?: throw UpdateException(
                    "release-metadata.json должен быть объектом.",
                    retryViaVpn = true,
                )
        } catch (error: UpdateException) {
            throw error
        } catch (error: Exception) {
            throw UpdateException(
                "release-metadata.json повреждён.",
                cause = error,
                retryViaVpn = true,
            )
        }
        val artifact = when (root.requiredInt("schema")) {
            1 -> legacyArtifact(root, supportedAbis)
            2 -> matrixArtifact(root, supportedAbis)
            else -> throw UpdateException("Версия release metadata не поддерживается.")
        }
        return ReleaseMetadata(
            versionName = root.requiredString("version_name"),
            versionCode = root.requiredLong("version_code").also {
                if (it <= 0) throw UpdateException("Некорректный versionCode в metadata.")
            },
            applicationId = root.requiredString("application_id"),
            coreTag = root.requiredString("core_tag"),
            coreCommit = root.requiredString("core_commit").also {
                if (!COMMIT.matches(it)) throw UpdateException("Некорректный commit ядра в metadata.")
            },
            abi = listOf(artifact.abi),
            apkFile = artifact.apkFile,
            apkSha256 = artifact.apkSha256,
            apkSize = artifact.apkSize,
        )
    }

    private fun legacyArtifact(root: JsonObject, supportedAbis: List<String>): Artifact {
        val abi = (root["abi"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.singleOrNull()
            ?.takeIf(KNOWN_ABIS::contains)
            ?: throw UpdateException("ABI отсутствует в release metadata.")
        if (abi !in supportedAbis) {
            throw UpdateException("Для архитектуры этого устройства APK не опубликован.")
        }
        return artifact(
            abi = abi,
            apkFile = root.requiredString("apk_file"),
            apkSha256 = root.requiredString("apk_sha256"),
            apkSize = root.requiredLong("apk_size"),
        )
    }

    private fun matrixArtifact(root: JsonObject, supportedAbis: List<String>): Artifact {
        val artifacts = (root["artifacts"] as? JsonArray)
            ?.map { value ->
                val item = value as? JsonObject
                    ?: throw UpdateException("Некорректный APK в release metadata.")
                artifact(
                    abi = item.requiredString("abi"),
                    apkFile = item.requiredString("apk_file"),
                    apkSha256 = item.requiredString("apk_sha256"),
                    apkSize = item.requiredLong("apk_size"),
                )
            }
            ?: throw UpdateException("Список APK отсутствует в release metadata.")
        if (artifacts.map(Artifact::abi).toSet().size != artifacts.size) {
            throw UpdateException("Release содержит повторяющийся ABI.")
        }
        return supportedAbis.firstNotNullOfOrNull { supported ->
            artifacts.singleOrNull { it.abi == supported }
        } ?: throw UpdateException("Для архитектуры этого устройства APK не опубликован.")
    }

    private fun artifact(abi: String, apkFile: String, apkSha256: String, apkSize: Long): Artifact {
        if (abi !in KNOWN_ABIS) throw UpdateException("Release содержит неподдерживаемый ABI.")
        if (!APK_FILE.matches(apkFile)) throw UpdateException("Некорректное имя APK в metadata.")
        if (apkSize <= 0 || apkSize > MAX_APK_BYTES) throw UpdateException("Некорректный размер APK.")
        return Artifact(abi, apkFile, normalizedSha256(apkSha256), apkSize)
    }

    fun checksum(raw: String, expectedFile: String): String {
        val line = raw.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
            ?: throw UpdateException("Файл SHA-256 пуст.")
        val match = CHECKSUM_LINE.matchEntire(line)
            ?: throw UpdateException("Файл SHA-256 имеет неизвестный формат.")
        val fileName = match.groupValues[2].removePrefix("*")
        if (fileName != expectedFile) throw UpdateException("SHA-256 относится к другому APK.")
        return normalizedSha256(match.groupValues[1])
    }

    private fun release(root: JsonObject): ForgejoRelease {
        val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { value ->
            val asset = value as? JsonObject ?: return@mapNotNull null
            ForgejoAsset(
                name = asset.requiredString("name"),
                downloadUrl = asset.requiredString("browser_download_url"),
                size = asset.requiredLong("size"),
                digest = asset.string("digest"),
            )
        }
        return ForgejoRelease(
            tag = root.requiredString("tag_name"),
            title = root.string("name")?.takeIf(String::isNotBlank) ?: root.requiredString("tag_name"),
            body = root.string("body").orEmpty().take(MAX_RELEASE_NOTES_CHARS),
            pageUrl = root.requiredString("html_url"),
            publishedAt = root.string("published_at"),
            draft = root.requiredBoolean("draft"),
            prerelease = root.requiredBoolean("prerelease"),
            assets = assets,
        )
    }

    fun normalizedSha256(raw: String): String {
        val value = raw.removePrefix("sha256:").lowercase(Locale.ROOT)
        if (!SHA256.matches(value)) throw UpdateException("Некорректный SHA-256.")
        return value
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        string(name)?.takeIf(String::isNotBlank)
            ?: throw UpdateException("Поле $name отсутствует в release metadata.")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        (get(name) as? JsonPrimitive)?.booleanOrNull
            ?: throw UpdateException("Поле $name отсутствует в Forgejo release.")

    private fun JsonObject.requiredInt(name: String): Int =
        (get(name) as? JsonPrimitive)?.intOrNull
            ?: throw UpdateException("Поле $name отсутствует в release metadata.")

    private fun JsonObject.requiredLong(name: String): Long =
        (get(name) as? JsonPrimitive)?.longOrNull
            ?: throw UpdateException("Поле $name отсутствует в release metadata.")

    const val MAX_APK_BYTES = 512L * 1024 * 1024
    private data class Artifact(
        val abi: String,
        val apkFile: String,
        val apkSha256: String,
        val apkSize: Long,
    )
    private val KNOWN_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    private val APK_FILE = Regex("[A-Za-z0-9._-]+\\.apk")
    private val COMMIT = Regex("[0-9a-f]{40}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val CHECKSUM_LINE = Regex("([0-9A-Fa-f]{64})[ \\t]+([*]?[A-Za-z0-9._-]+\\.apk)")
    private const val MAX_RELEASE_NOTES_CHARS = 12_000
}
