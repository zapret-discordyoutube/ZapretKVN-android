package io.github.zapretkvn.android.diagnostics

/** Evidence from a core/OS operation; classification never rewrites its message. */
data class RuntimeFailure(
    val component: String,
    val stage: String,
    val message: String,
    val code: String,
    val action: String,
    val sessionGeneration: Long = 0,
    val targetGeneration: Long = 0,
    val targetId: String = "",
    val level: Int = 2,
)

data class RecordedRuntimeFailure(
    val failure: RuntimeFailure,
    val firstSeenEpochMillis: Long,
    val lastSeenEpochMillis: Long,
    val occurrences: Long = 1,
)
