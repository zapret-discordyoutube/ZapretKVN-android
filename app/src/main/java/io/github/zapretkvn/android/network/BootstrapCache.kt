package io.github.zapretkvn.android.network

import android.util.AtomicFile
import io.github.zapretkvn.android.config.JsonConfig
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class BootstrapCacheEntry(
    val profileId: String,
    val hostname: String,
    val addresses: List<String>,
    val resolvedAtEpochMillis: Long,
    val connectedAtEpochMillis: Long,
) {
    fun ageAt(nowEpochMillis: Long): Long = (nowEpochMillis - resolvedAtEpochMillis).coerceAtLeast(0)
    fun isFreshAt(nowEpochMillis: Long): Boolean = ageAt(nowEpochMillis) <= FRESH_MILLIS
    fun isUsableAt(nowEpochMillis: Long): Boolean = ageAt(nowEpochMillis) <= EMERGENCY_MILLIS

    companion object {
        const val FRESH_MILLIS = 24L * 60 * 60 * 1_000
        const val EMERGENCY_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
}

/** Credential-free, bounded last-known-good cache in no-backup app storage. */
class BootstrapCache(root: File) {
    private val file = AtomicFile(File(root, "bootstrap-lkg.json"))
    private val lock = Mutex()

    suspend fun find(
        profileId: String,
        hostname: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): BootstrapCacheEntry? = lock.withLock {
        val stored = readEntries()
        val entries = stored.filter { it.isUsableAt(nowEpochMillis) }
        if (entries.size != stored.size) writeEntries(entries)
        entries.firstOrNull { it.profileId == profileId && it.hostname.equals(hostname, ignoreCase = true) }
    }

    suspend fun recordSuccess(
        profileId: String,
        hostname: String,
        addresses: List<InetAddress>,
        resolvedAtEpochMillis: Long = System.currentTimeMillis(),
        connectedAtEpochMillis: Long = System.currentTimeMillis(),
    ) = lock.withLock {
        val numeric = addresses.mapNotNull(InetAddress::getHostAddress).distinct().take(MAX_ADDRESSES)
        if (profileId.isBlank() || hostname.isBlank() || numeric.isEmpty()) return@withLock
        val entry = BootstrapCacheEntry(
            profileId = profileId.take(96),
            hostname = hostname.lowercase().take(253),
            addresses = numeric,
            resolvedAtEpochMillis = resolvedAtEpochMillis,
            connectedAtEpochMillis = connectedAtEpochMillis,
        )
        val next = (readEntries().filterNot { it.profileId == entry.profileId } + entry)
            .sortedByDescending(BootstrapCacheEntry::connectedAtEpochMillis)
            .take(MAX_ENTRIES)
        writeEntries(next)
    }

    suspend fun removeProfile(profileId: String) = lock.withLock {
        writeEntries(readEntries().filterNot { it.profileId == profileId })
    }

    suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private suspend fun readEntries(): List<BootstrapCacheEntry> = withContext(Dispatchers.IO) {
        if (!file.baseFile.isFile || file.baseFile.length() !in 1..MAX_FILE_BYTES) return@withContext emptyList()
        runCatching {
            val root = JsonConfig.parse(file.readFully().decodeToString()) as? JsonObject
                ?: return@runCatching emptyList()
            (root["entries"] as? JsonArray).orEmpty().mapNotNull(::decodeEntry)
        }.getOrDefault(emptyList())
    }

    private suspend fun writeEntries(entries: List<BootstrapCacheEntry>) = withContext(Dispatchers.IO) {
        file.baseFile.parentFile?.mkdirs()
        val payload = JsonConfig.format(
            buildJsonObject {
                put("version", 1)
                put("entries", buildJsonArray { entries.forEach { add(encodeEntry(it)) } })
            },
        ).encodeToByteArray()
        check(payload.size <= MAX_FILE_BYTES) { "Bootstrap cache превысил допустимый размер." }
        val stream = file.startWrite()
        try {
            stream.write(payload)
            stream.flush()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun encodeEntry(entry: BootstrapCacheEntry): JsonObject = buildJsonObject {
        put("profile_id", entry.profileId)
        put("hostname", entry.hostname)
        put("addresses", buildJsonArray { entry.addresses.forEach { add(JsonPrimitive(it)) } })
        put("resolved_at", entry.resolvedAtEpochMillis)
        put("connected_at", entry.connectedAtEpochMillis)
    }

    private fun decodeEntry(element: kotlinx.serialization.json.JsonElement): BootstrapCacheEntry? {
        val item = element as? JsonObject ?: return null
        val profileId = (item["profile_id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val hostname = (item["hostname"] as? JsonPrimitive)?.contentOrNull ?: return null
        val addresses = (item["addresses"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter(::looksNumeric)
            ?.distinct()
            ?.take(MAX_ADDRESSES)
            .orEmpty()
        val resolvedAt = (item["resolved_at"] as? JsonPrimitive)?.longOrNull ?: return null
        val connectedAt = (item["connected_at"] as? JsonPrimitive)?.longOrNull ?: return null
        if (profileId.isBlank() || hostname.isBlank() || addresses.isEmpty()) return null
        return BootstrapCacheEntry(profileId, hostname, addresses, resolvedAt, connectedAt)
    }

    private fun looksNumeric(value: String): Boolean = ':' in value || IPV4.matches(value)

    private companion object {
        const val MAX_ENTRIES = 32
        const val MAX_ADDRESSES = 8
        const val MAX_FILE_BYTES = 64L * 1024
        val IPV4 = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
    }
}
