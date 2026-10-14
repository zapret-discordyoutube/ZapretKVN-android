package io.github.zapretkvn.android.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Reads one prior process exit on API 30+ without copying the potentially large ANR trace. */
object AppProcessExitReader {
    fun read(context: Context): AppProcessExitRecord? {
        if (Build.VERSION.SDK_INT < 30) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
                .firstOrNull { it.timestamp > 0 }
        }.getOrNull() ?: return null
        return AppProcessExitRecord(
            occurredAtEpochMillis = info.timestamp,
            reasonCode = info.reason,
            reason = reasonName(info.reason),
            status = info.status,
            importance = info.importance,
            pssKilobytes = info.pss.coerceAtLeast(0),
            rssKilobytes = info.rss.coerceAtLeast(0),
            description = info.description
                ?.let(DiagnosticReportRedactor::redact)
                ?.replace(NEW_LINES, " ")
                ?.trim()
                ?.take(MAX_DESCRIPTION_CHARS)
                ?.takeIf(String::isNotEmpty),
        )
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "java_crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown"
    }

    private const val MAX_EXIT_RECORDS = 5
    private const val MAX_DESCRIPTION_CHARS = 240
    private val NEW_LINES = Regex("[\\r\\n]+")
}
