package io.github.zapretkvn.android.diagnostics

data class AppCrashFrame(
    val className: String,
    val methodName: String,
    val lineNumber: Int,
)

data class AppCrashRecord(
    val occurredAtEpochMillis: Long,
    val threadName: String,
    val exceptionType: String,
    val message: String?,
    val causes: List<String>,
    val stack: List<AppCrashFrame>,
)
