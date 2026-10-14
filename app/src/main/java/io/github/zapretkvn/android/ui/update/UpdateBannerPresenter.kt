package io.github.zapretkvn.android.ui.update

import androidx.compose.runtime.Immutable
import io.github.zapretkvn.android.updates.UpdateState
import io.github.zapretkvn.core.uimodel.formatBytes

internal enum class BannerAction(val label: String) {
    Install("Установить"),
    Download("Скачать"),
}

/** Узкая плашка на Главной: без окон и без блокировки экрана. */
@Immutable
internal data class UpdateBannerUi(
    val versionCode: Long,
    val text: String,
    val action: BannerAction?,
    /** null — полосы прогресса нет. */
    val progress: Float?,
    val dismissible: Boolean,
)

/**
 * Что показать на плашке для состояния обновления. Скрытая для этой версии плашка не показывается,
 * кроме момента установки: пока система ставит обновление, пользователь должен это видеть.
 */
internal fun presentBanner(state: UpdateState, dismissedVersionCode: Long): UpdateBannerUi? {
    val banner = when (state) {
        is UpdateState.Ready -> {
            val metadata = state.candidate.metadata
            UpdateBannerUi(
                versionCode = metadata.versionCode,
                text = "Обновление ${metadata.versionName} готово",
                action = BannerAction.Install,
                progress = null,
                dismissible = true,
            )
        }
        is UpdateState.Downloading -> {
            val metadata = state.candidate.metadata
            UpdateBannerUi(
                versionCode = metadata.versionCode,
                text = "Загружается обновление ${metadata.versionName} · " +
                    "${formatBytes(state.downloadedBytes)} из ${formatBytes(state.totalBytes)}",
                action = null,
                progress = if (state.totalBytes > 0) {
                    (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                },
                dismissible = true,
            )
        }
        is UpdateState.Available -> {
            val metadata = state.candidate.metadata
            UpdateBannerUi(
                versionCode = metadata.versionCode,
                text = "Доступна версия ${metadata.versionName}",
                action = BannerAction.Download,
                progress = null,
                dismissible = true,
            )
        }
        is UpdateState.Installing -> UpdateBannerUi(
            versionCode = state.candidate.metadata.versionCode,
            text = "Устанавливается обновление ${state.candidate.metadata.versionName}…",
            action = null,
            progress = null,
            dismissible = false,
        )
        else -> return null
    }
    return banner.takeUnless { it.dismissible && it.versionCode == dismissedVersionCode }
}
