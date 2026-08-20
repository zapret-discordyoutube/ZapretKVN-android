package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigValidationResult
import io.github.zapretkvn.android.config.ConfigValidator
import io.github.zapretkvn.android.config.JsonConfig
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed update leaves old profile readable`() = runBlocking {
        val writer = FailingWriter(JvmAtomicWriter())
        val store = store(writer)
        store.initialize()
        val metadata = store.create("Test", OLD_JSON, ProfileSource.RawJson)
        writer.failProfileWrite = true

        runCatching { store.update(metadata.id, NEW_JSON) }
            .onSuccess { throw AssertionError("Update must fail") }

        assertEquals(OLD_JSON, store.read(metadata.id).json)
    }

    @Test
    fun `backup restores previous version and survives store recreation`() = runBlocking {
        val root = temporaryFolder.newFolder("profiles")
        val first = store(JvmAtomicWriter(), root)
        first.initialize()
        val metadata = first.create("Test", OLD_JSON, ProfileSource.RawJson)
        first.update(metadata.id, NEW_JSON)
        assertTrue(first.read(metadata.id).hasBackup)

        val reopened = store(JvmAtomicWriter(), root)
        reopened.initialize()
        assertEquals(NEW_JSON, reopened.read(metadata.id).json)
        reopened.restoreBackup(metadata.id)
        assertEquals(OLD_JSON, reopened.read(metadata.id).json)
        assertTrue(reopened.read(metadata.id).hasBackup)
    }

    @Test
    fun `malformed json cannot modify profile`() = runBlocking {
        val store = store(JvmAtomicWriter())
        store.initialize()
        val metadata = store.create("Test", OLD_JSON, ProfileSource.RawJson)

        runCatching { store.update(metadata.id, "{broken") }
            .onSuccess { throw AssertionError("Malformed JSON must fail") }

        assertEquals(OLD_JSON, store.read(metadata.id).json)
    }

    @Test
    fun `index contains metadata only and no credentials`() = runBlocking {
        val root = temporaryFolder.newFolder("profiles")
        val store = store(JvmAtomicWriter(), root)
        store.initialize()
        store.create("Safe name", SECRET_JSON, ProfileSource.Clipboard)
        val index = File(root, "index.json").readText()

        assertFalse("outbounds" in index)
        assertFalse("route" in index)
        assertFalse("dns" in index)
        assertFalse("super-secret" in index)
        assertEquals(1, ProfileIndexCodec.decode(index).size)
    }

    @Test
    fun `profile names cannot move url credentials into index or notification metadata`() = runBlocking {
        val root = temporaryFolder.newFolder("profiles-sensitive-name")
        val store = store(JvmAtomicWriter(), root)
        store.initialize()

        val metadata = store.create(
            "https://user:password@sub.example/list?token=super-secret",
            OLD_JSON,
            ProfileSource.Url,
        )
        val index = File(root, "index.json").readText()

        assertFalse("password" in metadata.name)
        assertFalse("super-secret" in metadata.name)
        assertFalse("password" in index)
        assertFalse("super-secret" in index)
    }

    @Test
    fun `initialize removes orphan profiles and atomic temp files`() = runBlocking {
        val root = temporaryFolder.newFolder("profiles")
        val store = store(JvmAtomicWriter(), root)
        store.initialize()
        File(root, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json").writeText(OLD_JSON)
        File(root, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json.atomic").writeText("temp")

        store.initialize()

        assertFalse(File(root, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json").exists())
        assertFalse(File(root, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json.atomic").exists())
    }

    @Test
    fun `batch reconciles updates additions and removals in one index`() = runBlocking {
        val store = store(JvmAtomicWriter())
        store.initialize()
        val first = store.create("First", OLD_JSON, ProfileSource.Link)
        val secondId = "11111111111111111111111111111111"
        store.applyBatch(
            updates = emptyMap(),
            creates = listOf(ProfileBatchCreate(secondId, "Second", OLD_JSON, ProfileSource.Link)),
            deletes = emptySet(),
        )
        val thirdId = "22222222222222222222222222222222"

        val result = store.applyBatch(
            updates = mapOf(first.id to NEW_JSON),
            creates = listOf(ProfileBatchCreate(thirdId, "Third", NEW_JSON, ProfileSource.Link)),
            deletes = setOf(secondId),
        )

        assertEquals(listOf(first.id, thirdId), store.profiles.value.map(ProfileMetadata::id))
        assertEquals(NEW_JSON, store.read(first.id).json)
        assertEquals(listOf(thirdId), result.created.map(ProfileMetadata::id))
        assertTrue(runCatching { store.read(secondId) }.isFailure)
    }

    @Test
    fun `invalid batch changes nothing`() = runBlocking {
        val store = store(JvmAtomicWriter())
        store.initialize()
        val first = store.create("First", OLD_JSON, ProfileSource.Link)

        runCatching {
            store.applyBatch(
                updates = mapOf(first.id to "{broken"),
                creates = listOf(
                    ProfileBatchCreate(
                        "11111111111111111111111111111111",
                        "Second",
                        NEW_JSON,
                        ProfileSource.Link,
                    ),
                ),
                deletes = emptySet(),
            )
        }.onSuccess { throw AssertionError("Batch must fail") }

        assertEquals(listOf(first.id), store.profiles.value.map(ProfileMetadata::id))
        assertEquals(OLD_JSON, store.read(first.id).json)
    }

    private fun store(
        writer: AtomicProfileWriter,
        root: File = temporaryFolder.newFolder("profiles-${System.nanoTime()}"),
    ) = ProfileStore(
        root = root,
        validator = ConfigValidator { raw ->
            runCatching { JsonConfig.parse(raw) }.fold(
                onSuccess = { ConfigValidationResult.Valid },
                onFailure = { ConfigValidationResult.Invalid("bad json") },
            )
        },
        writer = writer,
        clock = { 1234L },
        idGenerator = { "0123456789abcdef0123456789abcdef" },
    )

    private class FailingWriter(private val delegate: AtomicProfileWriter) : AtomicProfileWriter {
        var failProfileWrite = false

        override fun writeAtomic(target: File, bytes: ByteArray) = delegate.writeAtomic(target, bytes)

        override fun writeProfile(target: File, bytes: ByteArray) {
            if (failProfileWrite) throw IOException("injected")
            delegate.writeProfile(target, bytes)
        }

        override fun rollbackProfile(target: File): Boolean = delegate.rollbackProfile(target)
    }

    private class JvmAtomicWriter : AtomicProfileWriter {
        override fun writeAtomic(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".new")
            temp.writeBytes(bytes)
            move(temp, target)
        }

        override fun writeProfile(target: File, bytes: ByteArray) {
            val stage = AndroidAtomicProfileWriter.stageFile(target)
            writeAtomic(stage, bytes)
            val backup = AndroidAtomicProfileWriter.backupFile(target)
            backup.delete()
            if (target.exists()) move(target, backup)
            move(stage, target)
        }

        override fun rollbackProfile(target: File): Boolean {
            val backup = AndroidAtomicProfileWriter.backupFile(target)
            if (!backup.exists()) return false
            target.delete()
            move(backup, target)
            return true
        }

        private fun move(from: File, to: File) {
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }

    private companion object {
        const val OLD_JSON = """{"outbounds":[{"type":"direct","tag":"old"}]}"""
        const val NEW_JSON = """{"outbounds":[{"type":"direct","tag":"new"}]}"""
        const val SECRET_JSON =
            """{"outbounds":[{"type":"trojan","tag":"server","server":"example.org","server_port":443,"password":"super-secret"}],"route":{"final":"server"}}"""
    }
}
