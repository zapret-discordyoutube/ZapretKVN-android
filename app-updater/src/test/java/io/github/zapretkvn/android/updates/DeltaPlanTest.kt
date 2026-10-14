package io.github.zapretkvn.android.updates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaPlanTest {
    @Test
    fun `ranges are merged only across small gaps`() {
        val merged = DeltaPlanner.mergeRanges(
            listOf(ByteRange(300, 400), ByteRange(0, 100), ByteRange(110, 150), ByteRange(1_000_000, 1_000_100)),
            maxGap = 100,
        )
        assertEquals(listOf(ByteRange(0, 150), ByteRange(300, 400), ByteRange(1_000_000, 1_000_100)), merged)
        assertEquals(listOf(ByteRange(0, 400)), DeltaPlanner.mergeRanges(merged.take(2), maxGap = 200))
        assertTrue(DeltaPlanner.mergeRanges(emptyList(), 10).isEmpty())
    }

    @Test
    fun `long ranges are split into bounded chunks`() {
        val chunks = DeltaPlanner.splitRanges(listOf(ByteRange(10, 35)), maxChunk = 10)
        assertEquals(listOf(ByteRange(10, 20), ByteRange(20, 30), ByteRange(30, 35)), chunks)
    }

    private fun plan(old: ByteArray, new: ByteArray, minReuse: Long = 100_000): DeltaPlan {
        val dir = tempApk(old)
        val local = LocalApk.open(dir)
        val tail = new.copyOfRange(new.size - 70_000.coerceAtMost(new.size), new.size)
        val eocd = ZipFormat.parseEndOfCentralDirectory(tail, new.size.toLong())
        val start = eocd.centralDirectoryOffset - 24
        val block = new.copyOfRange(start.toInt(), new.size)
        val entries = ZipFormat.parseCentralDirectory(
            block.copyOfRange(24, 24 + eocd.centralDirectorySize.toInt()),
            eocd,
        )
        val signing = ZipFormat.signingBlockOffset(block.copyOf(24), eocd.centralDirectoryOffset)
        return DeltaPlanner.plan(
            ZipDirectory(new.size.toLong(), entries, eocd.centralDirectoryOffset, eocd.centralDirectorySize, signing),
            local,
            start,
            block,
            block.size.toLong(),
            minReuseBytes = minReuse,
        )
    }

    private fun tempApk(bytes: ByteArray): File =
        File(TestZips.tempDir(), "old.apk").also { it.writeBytes(bytes) }

    @Test
    fun `only unchanged big entries are reused and the rest is downloaded`() {
        val (old, new) = TestZips.apkPair()
        val result = plan(old, new)

        assertEquals(setOf("lib/arm64-v8a/libbox.so", "lib/arm64-v8a/libgojni.so"), result.reuse.map { it.name }.toSet())
        assertEquals(650_000L, result.reusedBytes)
        // Сеть = всё, кроме повторно используемых данных (заголовки, изменённое, подпись, каталог).
        assertEquals(new.size - 650_000L, result.rangeBytes + result.prefetched.size)
        assertTrue(result.savedBytes > 600_000)
        // Диапазоны не пересекаются с повторно используемыми областями.
        for (copy in result.reuse) {
            assertTrue(result.downloads.none { it.start < copy.newOffset + copy.length && copy.newOffset < it.endExclusive })
        }
    }

    @Test
    fun `native abi mismatch disables reuse completely`() {
        val (_, new) = TestZips.apkPair()
        val otherAbi = TestZips.build(
            listOf(TestEntry("lib/armeabi-v7a/libbox.so", TestZips.randomBytes(200_000, 7), align = 4096)),
            signingPayload = ByteArray(100),
        )
        val result = plan(otherAbi, new)
        assertTrue(result.reuse.isEmpty())
        assertEquals(new.size - result.prefetched.size.toLong(), result.rangeBytes)
    }

    @Test
    fun `changed content size or method is never reused`() {
        val data = TestZips.randomBytes(300_000, 1)
        val changedByte = data.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        val old = TestZips.build(listOf(TestEntry("lib/arm64-v8a/a.so", data, align = 4096)), signingPayload = ByteArray(64))
        val newSame = TestZips.build(listOf(TestEntry("lib/arm64-v8a/a.so", data, align = 4096)), signingPayload = ByteArray(64))
        val newChanged = TestZips.build(listOf(TestEntry("lib/arm64-v8a/a.so", changedByte, align = 4096)), signingPayload = ByteArray(64))
        val newDeflated = TestZips.build(listOf(TestEntry("lib/arm64-v8a/a.so", data, deflate = true)), signingPayload = ByteArray(64))
        assertEquals(1, plan(old, newSame).reuse.size)
        assertTrue("CRC differs", plan(old, newChanged).reuse.isEmpty())
        assertTrue("method differs", plan(old, newDeflated).reuse.isEmpty())
    }

    @Test
    fun `entries with data descriptors are downloaded in full`() {
        val data = TestZips.randomBytes(300_000, 1)
        val old = TestZips.build(listOf(TestEntry("a.bin", data)), signingPayload = ByteArray(64))
        val withDescriptor = TestZips.build(listOf(TestEntry("a.bin", data, dataDescriptor = true)), signingPayload = ByteArray(64))
        assertTrue(plan(old, withDescriptor).reuse.isEmpty())
    }
}
