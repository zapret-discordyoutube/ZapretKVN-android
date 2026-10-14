package io.github.zapretkvn.android.diagnostics

import android.os.Process
import android.util.AtomicFile
import io.github.zapretkvn.android.config.JsonConfig
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Stores only the newest uncaught app crash. It never writes runtime/core traffic logs. */
class AppCrashStore(root: File) {
    private val directory = File(root, DIRECTORY_NAME)
    private val file = AtomicFile(File(directory, FILE_NAME))
    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(thread.name, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    fun read(): AppCrashRecord? = runCatching {
        file.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
            decode(JsonConfig.parse(reader.readText()) as JsonObject)
        }
    }.getOrNull()

    fun clear() {
        file.delete()
        directory.delete()
    }

    internal fun record(threadName: String, throwable: Throwable) {
        if (!directory.exists() && !directory.mkdirs()) return
        val record = AppCrashRecord(
            occurredAtEpochMillis = System.currentTimeMillis(),
            threadName = sanitizeText(threadName, 80),
            exceptionType = safeToken(throwable.javaClass.simpleName, "Throwable"),
            message = throwable.message
                ?.let { sanitizeText(it, MAX_MESSAGE_CHARS) }
                ?.takeIf(String::isNotEmpty),
            causes = generateSequence(throwable.cause) { it.cause }
                .take(MAX_CAUSES)
                .map { safeToken(it.javaClass.simpleName, "Throwable") }
                .toList(),
            stack = throwable.stackTrace
                .asSequence()
                .take(MAX_STACK_FRAMES)
                .map { frame ->
                    AppCrashFrame(
                        className = safeToken(
                            frame.className.substringAfterLast('.'),
                            "UnknownClass",
                        ),
                        methodName = safeToken(frame.methodName, "unknownMethod"),
                        lineNumber = frame.lineNumber.coerceAtLeast(-1),
                    )
                }
                .toList(),
        )
        val output = file.startWrite()
        try {
            OutputStreamWriter(output, Charsets.UTF_8).apply {
                write(JsonConfig.format(encode(record)))
                flush()
            }
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun encode(record: AppCrashRecord): JsonObject = buildJsonObject {
        put("occurred_at_epoch_ms", record.occurredAtEpochMillis)
        put("thread", record.threadName)
        put("exception", record.exceptionType)
        record.message?.let { put("message", it) }
        put(
            "causes",
            buildJsonArray { record.causes.forEach { add(JsonPrimitive(it)) } },
        )
        put(
            "stack",
            buildJsonArray {
                record.stack.forEach { frame ->
                    add(
                        buildJsonObject {
                            put("class", frame.className)
                            put("method", frame.methodName)
                            put("line", frame.lineNumber)
                        },
                    )
                }
            },
        )
    }

    private fun decode(root: JsonObject): AppCrashRecord {
        val stack = (root["stack"] as? JsonArray).orEmpty().mapNotNull { element ->
            val frame = element as? JsonObject ?: return@mapNotNull null
            AppCrashFrame(
                className = frame.text("class") ?: return@mapNotNull null,
                methodName = frame.text("method") ?: return@mapNotNull null,
                lineNumber = frame["line"]?.jsonPrimitive?.intOrNull ?: -1,
            )
        }.take(MAX_STACK_FRAMES)
        return AppCrashRecord(
            occurredAtEpochMillis = root["occurred_at_epoch_ms"]
                ?.jsonPrimitive
                ?.longOrNull
                ?: error("Crash timestamp is missing."),
            threadName = root.text("thread") ?: "unknown",
            exceptionType = root.text("exception") ?: "Throwable",
            message = root.text("message"),
            causes = (root["causes"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .take(MAX_CAUSES),
            stack = stack,
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun sanitizeText(value: String, maxLength: Int): String =
        DiagnosticReportRedactor.redact(value)
            .replace(ANSI_ESCAPE, "")
            .replace(NEW_LINES, " ")
            .trim()
            .take(maxLength)

    private fun safeToken(value: String, fallback: String): String =
        value.takeIf(SAFE_TOKEN::matches)?.take(MAX_TOKEN_CHARS) ?: fallback

    companion object {
        const val DIRECTORY_NAME = "diagnostic-state"
        const val FILE_NAME = "last-crash.json"
        private const val MAX_MESSAGE_CHARS = 360
        private const val MAX_TOKEN_CHARS = 120
        private const val MAX_CAUSES = 4
        private const val MAX_STACK_FRAMES = 16
        private val SAFE_TOKEN = Regex("[A-Za-z0-9_$<>-]+")
        private val ANSI_ESCAPE = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|[@-_])")
        private val NEW_LINES = Regex("[\\r\\n]+")
    }
}
