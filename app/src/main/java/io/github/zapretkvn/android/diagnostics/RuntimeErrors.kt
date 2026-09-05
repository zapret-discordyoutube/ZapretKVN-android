package io.github.zapretkvn.android.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

/** Freeze the failure before closing sockets and CommandLog emits teardown callbacks. */
internal class RuntimeStartupFailure(
    cause: Throwable,
    val evidence: RuntimeFailure?,
) : RuntimeException(cause.message, cause)

/** Separate from the bounded traffic log: no error or unknown message is evicted. */
internal class RuntimeErrorJournal {
    private val records = linkedMapOf<RuntimeFailure, RecordedRuntimeFailure>()
    private val mutableEntries = MutableStateFlow<List<RecordedRuntimeFailure>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    @Synchronized
    fun record(failure: RuntimeFailure, now: Long = System.currentTimeMillis()) {
        val previous = records[failure]
        records[failure] = previous?.copy(
            lastSeenEpochMillis = now,
            occurrences = previous.occurrences + 1,
        ) ?: RecordedRuntimeFailure(failure, now, now)
        mutableEntries.value = records.values.toList()
    }

    fun forGeneration(generation: Long): List<RecordedRuntimeFailure> =
        entries.value.filter { it.failure.sessionGeneration == generation }
}

/** The same catalog is packaged in Windows; unknown errors stay visible. */
internal object RuntimeErrors {
    private data class Rule(val code: String, val action: String, val pattern: Regex)

    private val rules: List<Rule> = checkNotNull(
        RuntimeErrors::class.java.getResourceAsStream("/runtime-errors.json"),
    ) { "Missing runtime error catalog" }.bufferedReader().use { reader ->
        Json.parseToJsonElement(reader.readText()).jsonObject.getValue("rules").jsonArray.map {
            val rule = it.jsonObject
            Rule(
                rule.getValue("code").jsonPrimitive.content,
                rule.getValue("action").jsonPrimitive.content,
                Regex(rule.getValue("pattern").jsonPrimitive.content, RegexOption.IGNORE_CASE),
            )
        }
    }

    fun classify(message: String): String? = rules.firstOrNull { it.pattern.containsMatchIn(message) }?.code

    fun classifyForRecovery(message: String): String? = rules
        .firstOrNull { it.pattern.containsMatchIn(message) }
        ?.takeUnless { it.action == "record_only" }?.code

    fun describe(error: Throwable): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val messages = mutableListOf<String>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            val detail = current.message?.takeIf(String::isNotBlank) ?: current.javaClass.simpleName
            if (messages.lastOrNull() != detail) messages += detail
            current = current.cause
        }
        return SecretRedactor.redactInline(messages.joinToString("\nCaused by: "))
    }

    fun capture(
        component: String,
        stage: String,
        message: String,
        sessionGeneration: Long = 0,
        targetGeneration: Long = 0,
        targetId: String = "",
        level: Int = 2,
    ): RuntimeFailure {
        val rule = rules.firstOrNull { it.pattern.containsMatchIn(message) }
        return RuntimeFailure(
            component, stage, SecretRedactor.redactInline(message),
            rule?.code ?: "CORE_UNCLASSIFIED", rule?.action ?: "stop",
            sessionGeneration, targetGeneration, targetId, level,
        )
    }

    fun bestEvidence(records: List<RecordedRuntimeFailure>): RuntimeFailure? = records
        .filter { it.failure.action != "record_only" }
        .filter { it.failure.code != "CORE_UNCLASSIFIED" || it.failure.level <= 2 }
        .maxWithOrNull(compareBy<RecordedRuntimeFailure> {
            if (it.failure.code.startsWith("TARGET_TLS_") ||
                it.failure.code in setOf("TARGET_PIN_MISMATCH", "TARGET_AUTH_REJECTED", "TARGET_OBFS_REJECTED")
            ) 1 else 0
        }.thenBy { it.lastSeenEpochMillis })?.failure

    fun startupEvidence(error: Throwable, records: List<RecordedRuntimeFailure>): RuntimeFailure? {
        val captured = generateSequence(error) { it.cause }
            .filterIsInstance<RuntimeStartupFailure>().firstOrNull()
        // A captured null is meaningful: cleanup cannot supply the original cause.
        return if (captured != null) captured.evidence else bestEvidence(records)
    }

    fun commandLogFailure(generation: Long, message: String, expectedClose: Boolean): RuntimeFailure? {
        if (expectedClose) return null
        return capture("libbox-command-log", "observer", "CommandLog: $message", generation)
            .copy(code = "LOCAL_CONTROL_PLANE_UNAVAILABLE", action = "stop")
    }
}
