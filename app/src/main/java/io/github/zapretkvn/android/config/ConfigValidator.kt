package io.github.zapretkvn.android.config

import io.github.zapretkvn.android.diagnostics.SecretRedactor
import kotlinx.serialization.SerializationException

sealed interface ConfigValidationResult {
    data object Valid : ConfigValidationResult

    data class Invalid(val message: String) : ConfigValidationResult
}

fun interface ConfigValidator {
    fun validate(config: String): ConfigValidationResult
}

class LibboxConfigValidator : ConfigValidator {
    override fun validate(config: String): ConfigValidationResult {
        try {
            JsonConfig.parse(config)
        } catch (_: SerializationException) {
            return ConfigValidationResult.Invalid("JSON содержит синтаксическую ошибку.")
        } catch (_: IllegalArgumentException) {
            return ConfigValidationResult.Invalid("JSON содержит синтаксическую ошибку.")
        }

        return try {
            CoreConfigCheck.check(config)
            ConfigValidationResult.Valid
        } catch (error: Exception) {
            ConfigValidationResult.Invalid(formatCoreError(error.message))
        } catch (_: LinkageError) {
            ConfigValidationResult.Invalid(
                "Ядро недоступно для архитектуры этого устройства. Установите подходящую сборку.",
            )
        }
    }

    private fun formatCoreError(message: String?): String {
        val safe = message
            .orEmpty()
            .let(SecretRedactor::redactInline)
            .replace(NEW_LINES, " ")
            .trim()
            .take(MAX_ERROR_LENGTH)
        return if (safe.isBlank()) {
            "sing-box отклонил конфигурацию."
        } else {
            "sing-box: $safe"
        }
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 320
        val NEW_LINES = Regex("[\\r\\n\\t]+")
    }
}
