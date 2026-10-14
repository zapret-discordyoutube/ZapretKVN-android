package io.github.zapretkvn.android.diagnostics

data class AppProcessExitRecord(
    val occurredAtEpochMillis: Long,
    val reasonCode: Int,
    val reason: String,
    val status: Int,
    val importance: Int,
    val pssKilobytes: Long,
    val rssKilobytes: Long,
    val description: String?,
)
