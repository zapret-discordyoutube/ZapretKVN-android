package io.github.zapretkvn.android.ui.settings

import androidx.compose.runtime.Immutable
import io.github.zapretkvn.android.updates.UpdateCandidate
import io.github.zapretkvn.android.updates.UpdateOperation
import io.github.zapretkvn.android.updates.UpdateState
import io.github.zapretkvn.core.uimodel.formatBytes

@Immutable
internal data class ReleaseUi(
    val versionName: String,
    /** «Ядро v1.2 · arm64-v8a · 45.2 МБ». */
    val details: String,
    /** Markdown заметок к релизу; пусто, если автор ничего не написал. */
    val notes: String,
)

/**
 * Состояния блока обновлений. Восемь состояний `UpdateState` сводятся к
 * семи видам: «проверяем» и «проверяем через VPN» отличаются только текстом.
 */
@Immutable
internal sealed interface UpdateSectionUi {
    data object Idle : UpdateSectionUi
    data class Busy(val message: String) : UpdateSectionUi
    data class UpToDate(val message: String) : UpdateSectionUi
    data class Available(val release: ReleaseUi) : UpdateSectionUi
    data class Downloading(
        val release: ReleaseUi,
        /** null — размер неизвестен, показываем неопределённый индикатор. */
        val progress: Float?,
        val progressLabel: String,
    ) : UpdateSectionUi
    data class Ready(val release: ReleaseUi) : UpdateSectionUi
    data class Failure(val message: String, val canRetryDownload: Boolean) : UpdateSectionUi
}

internal fun presentUpdate(state: UpdateState): UpdateSectionUi = when (state) {
    UpdateState.Idle -> UpdateSectionUi.Idle
    is UpdateState.Checking -> UpdateSectionUi.Busy("Проверяем обновления…")
    is UpdateState.RetryingViaVpn -> UpdateSectionUi.Busy(
        when (state.operation) {
            UpdateOperation.Check ->
                "Сервер обновлений не отвечает напрямую. Временно включаем VPN и проверяем снова…"
            UpdateOperation.Download ->
                "Загрузка не прошла напрямую. Временно включаем VPN и пробуем снова…"
        },
    )
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
            progressLabel = downloadLabel(state.downloadedBytes, state.totalBytes, progress),
        )
    }
    is UpdateState.Ready -> UpdateSectionUi.Ready(releaseUi(state.candidate))
    is UpdateState.Failure -> UpdateSectionUi.Failure(
        message = state.message,
        canRetryDownload = state.candidate != null,
    )
}

internal fun downloadLabel(downloaded: Long, total: Long, progress: Float?): String =
    if (progress == null) {
        formatBytes(downloaded)
    } else {
        "${formatBytes(downloaded)} из ${formatBytes(total)} · ${(progress * 100).toInt()} %"
    }

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
