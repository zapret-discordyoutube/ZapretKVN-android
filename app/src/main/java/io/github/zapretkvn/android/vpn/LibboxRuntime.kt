package io.github.zapretkvn.android.vpn

import android.content.Context
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.util.Locale

class LibboxRuntime(private val context: Context) {
    @Volatile
    private var initialized = false

    @Volatile
    private var initializationError: String? = null

    @Synchronized
    fun initialize(): Result<Unit> {
        if (initialized) return Result.success(Unit)
        initializationError?.let { return Result.failure(IllegalStateException(it)) }
        return try {
            val working = File(context.filesDir, "core").apply { mkdirs() }
            val temporary = File(context.cacheDir, "core").apply { mkdirs() }
            Libbox.touch()
            Libbox.setup(
                SetupOptions().apply {
                    basePath = context.filesDir.absolutePath
                    workingPath = working.absolutePath
                    tempPath = temporary.absolutePath
                    fixAndroidStack = true
                    commandServerListenPort = 0
                    commandServerSecret = ""
                    logMaxLines = 256
                    debug = BuildConfig.DEBUG
                },
            )
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
            Libbox.setMemoryLimit(false)
            initialized = true
            Result.success(Unit)
        } catch (error: Throwable) {
            val detail = error.message
                ?.lineSequence()
                ?.firstOrNull()
                ?.let(SecretRedactor::redactInline)
                ?.take(240)
            val errorType = error.javaClass.simpleName
                .takeIf { it.matches(SAFE_ERROR_TYPE) }
                ?: "LibboxError"
            val safe = listOfNotNull(errorType, detail).joinToString(": ")
                .ifBlank { "Не удалось инициализировать libbox." }
            initializationError = safe
            // Do not retain the original throwable as a cause. Android's
            // uncaught-exception/crash machinery can print a complete cause
            // chain, including a libbox message with URI credentials.
            Result.failure(IllegalStateException(safe))
        }
    }

    private companion object {
        val SAFE_ERROR_TYPE = Regex("[A-Za-z0-9_$<>-]{1,80}")
    }
}
