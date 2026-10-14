package io.github.zapretkvn.android.updates

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateControllerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class BytesHttp(private val bytes: ByteArray, private val broken: Boolean = false) : UpdateHttpClient {
        override fun open(url: String, start: Long, endInclusive: Long?): UpdateResponse {
            if (broken) throw UpdateException("Не удалось связаться с Forgejo.", retryViaVpn = true)
            val end = minOf(endInclusive ?: (bytes.size - 1L), bytes.size - 1L).toInt()
            val slice = bytes.copyOfRange(start.toInt(), end + 1)
            return if (start > 0 || endInclusive != null) {
                UpdateResponse(206, start, bytes.size.toLong(), slice.size.toLong(), ByteArrayInputStream(slice))
            } else {
                UpdateResponse(200, 0, bytes.size.toLong(), slice.size.toLong(), ByteArrayInputStream(slice))
            }
        }
    }

    private fun controller(
        candidate: UpdateCandidate,
        bytes: ByteArray,
        verifier: ApkUpdateVerifier,
        broken: Boolean = false,
        vpn: UpdateVpnFallback? = null,
    ) = UpdateController(
        updatesRoot = updateRoot(),
        currentVersionName = "1.0.0",
        currentVersionCode = 1,
        clientFactory = { route -> BytesHttp(bytes, broken && route == UpdateRoute.Direct) },
        // Источник ходит через выданный маршрутом клиент, как настоящий ForgejoUpdateSource:
        // иначе «сломанный» прямой маршрут никогда бы не проверялся.
        sourceFactory = { client ->
            UpdateReleaseSource {
                client.open("https://forgejo.invalid/release.json", 0, null).close()
                candidate
            }
        },
        verifier = verifier,
        vpnFallback = vpn,
        prefs = InMemoryUpdatePrefs(),
        environment = UpdateEnvironment(),
    )

    @Test
    fun directFailureUsesOneTemporaryVpnSessionAndRestoresIt() = runBlocking {
        val bytes = "verified apk fixture".toByteArray()
        var connected = 0
        var restored = 0
        val controller = controller(
            candidate(bytes), bytes, ApkUpdateVerifier { _, _ -> }, broken = true,
            vpn = UpdateVpnFallback {
                connected++
                UpdateVpnSession { restored++ }
            },
        )

        controller.check(UpdateChannel.Beta)
        withTimeout(5_000) { controller.state.first { it is UpdateState.Available } }

        assertEquals(1, connected)
        assertEquals(1, restored)
        controller.cancelAndDelete()
    }

    @Test
    fun manualCheckDownloadAndCancelUseOnlyUpdateCache() = runBlocking {
        val bytes = "verified apk fixture".toByteArray()
        val candidate = candidate(bytes)
        var verified = false
        val controller = controller(
            candidate = candidate,
            bytes = bytes,
            verifier = ApkUpdateVerifier { file, metadata ->
                assertEquals(bytes.toList(), file.readBytes().toList())
                assertEquals(candidate.metadata, metadata)
                verified = true
            },
        )

        controller.check(UpdateChannel.Beta)
        withTimeout(5_000) { controller.state.first { it is UpdateState.Available } }
        controller.download()
        withTimeout(5_000) { controller.state.first { it is UpdateState.Ready } }

        assertTrue(verified)
        assertEquals(1, updateRoot().listFiles().orEmpty().size)

        controller.cancelAndDelete()
        withTimeout(5_000) { controller.state.first { it == UpdateState.Idle } }
        assertFalse(updateRoot().exists())
    }

    @Test
    fun checksumFailureLeavesNoFilesAndNewControllerCleansStaleOnes() = runBlocking {
        val bytes = "tampered".toByteArray()
        val bad = candidate(bytes).copy(
            metadata = candidate(bytes).metadata.copy(apkSha256 = "f".repeat(64)),
        )
        val controller = controller(bad, bytes, ApkUpdateVerifier { _, _ -> error("must not verify") })
        controller.check(UpdateChannel.Stable)
        withTimeout(5_000) { controller.state.first { it is UpdateState.Available } }
        controller.download()
        val failure = withTimeout(5_000) { controller.state.first { it is UpdateState.Failure } }
        assertTrue((failure as UpdateState.Failure).message.contains("SHA-256"))
        assertFalse(updateRoot().exists())

        updateRoot().mkdirs()
        File(updateRoot(), "orphan.apk.part").writeText("partial")
        controller(bad, bytes, ApkUpdateVerifier { _, _ -> }).cleanupStaleFiles()
        assertFalse(updateRoot().exists())
    }

    @Test
    fun androidReadsCurrentApkPackageAndSigningCertificateFromArchive() {
        val verifier = AndroidApkUpdateVerifier(context)
        val installed = verifier.inspectInstalled()
        val archive = verifier.inspectArchive(File(context.applicationInfo.sourceDir))

        assertEquals(context.packageName, archive.packageName)
        assertEquals(installed.versionCode, archive.versionCode)
        assertTrue(UpdateInstallPolicy.signingCompatible(installed.signing, archive.signing))
    }

    @Test
    fun installedApkCentralDirectoryIsReadableForDeltaDownloads() {
        val apk = LocalApk.open(File(context.applicationInfo.sourceDir))
        assertTrue(apk.directory.entries.any { it.name == "AndroidManifest.xml" })
        assertTrue(apk.directory.entries.all { apk.entry(it.name) != null })
    }

    private fun candidate(bytes: ByteArray): UpdateCandidate {
        val name = "Zapret-KVN-v1.1.0-arm64-v8a.apk"
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val metadata = ReleaseMetadata(
            versionName = "1.1.0",
            versionCode = 2,
            applicationId = context.packageName,
            coreTag = "v1.13.14-extended-2.5.2",
            coreCommit = "ff11f007ec798136a5de258f947a4f34011a37ea",
            abi = listOf("arm64-v8a"),
            apkFile = name,
            apkSha256 = sha,
            apkSize = bytes.size.toLong(),
        )
        val apk = ForgejoAsset(name, "https://git.zapret.moe/zapretkvn/ZapretKVN-android/apk", bytes.size.toLong(), "sha256:$sha")
        return UpdateCandidate(
            release = ForgejoRelease(
                "v1.1.0",
                "1.1.0",
                "Changes",
                "https://github.com/release",
                false,
                false,
                emptyList(),
            ),
            metadata = metadata,
            apkAsset = apk,
            checksumAsset = ForgejoAsset("$name.sha256", "https://git.zapret.moe/sha", 100, null),
        )
    }

    private fun updateRoot() = File(context.cacheDir, "updates-test")
}
