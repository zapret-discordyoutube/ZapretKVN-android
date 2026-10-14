package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.diagnostics.SecretRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Общий запуск пользовательских операций: флаг `busy` и показ сообщения об ошибке.
 * Единственный владелец поля [ProfilesUiState.message].
 */
internal class ProfilesOperations(
    private val state: MutableStateFlow<ProfilesUiState>,
    private val scope: CoroutineScope,
) {
    fun run(markBusy: Boolean = true, block: suspend () -> Unit) {
        scope.launch {
            if (markBusy) state.update { it.copy(busy = true) }
            try {
                block()
            } catch (error: Exception) {
                showMessage(error.userMessage("Операция не выполнена."))
            } finally {
                if (markBusy) state.update { it.copy(busy = false) }
            }
        }
    }

    fun showMessage(message: String) {
        state.update { it.copy(message = message) }
    }

    fun consumeMessage() {
        state.update { it.copy(message = null) }
    }
}

/** Текст ошибки для пользователя: без секретов и не длиннее 320 символов. */
internal fun Throwable.userMessage(fallback: String): String =
    SecretRedactor.redactInline(message?.takeIf(String::isNotBlank)?.take(320) ?: fallback)
