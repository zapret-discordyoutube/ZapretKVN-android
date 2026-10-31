package io.github.zapretkvn.android.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState

/**
 * Как долго показывать сообщение приложения.
 *
 * Раньше любое сообщение — и «Скопировано», и объяснение ошибки подключения в
 * две строки — гасло через короткие ~4 с, и длинное не успевали дочитать.
 * Короткое по-прежнему уходит быстро, длинное висит долгий срок (~10 с), а у
 * каждого есть крестик, чтобы убрать его раньше.
 */
internal object AppMessageTiming {
    /** Столько знаков читается за короткий показ. */
    const val SHORT_MAX_CHARS = 50

    fun duration(message: String): SnackbarDuration =
        if (message.trim().length <= SHORT_MAX_CHARS) SnackbarDuration.Short else SnackbarDuration.Long
}

/** Единственный способ показать сообщение приложения в снекбаре. */
internal suspend fun SnackbarHostState.showAppMessage(message: String) {
    showSnackbar(
        message = message,
        withDismissAction = true,
        duration = AppMessageTiming.duration(message),
    )
}
