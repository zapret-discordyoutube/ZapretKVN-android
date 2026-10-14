package io.github.zapretkvn.android.updates

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkDownloaderTest {
    private val url = "https://git.zapret.moe/zapretkvn/ZapretKVN-android/releases/download/v2/app.apk"

    private class Progress : DownloadProgress {
        var downloaded = 0L
        var total = 0L
        var saved = 0L
        override fun update(downloadedBytes: Long, totalBytes: Long, savedBytes: Long) {
            downloaded = downloadedBytes
            total = totalBytes
            saved = savedBytes
        }
    }

    private fun downloader(installed: File?) = ApkDownloader(
        installedApk = installed,
        minReuseBytes = 100_000,
        minApkBytesForDelta = 0,
        minSavingBytes = 1,
    )

    private fun installed(bytes: ByteArray) = File(TestZips.tempDir(), "base.apk").also { it.writeBytes(bytes) }

    private fun run(
        http: FakeRangeHttp,
        installed: File?,
        new: ByteArray,
        partial: File = File(TestZips.tempDir(), "app.apk.part"),
        sha: String = TestZips.sha256(new),
        progress: Progress = Progress(),
    ): Triple<DownloadPlan, File, Progress> = runBlocking {
        val loader = downloader(installed)
        val plan = loader.plan(http, url, new.size.toLong(), partial)
        loader.download(http, url, new.size.toLong(), sha, plan, partial, progress)
        Triple(plan, partial, progress)
    }

    @Test
    fun `delta rebuilds the new apk from the installed one and the changed ranges`() {
        val (old, new) = TestZips.apkPair()
        val http = FakeRangeHttp(mutableMapOf(url to new))

        val (plan, partial, progress) = run(http, installed(old), new)

        assertTrue(plan is DownloadPlan.Delta)
        assertArrayEquals(new, partial.readBytes())
        assertEquals(TestZips.sha256(new), ApkDownloader.sha256(partial))
        // Тяжёлые библиотеки (650 КБ из ~840 КБ, минус проба хвоста) не скачивались.
        assertTrue("served ${http.servedBytes} of ${new.size}", http.servedBytes < new.size - 550_000)
        assertEquals(http.servedBytes, plan.networkBytes)
        assertEquals(progress.total, progress.downloaded)
        assertEquals(plan.savedBytes, progress.saved)
        assertTrue(plan.savedBytes > 550_000)
    }

    @Test
    fun `corrupted download makes the delta fall back to a full download`() {
        val (old, new) = TestZips.apkPair()
        // Байт в изменённой области портится при каждом запросе диапазона, но целиком отдаётся верно.
        val http = FakeRangeHttp(mutableMapOf(url to new), corruptAt = 1_000)
        val onlyRanged = object : UpdateHttpClient by http {
            override fun open(url: String, start: Long, endInclusive: Long?): UpdateResponse =
                if (start == 0L && endInclusive == null) {
                    UpdateResponse(200, 0, new.size.toLong(), new.size.toLong(), new.inputStream())
                } else {
                    http.open(url, start, endInclusive)
                }
        }
        val partial = File(TestZips.tempDir(), "app.apk.part")
        val sha = TestZips.sha256(new)
        runBlocking {
            val loader = downloader(installed(old))
            val plan = loader.plan(onlyRanged, url, new.size.toLong(), partial)
            assertTrue(plan is DownloadPlan.Delta)
            loader.download(onlyRanged, url, new.size.toLong(), sha, plan, partial, Progress())
        }
        assertArrayEquals(new, partial.readBytes())
    }

    @Test
    fun `server without range support gives a full download`() {
        val (old, new) = TestZips.apkPair()
        val http = FakeRangeHttp(mutableMapOf(url to new), supportsRange = false)

        val (_, partial, _) = run(http, installed(old), new)

        assertArrayEquals(new, partial.readBytes())
    }

    @Test
    fun `missing or unreadable installed apk gives a full download`() {
        val (_, new) = TestZips.apkPair()
        assertTrue(run(FakeRangeHttp(mutableMapOf(url to new)), null, new).first is DownloadPlan.Full)
        val garbage = installed(ByteArray(5_000) { 7 })
        val (plan, partial, _) = run(FakeRangeHttp(mutableMapOf(url to new)), garbage, new)
        assertTrue(plan is DownloadPlan.Full)
        assertArrayEquals(new, partial.readBytes())
    }

    @Test
    fun `apk with different native abi is downloaded in full`() {
        val (_, new) = TestZips.apkPair()
        val other = TestZips.build(
            listOf(TestEntry("lib/armeabi-v7a/libbox.so", TestZips.randomBytes(400_000, 7), align = 4096)),
            signingPayload = ByteArray(100),
        )
        assertTrue(run(FakeRangeHttp(mutableMapOf(url to new)), installed(other), new).first is DownloadPlan.Full)
    }

    @Test
    fun `wrong published hash is a hard failure after a full download`() {
        val (old, new) = TestZips.apkPair()
        val http = FakeRangeHttp(mutableMapOf(url to new))
        val error = assertThrows(UpdateException::class.java) { run(http, installed(old), new, sha = "0".repeat(64)) }
        assertTrue("SHA-256" in error.message.orEmpty())
        assertFalse(error.retryViaVpn)
    }

    @Test
    fun `interrupted full download resumes the part file with a range request`() {
        val new = TestZips.randomBytes(600_000, 5)
        val http = FakeRangeHttp(mutableMapOf(url to new), interruptFirstReads = 1)

        val (_, partial, _) = run(http, null, new)

        assertArrayEquals(new, partial.readBytes())
        assertEquals(2, http.requests.size)
        assertTrue("second request resumes", http.requests[1].first > 0)
    }

    @Test
    fun `existing part file is continued instead of restarted`() {
        val new = TestZips.randomBytes(600_000, 6)
        val partial = File(TestZips.tempDir(), "app.apk.part").also { it.writeBytes(new.copyOf(250_000)) }
        val http = FakeRangeHttp(mutableMapOf(url to new))

        val progress = Progress()
        val (plan, _, _) = run(http, null, new, partial = partial, progress = progress)

        assertEquals(250_000L, (plan as DownloadPlan.Full).resumeFrom)
        assertEquals(350_000L, plan.networkBytes)
        assertEquals(250_000L to null, http.requests.single())
        assertArrayEquals(new, partial.readBytes())
    }

    @Test
    fun `corrupted resumed part is dropped and downloaded again once`() {
        val new = TestZips.randomBytes(600_000, 6)
        val broken = new.copyOf(250_000).also { it[10] = (it[10] + 1).toByte() }
        val partial = File(TestZips.tempDir(), "app.apk.part").also { it.writeBytes(broken) }
        val (_, file, _) = run(FakeRangeHttp(mutableMapOf(url to new)), null, new, partial = partial)
        assertArrayEquals(new, file.readBytes())
    }

    @Test
    fun `small saving keeps the plan full`() {
        val (_, new) = TestZips.apkPair()
        // Установленный APK без общих записей: повторно использовать нечего.
        val unrelated = TestZips.build(
            listOf(TestEntry("lib/arm64-v8a/other.so", TestZips.randomBytes(300_000, 99), align = 4096)),
            signingPayload = ByteArray(100),
        )
        assertTrue(run(FakeRangeHttp(mutableMapOf(url to new)), installed(unrelated), new).first is DownloadPlan.Full)
    }
}
