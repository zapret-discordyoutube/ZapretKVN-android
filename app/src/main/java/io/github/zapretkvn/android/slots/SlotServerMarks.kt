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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
    /** How long a mark keeps a slot away from the server. */
    const val TTL_MILLIS = 60 * 60 * 1_000L
    const val MAX_ENTRIES = 256

    /** The marks that still count at [now]; the newest [MAX_ENTRIES] only. A mark dated in the future is dropped. */
    fun live(marks: Map<String, Long>, now: Long): Map<String, Long> =
        marks.filter { (_, at) -> now - at in 0..TTL_MILLIS }
            .entries.sortedByDescending { it.value }.take(MAX_ENTRIES)
            .associate { it.key to it.value }

    fun encode(marks: Map<String, Long>): String =
        buildJsonObject { marks.toSortedMap().forEach { (key, at) -> put(key, JsonPrimitive(at)) } }.toString()

    /** Anything unreadable is "no marks". */
    fun decode(text: String?): Map<String, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        val root: JsonObject = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        return root.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.let { key to it } }.toMap()
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
    private var marks: Map<String, Long> = emptyMap()
    private val writer = Mutex()

    init {
        scope.launch(ioDispatcher) {
            val stored = SlotMarkRules.decode(runCatching { if (file.isFile) file.readText() else null }.getOrNull())
            synchronized(lock) { marks = SlotMarkRules.live(stored + marks, clock()) }
        }
    }

    /** [key] did not work for a slot just now. */
    fun mark(key: String) {
        val now = clock()
        synchronized(lock) { marks = SlotMarkRules.live(marks + (key to now), now) }
        scope.launch(ioDispatcher) {
            writer.withLock {
                val snapshot = synchronized(lock) { marks }
                runCatching { writeAtomically(SlotMarkRules.encode(snapshot)) }
            }
        }
    }

    /** Marks that still count, key to the time of the mark. */
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
