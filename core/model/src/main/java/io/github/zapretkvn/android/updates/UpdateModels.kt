package io.github.zapretkvn.android.updates

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

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Checking(val channel: String) : UpdateState
    data class UpToDate(val checkedTag: String, val currentVersion: String) : UpdateState
    data class Available(val candidate: UpdateCandidate) : UpdateState

    /**
     * @param totalBytes сколько байт нужно получить по сети (при дельта-загрузке намного меньше размера APK)
     * @param savedBytes сколько байт не придётся скачивать благодаря установленному APK
     */
    data class Downloading(
        val candidate: UpdateCandidate,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val savedBytes: Long = 0,
    ) : UpdateState

    /**
     * APK скачан и проверен. [needsConfirmation] — установщик Android запросил подтверждение:
     * нужно одно нажатие пользователя.
     */
    data class Ready(val candidate: UpdateCandidate, val needsConfirmation: Boolean = false) : UpdateState
    data class Installing(val candidate: UpdateCandidate) : UpdateState

    /** [message] — короткий текст для пользователя; [detail] — техническая причина для журнала и диагностики. */
    data class Failure(
        val message: String,
        val candidate: UpdateCandidate? = null,
        val detail: String? = null,
    ) : UpdateState
}
