package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigValidationResult
import io.github.zapretkvn.android.config.ConfigValidator
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ProfileBatchCreate(
    val id: String,
    val name: String,
    val rawJson: String,
    val source: ProfileSource,
)

data class ProfileBatchResult(val created: List<ProfileMetadata>)

class ProfileStore(
    private val root: File,
    private val validator: ConfigValidator,
    private val writer: AtomicProfileWriter = AndroidAtomicProfileWriter(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    private val mutex = Mutex()
    private val mutableProfiles = MutableStateFlow<List<ProfileMetadata>>(emptyList())

    val profiles: StateFlow<List<ProfileMetadata>> = mutableProfiles.asStateFlow()

    suspend fun initialize(): List<ProfileMetadata> = io {
        mutex.withLock {
            ensureRoot()
            val index = readIndexOrCreate()
            recoverAndClean(index)
            mutableProfiles.value = index
            index
        }
    }

    suspend fun create(name: String, rawJson: String, source: ProfileSource): ProfileMetadata = io {
        mutex.withLock {
            requireValid(rawJson)
            val normalizedName = normalizeName(name)
            val index = readIndexOrCreate()
            val id = generateUniqueId(index)
            val now = clock()
            val metadata = ProfileMetadata(id, normalizedName, source, now, now)
            val profileFile = profileFile(id)
            try {
                writer.writeProfile(profileFile, rawJson.toByteArray(Charsets.UTF_8))
                writeIndex(index + metadata)
            } catch (error: Throwable) {
                deleteProfileFamily(profileFile)
                throw storeError("Не удалось сохранить профиль.", error)
            }
            mutableProfiles.value = index + metadata
            metadata
        }
    }

    suspend fun read(id: String): StoredProfile = io {
        mutex.withLock {
            val metadata = readIndexOrCreate().firstOrNull { it.id == id }
                ?: throw ProfileStoreException("Профиль не найден.")
            val file = profileFile(id)
            recoverMissingProfile(file)
            val json = try {
                file.readText(Charsets.UTF_8)
            } catch (error: IOException) {
                throw ProfileStoreException("Не удалось прочитать профиль.", error)
            }
            StoredProfile(metadata, json, backupFile(file).isFile)
        }
    }

    suspend fun update(id: String, rawJson: String) = io {
        mutex.withLock {
            requireValid(rawJson)
            val index = readIndexOrCreate()
            val position = index.indexOfFirst { it.id == id }
            if (position < 0) throw ProfileStoreException("Профиль не найден.")
            val file = profileFile(id)
            recoverMissingProfile(file)
            if (!file.isFile) throw ProfileStoreException("Файл профиля отсутствует.")
            val updated = index.toMutableList().apply {
                this[position] = this[position].copy(updatedAtEpochMillis = clock())
            }
            try {
                writer.writeProfile(file, rawJson.toByteArray(Charsets.UTF_8))
            } catch (error: Throwable) {
                throw storeError("Не удалось обновить профиль; сохранена прежняя версия.", error)
            }
            try {
                writeIndex(updated)
            } catch (error: Throwable) {
                writer.rollbackProfile(file)
                throw error
            }
            mutableProfiles.value = updated
        }
    }

    /**
     * Applies a subscription reconciliation as one index commit. Profile files written before
     * that commit are rolled back if any validation or write fails.
     */
    suspend fun applyBatch(
        updates: Map<String, String>,
        creates: List<ProfileBatchCreate>,
        deletes: Set<String>,
    ): ProfileBatchResult = io {
        mutex.withLock {
            require(updates.keys.intersect(deletes).isEmpty()) { "Профиль нельзя обновить и удалить." }
            require(creates.map { it.id }.distinct().size == creates.size) { "Повторяющийся id профиля." }
            updates.values.forEach(::requireValid)
            creates.forEach { requireValid(it.rawJson) }

            val index = readIndexOrCreate()
            val indexedIds = index.map(ProfileMetadata::id).toSet()
            if (!indexedIds.containsAll(updates.keys + deletes)) {
                throw ProfileStoreException("Один из профилей группы не найден.")
            }
            if (creates.any { !it.id.matches(ID_PATTERN) || it.id in indexedIds }) {
                throw ProfileStoreException("Некорректный или занятый id нового профиля.")
            }

            val now = clock()
            val createdMetadata = creates.map {
                ProfileMetadata(it.id, normalizeName(it.name), it.source, now, now)
            }
            val finalIndex = index
                .filterNot { it.id in deletes }
                .map { metadata ->
                    if (metadata.id in updates) metadata.copy(updatedAtEpochMillis = now) else metadata
                } + createdMetadata

            val writtenUpdates = mutableListOf<File>()
            val writtenCreates = mutableListOf<File>()
            try {
                updates.forEach { (id, rawJson) ->
                    val file = profileFile(id)
                    recoverMissingProfile(file)
                    if (!file.isFile) throw ProfileStoreException("Файл профиля отсутствует.")
                    writer.writeProfile(file, rawJson.toByteArray(Charsets.UTF_8))
                    writtenUpdates += file
                }
                creates.forEach { create ->
                    val file = profileFile(create.id)
                    writer.writeProfile(file, create.rawJson.toByteArray(Charsets.UTF_8))
                    writtenCreates += file
                }
                writeIndex(finalIndex)
            } catch (error: Throwable) {
                writtenUpdates.asReversed().forEach(writer::rollbackProfile)
                writtenCreates.forEach(::deleteProfileFamily)
                throw storeError("Не удалось синхронизировать профили подписки.", error)
            }
            deletes.forEach { deleteProfileFamily(profileFile(it)) }
            mutableProfiles.value = finalIndex
            ProfileBatchResult(createdMetadata)
        }
    }

    suspend fun rename(id: String, newName: String) = io {
        mutex.withLock {
            val index = readIndexOrCreate()
            val position = index.indexOfFirst { it.id == id }
            if (position < 0) throw ProfileStoreException("Профиль не найден.")
            val updated = index.toMutableList().apply {
                this[position] = this[position].copy(
                    name = normalizeName(newName),
                    updatedAtEpochMillis = clock(),
                )
            }
            writeIndex(updated)
            mutableProfiles.value = updated
        }
    }

    suspend fun delete(id: String) = io {
        mutex.withLock {
            val index = readIndexOrCreate()
            if (index.none { it.id == id }) return@withLock
            val updated = index.filterNot { it.id == id }
            writeIndex(updated)
            deleteProfileFamily(profileFile(id))
            mutableProfiles.value = updated
        }
    }

    suspend fun restoreBackup(id: String) = io {
        mutex.withLock {
            val index = readIndexOrCreate()
            val position = index.indexOfFirst { it.id == id }
            if (position < 0) throw ProfileStoreException("Профиль не найден.")
            val file = profileFile(id)
            val backup = backupFile(file)
            if (!backup.isFile) throw ProfileStoreException("Backup этого профиля отсутствует.")
            val backupJson = backup.readText(Charsets.UTF_8)
            requireValid(backupJson)
            val updated = index.toMutableList().apply {
                this[position] = this[position].copy(updatedAtEpochMillis = clock())
            }
            try {
                writer.writeProfile(file, backupJson.toByteArray(Charsets.UTF_8))
            } catch (error: Throwable) {
                throw storeError("Не удалось восстановить backup.", error)
            }
            try {
                writeIndex(updated)
            } catch (error: Throwable) {
                writer.rollbackProfile(file)
                throw error
            }
            mutableProfiles.value = updated
        }
    }

    private fun requireValid(rawJson: String) {
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_PROFILE_BYTES) {
            throw ProfileStoreException("Профиль больше 4 МБ.")
        }
        when (val result = validator.validate(rawJson)) {
            ConfigValidationResult.Valid -> Unit
            is ConfigValidationResult.Invalid -> throw ProfileStoreException(result.message)
        }
    }

    private fun readIndexOrCreate(): List<ProfileMetadata> {
        ensureRoot()
        if (!indexFile.exists()) {
            val legacyBackup = File(indexFile.path + ".bak")
            if (legacyBackup.isFile) legacyBackup.renameTo(indexFile)
        }
        if (!indexFile.exists()) {
            writeIndex(emptyList())
            return emptyList()
        }
        return try {
            ProfileIndexCodec.decode(indexFile.readText(Charsets.UTF_8))
        } catch (primary: Throwable) {
            val backup = File(indexFile.path + ".bak")
            if (!backup.isFile) throw storeError("Индекс профилей повреждён.", primary)
            try {
                val restored = ProfileIndexCodec.decode(backup.readText(Charsets.UTF_8))
                if (indexFile.exists()) indexFile.delete()
                if (!backup.renameTo(indexFile)) throw IOException("Не удалось восстановить index.json.")
                restored
            } catch (secondary: Throwable) {
                throw storeError("Индекс и его backup повреждены.", secondary)
            }
        }
    }

    private fun writeIndex(index: List<ProfileMetadata>) {
        try {
            writer.writeAtomic(
                indexFile,
                ProfileIndexCodec.encode(index).toByteArray(Charsets.UTF_8),
            )
        } catch (error: Throwable) {
            throw storeError("Не удалось записать индекс профилей.", error)
        }
    }

    private fun recoverAndClean(index: List<ProfileMetadata>) {
        val ids = index.map(ProfileMetadata::id).toSet()
        ids.forEach { recoverMissingProfile(profileFile(it)) }
        root.listFiles().orEmpty().forEach { file ->
            when {
                file.name == indexFile.name || file.name == indexFile.name + ".bak" -> Unit
                file.name == indexFile.name + ".new" -> file.delete()
                file.name.endsWith(".atomic") ||
                    file.name.endsWith(".atomic.new") ||
                    file.name.endsWith(".atomic.bak") -> file.delete()
                file.name.endsWith(".json") -> {
                    val id = file.name.removeSuffix(".json")
                    if (id !in ids) deleteProfileFamily(file)
                }
                file.name.endsWith(".json.bak") -> {
                    val id = file.name.removeSuffix(".json.bak")
                    if (id !in ids) file.delete()
                }
            }
        }
    }

    private fun recoverMissingProfile(file: File) {
        if (file.exists()) return
        val backup = backupFile(file)
        if (backup.isFile && !backup.renameTo(file)) {
            throw ProfileStoreException("Не удалось восстановить профиль после сбоя записи.")
        }
    }

    private fun deleteProfileFamily(file: File) {
        file.delete()
        backupFile(file).delete()
        AndroidAtomicProfileWriter.deleteAtomicFamily(AndroidAtomicProfileWriter.stageFile(file))
    }

    private fun generateUniqueId(index: List<ProfileMetadata>): String {
        repeat(16) {
            val candidate = idGenerator().lowercase()
            if (candidate.matches(ID_PATTERN) && index.none { it.id == candidate }) return candidate
        }
        throw ProfileStoreException("Не удалось создать уникальный id профиля.")
    }

    private fun normalizeName(name: String): String = name
        .let(SecretRedactor::redactInline)
        .trim()
        .take(MAX_NAME_LENGTH)
        .ifEmpty { "Профиль" }

    private fun ensureRoot() {
        if (!root.exists() && !root.mkdirs()) {
            throw ProfileStoreException("Не удалось создать каталог профилей.")
        }
        if (!root.isDirectory) throw ProfileStoreException("Каталог профилей недоступен.")
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun storeError(message: String, error: Throwable): ProfileStoreException =
        when (error) {
            is CancellationException -> throw error
            is ProfileStoreException -> error
            else -> ProfileStoreException(message, error)
        }

    private fun profileFile(id: String) = File(root, "$id.json")
    private fun backupFile(file: File) = AndroidAtomicProfileWriter.backupFile(file)
    private val indexFile get() = File(root, "index.json")

    private companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_PROFILE_BYTES = 4 * 1024 * 1024
        val ID_PATTERN = Regex("[0-9a-f]{32}")
    }
}
