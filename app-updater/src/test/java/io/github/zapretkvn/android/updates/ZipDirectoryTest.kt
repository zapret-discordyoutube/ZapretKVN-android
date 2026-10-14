package io.github.zapretkvn.android.updates

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipDirectoryTest {
    private fun directory(zip: ByteArray): ZipDirectory {
        val tail = zip.copyOfRange(maxOf(0, zip.size - ZipFormat.TAIL_PROBE_BYTES), zip.size)
        val eocd = ZipFormat.parseEndOfCentralDirectory(tail, zip.size.toLong())
        val cd = zip.copyOfRange(eocd.centralDirectoryOffset.toInt(), (eocd.centralDirectoryOffset + eocd.centralDirectorySize).toInt())
        val entries = ZipFormat.parseCentralDirectory(cd, eocd)
        val footer = zip.copyOfRange(eocd.centralDirectoryOffset.toInt() - 24, eocd.centralDirectoryOffset.toInt())
        return ZipDirectory(
            zip.size.toLong(),
            entries,
            eocd.centralDirectoryOffset,
            eocd.centralDirectorySize,
            ZipFormat.signingBlockOffset(footer, eocd.centralDirectoryOffset),
        )
    }

    @Test
    fun `parses entries offsets sizes and the signing block`() {
        val (old, _) = TestZips.apkPair()
        val dir = directory(old)
        assertEquals(6, dir.entries.size)
        assertEquals(setOf("arm64-v8a"), dir.nativeAbis)
        val so = dir.entries.single { it.name == "lib/arm64-v8a/libbox.so" }
        assertEquals(400_000L, so.compressedSize)
        assertEquals(0, so.method)
        assertNotNull(dir.signingBlockOffset)
        // Блок подписи начинается ровно там, где кончаются данные последней записи.
        val last = dir.entries.maxBy(ZipEntryInfo::localHeaderOffset)
        val lastEnd = last.localHeaderOffset +
            ZipFormat.localHeaderLength(old, last.localHeaderOffset.toInt())!! + last.compressedSize
        assertEquals(lastEnd, dir.signingBlockOffset)
    }

    @Test
    fun `eocd with a comment is found and signature inside the comment is ignored`() {
        val comment = "PK\u0005\u0006fake".toByteArray(Charsets.ISO_8859_1) + ByteArray(30)
        val zip = TestZips.build(listOf(TestEntry("a", ByteArray(10))), comment = comment)
        assertEquals(1, directory(zip).entries.size)
    }

    @Test
    fun `apk without a signing block has none`() {
        val zip = TestZips.build(listOf(TestEntry("a", ByteArray(100)), TestEntry("b", ByteArray(50))))
        assertNull(directory(zip).signingBlockOffset)
    }

    @Test
    fun `zip64 and garbage fall back instead of being guessed`() {
        val zip = TestZips.build(listOf(TestEntry("a", ByteArray(10))))
        val zip64 = zip.copyOf().also { bytes ->
            // Число записей 0xFFFF = признак ZIP64.
            bytes[bytes.size - 22 + 10] = 0xFF.toByte(); bytes[bytes.size - 22 + 11] = 0xFF.toByte()
            bytes[bytes.size - 22 + 8] = 0xFF.toByte(); bytes[bytes.size - 22 + 9] = 0xFF.toByte()
        }
        assertThrows(DeltaUnsupportedException::class.java) {
            ZipFormat.parseEndOfCentralDirectory(zip64, zip64.size.toLong())
        }
        assertThrows(DeltaUnsupportedException::class.java) {
            ZipFormat.parseEndOfCentralDirectory(ByteArray(100), 100)
        }
        // Размер каталога, не сходящийся с EOCD.
        val eocd = ZipFormat.parseEndOfCentralDirectory(zip, zip.size.toLong())
        assertThrows(DeltaUnsupportedException::class.java) {
            ZipFormat.parseCentralDirectory(ByteArray(10), eocd)
        }
    }

    @Test
    fun `zip written by the jdk is understood and data descriptors are not reusable`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("deflated.txt"))
            zip.write("hello hello hello hello".toByteArray())
            zip.closeEntry()
        }
        val dir = directory(out.toByteArray())
        val entry = dir.entries.single()
        assertEquals("deflated.txt", entry.name)
        assertTrue(!entry.hasPlainLayout)
    }

    @Test
    fun `local header length and name come from the first bytes`() {
        val zip = TestZips.build(listOf(TestEntry("lib/x.so", ByteArray(5000), align = 4096)))
        val length = ZipFormat.localHeaderLength(zip)!!
        assertEquals(4096L, length)
        assertEquals("lib/x.so", ZipFormat.localHeaderName(zip))
        assertNull(ZipFormat.localHeaderLength(ByteArray(40)))
    }
}
