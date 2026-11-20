package io.github.zapretkvn.clientreports

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * An app error that is not about any server (vpnbot «app_error live» 2026-11-20, owner's decision): a code from a
 * closed set of steps, how often it happened, and for a crash only the frames — never a message, a path, an address or
 * a URL. vpnbot groups crashes itself (code + frames without lines); the app sends no hash. Same as Windows
 * `client_reports/app_errors.py`.
 */
data class AppError(
    val step: String,
    val code: String,
    val frames: List<String> = emptyList(),
    val subscription: SubscriptionFact? = null,
)

/** Only with step `subscription`: whether it is our service's subscription and which way it was fetched. */
data class SubscriptionFact(val ours: Boolean, val route: String)

/** One pending app error: [count] repeats since the last send, the first at [occurredAtMillis]. */
data class AppErrorRecord(
    val reportId: String,
    val error: AppError,
    val count: Int,
    val occurredAtMillis: Long,
    val appVersion: String,
    val networkToken: String = "",
    val details: AttemptDetails = AttemptDetails(),
)

object AppErrors {
    const val KIND = "app_error"
    const val SCHEMA = 1
    const val STEP_START = "start"
    const val STEP_SUBSCRIPTION = "subscription"
    const val STEP_UPDATE = "update"
    const val STEP_PERMISSION = "permission"
    const val STEP_CRASH = "crash"
    const val STEP_STORAGE = "storage"
    val STEPS = setOf(STEP_START, STEP_SUBSCRIPTION, STEP_UPDATE, STEP_PERMISSION, STEP_CRASH, STEP_STORAGE)
    val ROUTES = setOf("direct", "proxy", "tunnel")
    const val MAX_COUNT = 100_000
    const val MAX_FRAMES = 16

    /** One send per code and app version a day: a broken install does not flood (the rest is counted). */
    const val SEND_EVERY_MILLIS = 24L * 60 * 60 * 1000

    private val CODE = Regex("^[a-z]{1,16}:[A-Za-z0-9_.-]{1,64}$")
    private val FRAME = Regex("^[A-Za-z0-9_$.<>-]{1,72}:[0-9]{1,6}$")
    private val CODE_VALUE_BAD = Regex("[^A-Za-z0-9_.-]")
    private val FRAME_NAME_BAD = Regex("[^A-Za-z0-9_$.<>-]")

    /** `prefix:value` in the contract's shape; the value's other characters become `_`, null when nothing is left. */
    fun code(prefix: String, value: String): String? {
        val cleaned = value.trim().replace(CODE_VALUE_BAD, "_").take(64)
        return "$prefix:$cleaned".takeIf { CODE.matches(it) }
    }

    /** `name:line`: the name keeps only allowed characters (the tail when too long), an unknown line is 0. */
    fun frame(name: String, line: Int): String? {
        val cleaned = name.replace(FRAME_NAME_BAD, "_").takeLast(72).trimStart('.')
        if (cleaned.isEmpty()) return null
        return "$cleaned:${line.coerceIn(0, 999_999)}".takeIf { FRAME.matches(it) }
    }

    /** Java frames, innermost first: `SimpleClass.method:line` (no package, no file). */
    fun frames(trace: Array<StackTraceElement>): List<String> =
        trace.asSequence().mapNotNull { frame(it.className.substringAfterLast('.') + "." + it.methodName, it.lineNumber) }
            .take(MAX_FRAMES).toList()

    fun isValid(error: AppError): Boolean =
        error.step in STEPS && CODE.matches(error.code) && error.frames.size <= MAX_FRAMES &&
            error.frames.all(FRAME::matches) &&
            (error.subscription == null || error.step == STEP_SUBSCRIPTION && error.subscription.route in ROUTES)

    /** The record of the batch body: exactly the contract's fields, `occurred_at` in the ingest's time. */
    fun json(installId: String, appName: String, platform: String, record: AppErrorRecord, sentVia: String, clockOffsetMillis: Long): JsonObject =
        buildJsonObject {
            put("kind", KIND)
            put("schema", SCHEMA)
            put("install_id", installId)
            put("report_id", record.reportId)
            put("app", buildJsonObject {
                put("name", appName)
                put("version", record.appVersion)
                put("platform", platform)
            })
            put("step", record.error.step)
            put("code", record.error.code)
            put("count", record.count.coerceIn(1, MAX_COUNT))
            put("occurred_at", ClientReportJson.isoUtc(record.occurredAtMillis + clockOffsetMillis))
            put("sent_via", sentVia)
            if (record.networkToken.isNotEmpty()) put("network_token", record.networkToken)
            if (record.error.frames.isNotEmpty()) put("frames", buildJsonArray { record.error.frames.forEach { add(JsonPrimitive(it)) } })
            record.error.subscription?.let { fact ->
                put("subscription", buildJsonObject {
                    put("ours", fact.ours)
                    put("route", fact.route)
                })
            }
            ClientReportJson.commonDetails(record.details).forEach { (key, value) -> put(key, value) }
        }

    internal fun key(error: AppError): String =
        listOf(error.step, error.code, error.frames.joinToString(","), error.subscription?.let { "${it.ours}/${it.route}" }.orEmpty())
            .joinToString("|")
}

/**
 * Pending app errors on disk (one small JSON file): the same error while unsent adds to its count; after a send the
 * next one of the same code and version waits [AppErrors.SEND_EVERY_MILLIS]. Kept at most [MAX_PENDING]; older than
 * the ingest's 7 days they are dropped. Survives a crash: the uncaught handler writes nothing here, the next start does.
 */
class AppErrorStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun add(error: AppError, appVersion: String, networkToken: String, details: AttemptDetails, atMillis: Long = now()): AppErrorRecord? {
        if (!AppErrors.isValid(error)) return null
        val state = read()
        val key = AppErrors.key(error)
        val index = state.pending.indexOfFirst { AppErrors.key(it.error) == key && it.appVersion == appVersion }
        val record = if (index >= 0) {
            val old = state.pending[index]
            old.copy(count = (old.count + 1).coerceAtMost(AppErrors.MAX_COUNT), occurredAtMillis = minOf(old.occurredAtMillis, atMillis))
                .also { state.pending[index] = it }
        } else {
            AppErrorRecord(ClientReportContract.newReportId(), error, 1, atMillis, appVersion, networkToken, details.common())
                .also { state.pending += it }
        }
        while (state.pending.size > MAX_PENDING) state.pending.removeAt(0)
        write(state)
        return record
    }

    /** The errors that may go now, oldest first. */
    @Synchronized
    fun due(limit: Int): List<AppErrorRecord> {
        val state = read()
        val at = now()
        val fresh = state.pending.filter { at - it.occurredAtMillis < ClientReportContract.MAX_AGE_MS - FRESHNESS_MARGIN_MS }
        if (fresh.size != state.pending.size) write(state.copy(pending = fresh.toMutableList()))
        return fresh.filter { at - (state.sentAt[sendKey(it)] ?: 0L) >= AppErrors.SEND_EVERY_MILLIS }.take(limit)
    }

    /**
     * The ingest took (or refused alone) these: they leave the store and their code waits a day. Repeats counted while
     * one was on its way stay as a new pending error with a new report_id.
     */
    @Synchronized
    fun sent(records: List<AppErrorRecord>) {
        if (records.isEmpty()) return
        val state = read()
        val at = now()
        records.forEach { sent ->
            val index = state.pending.indexOfFirst { it.reportId == sent.reportId }
            if (index >= 0) {
                val current = state.pending[index]
                if (current.count > sent.count) {
                    state.pending[index] = current.copy(reportId = ClientReportContract.newReportId(), count = current.count - sent.count, occurredAtMillis = at)
                } else {
                    state.pending.removeAt(index)
                }
            }
            state.sentAt[sendKey(sent)] = at
        }
        state.sentAt.entries.removeAll { at - it.value > AppErrors.SEND_EVERY_MILLIS * 2 }
        write(state)
    }

    /** When a crash or exit of a previous run was last turned into an app error (they are read once). */
    @Synchronized
    fun seenUntil(): Long = read().seenUntil

    @Synchronized
    fun see(untilMillis: Long) {
        val state = read()
        if (untilMillis > state.seenUntil) write(state.copy(seenUntil = untilMillis))
    }

    @Synchronized
    fun size(): Int = read().pending.size

    private fun sendKey(record: AppErrorRecord) = record.error.code + "|" + record.appVersion

    private data class State(
        val pending: MutableList<AppErrorRecord> = mutableListOf(),
        val sentAt: MutableMap<String, Long> = mutableMapOf(),
        val seenUntil: Long = 0,
    )

    private fun read(): State = runCatching {
        if (!file.exists()) return State()
        val root = json.parseToJsonElement(file.readText()) as JsonObject
        val pending = (root["pending"] as? JsonArray).orEmpty().mapNotNull { parse(it as? JsonObject ?: return@mapNotNull null) }
        val sentAt = (root["sent"] as? JsonObject).orEmpty().mapNotNull { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.let { key to it } }
        State(pending.toMutableList(), sentAt.toMap().toMutableMap(), (root["seen"] as? JsonPrimitive)?.longOrNull ?: 0)
    }.getOrDefault(State())

    private fun write(state: State) {
        runCatching {
            file.parentFile?.mkdirs()
            val text = buildJsonObject {
                put("v", 1)
                put("pending", buildJsonArray { state.pending.forEach { add(encode(it)) } })
                put("sent", buildJsonObject { state.sentAt.forEach { (key, value) -> put(key, value) } })
                put("seen", state.seenUntil)
            }.toString()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private fun encode(record: AppErrorRecord): JsonObject = buildJsonObject {
        put("id", record.reportId)
        put("step", record.error.step)
        put("code", record.error.code)
        put("frames", buildJsonArray { record.error.frames.forEach { add(JsonPrimitive(it)) } })
        record.error.subscription?.let {
            put("ours", it.ours)
            put("route", it.route)
        }
        put("count", record.count)
        put("t", record.occurredAtMillis)
        put("ver", record.appVersion)
        put("n", record.networkToken)
        put("x", ClientReportJson.commonDetails(record.details))
    }

    private fun parse(obj: JsonObject): AppErrorRecord? = runCatching {
        fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val route = text("route")
        val error = AppError(
            step = text("step"),
            code = text("code"),
            frames = (obj["frames"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            subscription = (obj["ours"] as? JsonPrimitive)?.booleanOrNull?.let { SubscriptionFact(it, route) },
        )
        if (!AppErrors.isValid(error) || !ClientReportContract.isReportId(text("id"))) return null
        AppErrorRecord(
            reportId = text("id"),
            error = error,
            count = (obj["count"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, AppErrors.MAX_COUNT) ?: return null,
            occurredAtMillis = (obj["t"] as? JsonPrimitive)?.longOrNull ?: return null,
            appVersion = text("ver").ifEmpty { return null },
            networkToken = text("n").takeIf(ClientReportContract::isNetworkToken).orEmpty(),
            details = (obj["x"] as? JsonObject)?.let(ClientReportJson::parseCommonDetails) ?: AttemptDetails(),
        )
    }.getOrNull()

    companion object {
        const val MAX_PENDING = 32
        private const val FRESHNESS_MARGIN_MS = 60L * 60 * 1000
    }
}

/** Only what app errors carry: network type, settings, versions (never an address or a port). */
fun AttemptDetails.common(): AttemptDetails = AttemptDetails(networkType = networkType, settings = settings, versions = versions).cleaned()
