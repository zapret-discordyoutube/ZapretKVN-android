package io.github.zapretkvn.android.ui.settings

import androidx.compose.runtime.Immutable
import io.github.zapretkvn.android.updates.UpdateCandidate
import io.github.zapretkvn.android.updates.UpdateState
import io.github.zapretkvn.core.uimodel.formatBytes

@Immutable
data class ReleaseUi(
    val versionName: String,
    /** «Ядро v1.2 · arm64-v8a · 45.2 МБ». */
    val details: String,
    /** Markdown заметок к релизу; пусто, если автор ничего не написал. */
    val notes: String,
)

/**
 * Состояния блока обновлений. Промежуточных «пробуем через VPN» здесь нет: маршрут выбирается молча,
 * пользователь видит только проверку, загрузку и итог.
 */
@Immutable
sealed interface UpdateSectionUi {
    data object Idle : UpdateSectionUi
    data class Busy(val message: String) : UpdateSectionUi
    data class UpToDate(val message: String) : UpdateSectionUi
    data class Available(val release: ReleaseUi) : UpdateSectionUi
    data class Downloading(
        val release: ReleaseUi,
        /** null — размер неизвестен, показываем неопределённый индикатор. */
        val progress: Float?,
        val progressLabel: String,
        /** «Экономия 114 МБ» при загрузке только изменившихся частей; пусто, если качаем целиком. */
        val savingLabel: String,
    ) : UpdateSectionUi
    data class Ready(val release: ReleaseUi, val needsConfirmation: Boolean) : UpdateSectionUi
    data class Installing(val release: ReleaseUi) : UpdateSectionUi
    data class Failure(val message: String, val canRetryDownload: Boolean) : UpdateSectionUi
}

fun presentUpdate(state: UpdateState): UpdateSectionUi = when (state) {
    UpdateState.Idle -> UpdateSectionUi.Idle
    is UpdateState.Checking -> UpdateSectionUi.Busy("Проверяем обновления…")
    is UpdateState.UpToDate ->
        UpdateSectionUi.UpToDate("У вас последняя версия: ${state.currentVersion}.")
    is UpdateState.Available -> UpdateSectionUi.Available(releaseUi(state.candidate))
    is UpdateState.Downloading -> {
        val progress = if (state.totalBytes > 0) {
            (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
        UpdateSectionUi.Downloading(
            release = releaseUi(state.candidate),
            progress = progress,
            progressLabel = downloadLabel(state.downloadedBytes, state.totalBytes),
            savingLabel = savingLabel(state.savedBytes),
        )
    }
    is UpdateState.Ready -> UpdateSectionUi.Ready(releaseUi(state.candidate), state.needsConfirmation)
    is UpdateState.Installing -> UpdateSectionUi.Installing(releaseUi(state.candidate))
    is UpdateState.Failure -> UpdateSectionUi.Failure(
        message = state.message,
        canRetryDownload = state.candidate != null,
    )
}

/** «Загружается 1.2 МБ из 3.1 МБ»; при неизвестном размере — только объём. */
fun downloadLabel(downloaded: Long, total: Long): String =
    if (total <= 0) {
        formatBytes(downloaded)
    } else {
        "Загружается ${formatBytes(downloaded)} из ${formatBytes(total)}"
    }

fun savingLabel(saved: Long): String =
    if (saved < MIN_SHOWN_SAVING) "" else "экономия ${formatBytes(saved)}"

private const val MIN_SHOWN_SAVING = 1024L * 1024

private fun releaseUi(candidate: UpdateCandidate): ReleaseUi {
    val metadata = candidate.metadata
    return ReleaseUi(
        versionName = metadata.versionName,
        details = listOfNotNull(
            "Ядро ${metadata.coreTag}",
            metadata.abi.firstOrNull(),
            metadata.apkSize.takeIf { it > 0 }?.let(::formatBytes),
        ).joinToString(" · "),
        notes = candidate.release.body.trim(),
    )
}
