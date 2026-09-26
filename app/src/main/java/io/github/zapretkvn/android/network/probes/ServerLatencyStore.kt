package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.OutboundDescription
import io.github.zapretkvn.android.engines.failover.LatencyHint
import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencySample
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Последний результат одной пробы (Relay HTTPS или ICMP). */
data class PersistedProbeResult(
    /** null — проба не удалась, см. [failure]. */
    val millis: Int?,
    val failure: LatencyFailure?,
    val measuredAtEpochMillis: Long,
    /** Последний удачный замер до неудачи — чтобы показать «Нет ответа · было 42 мс». */
    val previousMillis: Int? = null,
    val previousAtEpochMillis: Long? = null,
) {
    val failed: Boolean get() = millis == null

    fun toState(): LatencyProbeState {
        val previous = if (previousMillis != null && previousAtEpochMillis != null) {
            LatencySample(previousMillis, previousAtEpochMillis, networkIdentity = null)
        } else {
            null
        }
        return if (millis != null) {
            LatencyProbeState.Success(LatencySample(millis, measuredAtEpochMillis, networkIdentity = null))
        } else {
            LatencyProbeState.Failed(
                reason = failure ?: LatencyFailure.Failed,
                previous = previous,
                failedAtEpochMillis = measuredAtEpochMillis,
            )
        }
    }
}

/**
 * Пинг сервера, переживающий перезапуск приложения.
 *
 * [fingerprint] — хэш типа и адреса сервера (сам адрес не хранится): если
 * подписка сохранила тег, но сервер за ним поменялся, старый пинг выбрасывается.
 */
data class PersistedServerLatency(
    val fingerprint: String?,
    val relay: PersistedProbeResult? = null,
    val icmp: PersistedProbeResult? = null,
) {
    /** Для ранжирования кандидатов «умной проверки»: Relay HTTPS идёт через сам сервер. */
    internal fun hint(): LatencyHint? {
        val primary = relay ?: icmp ?: return null
        return LatencyHint(millis = primary.millis, failed = primary.failed)
    }

    /** Что показать в списке серверов без подключения: Relay, иначе ICMP. */
    fun displayState(): LatencyProbeState? = (relay ?: icmp)?.toState()
}

/** profileId → tag → последний пинг. */
typealias ServerLatencyEntries = Map<String, Map<String, PersistedServerLatency>>

object ServerLatencyFingerprint {
    fun of(type: String?, endpoint: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${type.orEmpty().lowercase()}|${endpoint.orEmpty()}".toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun of(description: OutboundDescription): String = of(description.type, description.endpoint)

    fun of(descriptions: Map<String, OutboundDescription>): Map<String, String> =
        descriptions.mapValues { (_, description) -> of(description) }
}

/** Чистые преобразования записей; хранилище только применяет их к своему состоянию. */
internal object ServerLatencyReducer {
    const val MAX_ENTRIES_PER_PROFILE = 512

    fun record(
        entries: ServerLatencyEntries,
        profileId: String,
        fingerprints: Map<String, String>,
        relay: Map<String, LatencyProbeState>,
        icmp: Map<String, LatencyProbeState>,
        nowEpochMillis: Long,
    ): ServerLatencyEntries {
        if (profileId.isBlank()) return entries
        val profile = entries[profileId].orEmpty().toMutableMap()
        var changed = false
        (relay.keys + icmp.keys).forEach { tag ->
            val relayResult = relay[tag]?.toPersisted(nowEpochMillis)
            val icmpResult = icmp[tag]?.toPersisted(nowEpochMillis)
            if (relayResult == null && icmpResult == null) return@forEach
            val fingerprint = fingerprints[tag]
            val previous = profile[tag]?.takeIf {
                it.fingerprint == null || fingerprint == null || it.fingerprint == fingerprint
            }
            profile[tag] = PersistedServerLatency(
                fingerprint = fingerprint ?: previous?.fingerprint,
                relay = relayResult?.carryPrevious(previous?.relay) ?: previous?.relay,
                icmp = icmpResult?.carryPrevious(previous?.icmp) ?: previous?.icmp,
            )
            changed = true
        }
        if (!changed) return entries
        return entries + (profileId to profile.bounded())
    }

    /**
     * Оставляет только существующие профили и серверы. Запись с другим
     * отпечатком адреса удаляется, запись без отпечатка его получает.
     */
    fun retain(
        entries: ServerLatencyEntries,
        servers: Map<String, Map<String, String>>,
    ): ServerLatencyEntries = entries.mapNotNull { (profileId, profile) ->
        val known = servers[profileId] ?: return@mapNotNull null
        val kept = profile.mapNotNull { (tag, entry) ->
            val fingerprint = known[tag] ?: return@mapNotNull null
            when (entry.fingerprint) {
                null -> tag to entry.copy(fingerprint = fingerprint)
                fingerprint -> tag to entry
                else -> null
            }
        }.toMap()
        kept.takeIf(Map<String, PersistedServerLatency>::isNotEmpty)?.let { profileId to it }
    }.toMap()

    /** Подставляет сохранённый пинг туда, где ядро ещё ничего не мерило. */
    fun hydrate(
        groups: List<RuntimeSelectorGroup>,
        persisted: Map<String, PersistedServerLatency>,
        fingerprints: Map<String, String>,
    ): List<RuntimeSelectorGroup> {
        if (persisted.isEmpty()) return groups
        return groups.map { group ->
            group.copy(
                items = group.items.map { item ->
                    val current = fingerprints[item.tag]
                    val entry = persisted[item.tag]
                        ?.takeIf { it.fingerprint == null || current == null || it.fingerprint == current }
                        ?: return@map item
                    item.copy(
                        relay = if (item.relay == LatencyProbeState.NotTested) {
                            entry.relay?.toState() ?: item.relay
                        } else {
                            item.relay
                        },
                        icmp = if (item.icmp == LatencyProbeState.NotTested) {
                            entry.icmp?.toState() ?: item.icmp
                        } else {
                            item.icmp
                        },
                    )
                },
            )
        }
    }

    private fun LatencyProbeState.toPersisted(nowEpochMillis: Long): PersistedProbeResult? = when (this) {
        is LatencyProbeState.Success -> PersistedProbeResult(
            millis = sample.millis,
            failure = null,
            measuredAtEpochMillis = sample.measuredAtEpochMillis,
        )
        is LatencyProbeState.Failed -> PersistedProbeResult(
            millis = null,
            failure = reason,
            measuredAtEpochMillis = failedAtEpochMillis ?: nowEpochMillis,
            previousMillis = previous?.millis,
            previousAtEpochMillis = previous?.measuredAtEpochMillis,
        )
        else -> null
    }

    private fun PersistedProbeResult.carryPrevious(old: PersistedProbeResult?): PersistedProbeResult {
        if (!failed || previousMillis != null || old == null) return this
        return if (old.millis != null) {
            copy(previousMillis = old.millis, previousAtEpochMillis = old.measuredAtEpochMillis)
        } else {
            copy(previousMillis = old.previousMillis, previousAtEpochMillis = old.previousAtEpochMillis)
        }
    }

    private fun Map<String, PersistedServerLatency>.bounded(): Map<String, PersistedServerLatency> {
        if (size <= MAX_ENTRIES_PER_PROFILE) return this
        return entries
            .sortedByDescending { entry ->
                maxOf(
                    entry.value.relay?.measuredAtEpochMillis ?: 0L,
                    entry.value.icmp?.measuredAtEpochMillis ?: 0L,
                )
            }
            .take(MAX_ENTRIES_PER_PROFILE)
            .associate { it.key to it.value }
    }
}

internal object ServerLatencyCodec {
    private const val VERSION = 1

    fun encode(entries: ServerLatencyEntries): String = JsonConfig.compact.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", VERSION)
            put(
                "profiles",
                JsonObject(
                    entries.mapValues { (_, profile) ->
                        JsonObject(profile.mapValues { (_, entry) -> entry.toJson() })
                    },
                ),
            )
        },
    )

    fun decode(text: String): ServerLatencyEntries = runCatching {
        val root = JsonConfig.parse(text) as? JsonObject ?: return emptyMap()
        if ((root["v"] as? JsonPrimitive)?.intOrNull != VERSION) return emptyMap()
        val profiles = root["profiles"] as? JsonObject ?: return emptyMap()
        profiles.mapNotNull { (profileId, value) ->
            val servers = (value as? JsonObject)?.mapNotNull { (tag, entry) ->
                (entry as? JsonObject)?.toEntry()?.let { tag to it }
            }?.toMap().orEmpty()
            servers.takeIf { it.isNotEmpty() }?.let { profileId to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun PersistedServerLatency.toJson(): JsonObject = buildJsonObject {
        fingerprint?.let { put("fp", it) }
        relay?.let { put("relay", it.toJson()) }
        icmp?.let { put("icmp", it.toJson()) }
    }

    private fun PersistedProbeResult.toJson(): JsonObject = buildJsonObject {
        millis?.let { put("ms", it) }
        failure?.let { put("fail", it.name) }
        put("at", measuredAtEpochMillis)
        previousMillis?.let { put("pms", it) }
        previousAtEpochMillis?.let { put("pat", it) }
    }

    private fun JsonObject.toEntry(): PersistedServerLatency? {
        val relay = (this["relay"] as? JsonObject)?.toResult()
        val icmp = (this["icmp"] as? JsonObject)?.toResult()
        if (relay == null && icmp == null) return null
        return PersistedServerLatency(
            fingerprint = (this["fp"] as? JsonPrimitive)?.contentOrNull,
            relay = relay,
            icmp = icmp,
        )
    }

    private fun JsonObject.toResult(): PersistedProbeResult? {
        val at = (this["at"] as? JsonPrimitive)?.longOrNull ?: return null
        val millis = (this["ms"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        val failure = (this["fail"] as? JsonPrimitive)?.contentOrNull
            ?.let { name -> LatencyFailure.entries.firstOrNull { it.name == name } }
        if (millis == null && failure == null) return null
        return PersistedProbeResult(
            millis = millis,
            failure = if (millis == null) failure else null,
            measuredAtEpochMillis = at,
            previousMillis = (this["pms"] as? JsonPrimitive)?.intOrNull,
            previousAtEpochMillis = (this["pat"] as? JsonPrimitive)?.longOrNull,
        )
    }
}

/**
 * Хранилище последних пингов серверов. Состояние — в памяти; запись в файл
 * идёт в фоне одним писателем: StateFlow версии конфлирует изменения, поэтому
 * пачка результатов, пришедшая во время записи, сохраняется одной следующей
 * записью. Таймеров нет.
 */
class ServerLatencyStore(
    private val file: File,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val mutableEntries = MutableStateFlow<ServerLatencyEntries>(emptyMap())
    private val version = MutableStateFlow(0L)
    private val writtenVersion = MutableStateFlow(-1L)
    private val loaded = CompletableDeferred<Unit>()

    val entries: StateFlow<ServerLatencyEntries> = mutableEntries.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            val stored = runCatching { if (file.isFile) file.readText() else null }
                .getOrNull()
                ?.let(ServerLatencyCodec::decode)
                .orEmpty()
            val changedInMemory = synchronized(lock) {
                val current = mutableEntries.value
                // Результаты, записанные до окончания чтения, новее файла.
                val merged = stored.mapValues { (profileId, profile) ->
                    profile + current[profileId].orEmpty()
                } + current.filterKeys { it !in stored }
                mutableEntries.value = merged
                version.value += 1
                merged != stored
            }
            if (!changedInMemory) writtenVersion.value = version.value
            loaded.complete(Unit)
            version.collect { target ->
                if (target <= writtenVersion.value) return@collect
                val (snapshotVersion, snapshot) = synchronized(lock) {
                    version.value to mutableEntries.value
                }
                runCatching { writeAtomically(ServerLatencyCodec.encode(snapshot)) }
                writtenVersion.value = snapshotVersion
            }
        }
    }

    fun forProfile(profileId: String): Map<String, PersistedServerLatency> =
        mutableEntries.value[profileId].orEmpty()

    fun record(
        profileId: String,
        fingerprints: Map<String, String>,
        relay: Map<String, LatencyProbeState> = emptyMap(),
        icmp: Map<String, LatencyProbeState> = emptyMap(),
    ) {
        if (relay.isEmpty() && icmp.isEmpty()) return
        val now = clock()
        mutate { current -> ServerLatencyReducer.record(current, profileId, fingerprints, relay, icmp, now) }
    }

    /** Удаляет записи исчезнувших профилей и серверов; ждёт чтения файла. */
    suspend fun retain(servers: Map<String, Map<String, String>>) {
        loaded.await()
        mutate { current -> ServerLatencyReducer.retain(current, servers) }
    }

    /** Ждёт, пока текущее состояние окажется на диске. */
    suspend fun awaitPersisted() {
        loaded.await()
        val target = version.value
        writtenVersion.first { it >= target }
    }

    private fun mutate(transform: (ServerLatencyEntries) -> ServerLatencyEntries) {
        synchronized(lock) {
            val current = mutableEntries.value
            val next = transform(current)
            if (next == current) return
            mutableEntries.value = next
            version.value += 1
        }
    }

    private fun writeAtomically(text: String) {
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Нет каталога ${parent.path}.")
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Не удалось сохранить пинг серверов.")
        }
    }
}
