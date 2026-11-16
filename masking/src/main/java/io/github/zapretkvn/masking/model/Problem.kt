package io.github.zapretkvn.masking.model

/**
 * A validation finding for one field. [message] is shown to the user as is (Russian, like
 * the rest of the app UI). An [Severity.Error] value is never handed to a core: the app does
 * not start a core with a config it knows the core rejects or mishandles.
 */
data class Problem(val field: String, val message: String, val severity: Severity = Severity.Error) {
    enum class Severity { Error, Warning }

    companion object {
        fun error(field: String, message: String) = Problem(field, message, Severity.Error)

        fun warning(field: String, message: String) = Problem(field, message, Severity.Warning)
    }
}

val List<Problem>.errors: List<Problem> get() = filter { it.severity == Problem.Severity.Error }
