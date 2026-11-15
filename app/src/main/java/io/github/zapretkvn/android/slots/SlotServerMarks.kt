package io.github.zapretkvn.android.slots

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Servers a slot had to leave because they did not work for it (the slot's own DNS timed out, connections failed, or
 * the server rejected the credentials): key is the stored form of the server (`ServerRef.encode()`), value is when.
 * It is NOT the «не отвечает» row of the Servers tab and the main selector never reads it: a server can be good for
 * ordinary traffic and still dead for a slot, because the slot's DNS goes through that server alone (field case
 * 2026-11-15: Clermany and Rusge failed the YouTube slot's DNS in three sessions in a row from the same phone, and
 * nothing remembered it, so every connect hopped through them again).
 */
internal object SlotMarkRules {
    /** How long the first mark keeps a slot away from the server; each repeat doubles it (see [ttl]). */
    const val TTL_MILLIS = 60 * 60 * 1_000L
    const val MAX_ENTRIES = 256

    /** The longest mark is 8 hours (four strikes): a server that keeps failing the slot is tried a few times a day. */
    const val MAX_STRIKES = 4

    /** A repeat counts as a repeat for this long after the last mark; later the server starts again from one hour. */
    const val MEMORY_MILLIS = 24 * 60 * 60 * 1_000L

    /** A mark: when it was set and how many times the server has failed the slot again after a mark ran out. */
    data class Mark(val at: Long, val strikes: Int = 1)

    fun ttl(strikes: Int): Long = TTL_MILLIS shl (strikes.coerceIn(1, MAX_STRIKES) - 1)

    /** Marks to keep at [now] (the strike memory, [MEMORY_MILLIS]); the newest [MAX_ENTRIES] only; a mark dated in the future is dropped. */
    fun remembered(marks: Map<String, Mark>, now: Long): Map<String, Mark> =
        marks.filter { (_, mark) -> now - mark.at in 0..MEMORY_MILLIS }
            .entries.sortedByDescending { it.value.at }.take(MAX_ENTRIES)
            .associate { it.key to it.value }

    /** The marks that still keep a slot away at [now]: key to the time of the mark. */
    fun live(marks: Map<String, Mark>, now: Long): Map<String, Long> =
        marks.filter { (_, mark) -> now - mark.at in 0..ttl(mark.strikes) }.mapValues { it.value.at }

    /**
     * [key] failed the slot at [now]. A failure while a mark still runs keeps the strike count (the slot had left that
     * server already); a failure after the mark ran out, within [MEMORY_MILLIS], is a repeat and doubles the next mark.
     */
    fun add(marks: Map<String, Mark>, key: String, now: Long): Map<String, Mark> {
        val previous = marks[key]
        val strikes = when {
            previous == null || now - previous.at > MEMORY_MILLIS -> 1
            now - previous.at <= ttl(previous.strikes) -> previous.strikes
            else -> minOf(previous.strikes + 1, MAX_STRIKES)
        }
        return remembered(marks + (key to Mark(now, strikes)), now)
    }

    /**
     * Undo of one [add]: [key] holds [set] (the mark that add made) and goes back to [before] (null: no mark). A later
     * mark of the same server is not touched: it was made by another failure and stays.
     */
    fun undo(marks: Map<String, Mark>, key: String, before: Mark?, set: Mark): Map<String, Mark> {
        if (marks[key] != set) return marks
        return if (before == null) marks - key else marks + (key to before)
    }

    /** One mark is a number (the time, one strike: files of 0.6.13-0.6.18) or `[time, strikes]`. */
    fun encode(marks: Map<String, Mark>): String =
        buildJsonObject {
            marks.toSortedMap().forEach { (key, mark) ->
                put(key, if (mark.strikes <= 1) JsonPrimitive(mark.at) else JsonArray(listOf(JsonPrimitive(mark.at), JsonPrimitive(mark.strikes))))
            }
        }.toString()

    /** Anything unreadable is "no marks". */
    fun decode(text: String?): Map<String, Mark> {
        if (text.isNullOrBlank()) return emptyMap()
        val root: JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        return root.mapNotNull { (key, value) ->
            val mark = when (value) {
                is JsonPrimitive -> value.longOrNull?.let { Mark(it) }
                is JsonArray -> {
                    val at = (value.getOrNull(0) as? JsonPrimitive)?.longOrNull
                    val strikes = (value.getOrNull(1) as? JsonPrimitive)?.intOrNull
                    if (at != null && strikes != null) Mark(at, strikes.coerceIn(1, MAX_STRIKES)) else null
                }
                else -> null
            }
            mark?.let { key to it }
        }.toMap()
    }
}

/**
 * The marks in memory with a file behind them. Reads are synchronous (the runtime actor asks while it decides), the
 * file is written in the background by one writer at a time; a change that arrives while a write runs is picked up by
 * the next write because every write takes the latest state. No timers: marks expire when they are read.
 */
internal class SlotServerMarks(
    private val file: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var marks: Map<String, SlotMarkRules.Mark> = emptyMap()
    private val writer = Mutex()

    /** Per key, what the last [mark] replaced and what it set: [forgive] puts it back. */
    private val lastChange = HashMap<String, Pair<SlotMarkRules.Mark?, SlotMarkRules.Mark>>()

    init {
        scope.launch(ioDispatcher) {
            val stored = SlotMarkRules.decode(runCatching { if (file.isFile) file.readText() else null }.getOrNull())
            synchronized(lock) { marks = SlotMarkRules.remembered(stored + marks, clock()) }
        }
    }

    /** [key] did not work for a slot just now. */
    fun mark(key: String) {
        val now = clock()
        synchronized(lock) {
            val before = marks[key]
            marks = SlotMarkRules.add(marks, key, now)
            marks[key]?.let { lastChange[key] = before to it }
        }
        save()
    }

    /**
     * The last [mark] of [key] was a mistake (the YouTube video starved on every server, so this one was not the cause):
     * the mark and its strike go back to what they were before it. Nothing happens when the server was marked again since.
     */
    fun forgive(key: String) {
        val changed = synchronized(lock) {
            val (before, set) = lastChange.remove(key) ?: return
            val next = SlotMarkRules.undo(marks, key, before, set)
            (next !== marks).also { marks = next }
        }
        if (changed) save()
    }

    private fun save() {
        scope.launch(ioDispatcher) {
            writer.withLock {
                val snapshot = synchronized(lock) { marks }
                runCatching { writeAtomically(SlotMarkRules.encode(snapshot)) }
            }
        }
    }

    /** Marks that still keep a slot away (the first for an hour, repeats longer), key to the time of the mark. */
    fun active(): Map<String, Long> = synchronized(lock) { SlotMarkRules.live(marks, clock()) }

    private fun writeAtomically(text: String) {
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw IOException("No directory ${parent.path}.")
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Could not save the slot server marks.")
        }
    }
}
