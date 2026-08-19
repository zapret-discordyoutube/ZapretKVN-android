package io.github.zapretkvn.android.updates

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateControllerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun retryableCheckUsesOneTemporaryVpnSessionAndRestoresIt() = runBlocking {
        val bytes = "verified apk fixture".toByteArray()
        val candidate = candidate(bytes)
        var attempts = 0
        var connected = 0
        var restored = 0
        val controller = UpdateController(
            context = context,
            repository = "zapretkvn/ZapretKVN-android",
            currentVersionName = "1.0.0",
            currentVersionCode = 1,
            source = UpdateReleaseSource {
                attempts++
                if (attempts == 1) {
                    throw UpdateException("Forgejo недоступен.", retryViaVpn = true)
                }
                candidate
            },
            vpnFallback = UpdateVpnFallback {
                connected++
                UpdateVpnSession { restored++ }
            },
        )

        controller.check(UpdateChannel.Beta)
        withTimeout(5_000) { controller.state.first { it is UpdateState.Available } }

        assertEquals(2, attempts)
        assertEquals(1, connected)
        assertEquals(1, restored)
        controller.cancelAndDelete()
    }

    @Test
    fun automaticCheckRunsOnlyOncePerProcessController() = runBlocking {
        val bytes = "verified apk fixture".toByteArray()
        val candidate = candidate(bytes)
        var checks = 0
        val controller = UpdateController(
            context = context,
            repository = "zapretkvn/ZapretKVN-android",
            currentVersionName = "1.0.0",
            currentVersionCode = 1,
            source = UpdateReleaseSource {
                checks++
                candidate
            },
            http = object : UpdateHttpClient {
                override fun readText(url: String, maxBytes: Int): String = error("not used")
                override fun download(url: String, target: File, expectedBytes: Long, onProgress: (Long) -> Unit) =
                    error("not used")
            },
            verifier = ApkUpdateVerifier { _, _ -> },
        )

        controller.checkOnce(UpdateChannel.Beta)
        controller.checkOnce(UpdateChannel.Beta)
        withTimeout(5_000) { controller.state.first { it is UpdateState.Available } }

        assertEquals(1, checks)
        controller.cancelAndDelete()
    }

    @Test
    fun manualCheckDownloadIntentAndCancelUseOnlyUpdateCache() = runBlocking {
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
        assertTrue(controller.consumeAutomaticInstallRequest())
        assertFalse(controller.consumeAutomaticInstallRequest())
        val intent = controller.createInstallIntent()
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
        assertEquals("content", intent.data?.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(context.packageManager.resolveActivity(intent, 0))
        assertEquals(1, updateRoot().listFiles().orEmpty().size)

        controller.onInstallerFinished(installed = false)
        withTimeout(5_000) { controller.state.first { it is UpdateState.Failure } }
        assertFalse(updateRoot().exists())
        controller.cancelAndDelete()
        withTimeout(5_000) { controller.state.first { it == UpdateState.Idle } }
        Unit
    }

    @Test
    fun checksumFailureAndNewControllerRemoveCompleteAndPartialApks() = runBlocking {
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
    fun interruptedDownloadDeletesPartialFile() = runBlocking {
        val bytes = "interrupted download".toByteArray()
        val candidate = candidate(bytes)
        val controller = UpdateController(
            context = context,
            repository = "zapretkvn/ZapretKVN-android",
            currentVersionName = "1.0.0",
            currentVersionCode = 1,
            source = UpdateReleaseSource { candidate },
            http = object : UpdateHttpClient {
                override fun readText(url: String, maxBytes: Int): String = error("not used")
                override fun download(
                    url: String,
                    target: File,
                    expectedBytes: Long,
                    onProgress: (Long) -> Unit,
                ) {
                    target.writeBytes(bytes.copyOf(bytes.size / 2))
                    throw UpdateException("Загрузка APK прервана.")
                }
            },
            verifier = ApkUpdateVerifier { _, _ -> error("must not verify") },
        )
        controller.check(UpdateChannel.Stable)
        withTimeout(5_000) { controller.state.first { it is UpdateState.Available } }
        controller.download()
        val failure = withTimeout(5_000) { controller.state.first { it is UpdateState.Failure } }

        assertTrue((failure as UpdateState.Failure).message.contains("прервана"))
        assertFalse(updateRoot().exists())

        val cancelling = UpdateController(
            context = context,
            repository = "zapretkvn/ZapretKVN-android",
            currentVersionName = "1.0.0",
            currentVersionCode = 1,
            source = UpdateReleaseSource { candidate },
            http = object : UpdateHttpClient {
                override fun readText(url: String, maxBytes: Int): String = error("not used")
                override fun download(
                    url: String,
                    target: File,
                    expectedBytes: Long,
                    onProgress: (Long) -> Unit,
                ) {
                    target.writeBytes(bytes.copyOf(bytes.size / 2))
                    while (true) {
                        onProgress(bytes.size.toLong() / 2)
                        Thread.sleep(10)
                    }
                }
            },
            verifier = ApkUpdateVerifier { _, _ -> error("must not verify") },
        )
        cancelling.check(UpdateChannel.Stable)
        withTimeout(5_000) { cancelling.state.first { it is UpdateState.Available } }
        cancelling.download()
        withTimeout(5_000) {
            cancelling.state.first {
                it is UpdateState.Downloading && it.downloadedBytes > 0
            }
        }
        cancelling.cancelAndDelete()
        withTimeout(5_000) { cancelling.state.first { it == UpdateState.Idle } }
        assertFalse(updateRoot().exists())
    }

    private fun controller(
        candidate: UpdateCandidate,
        bytes: ByteArray,
        verifier: ApkUpdateVerifier,
    ) = UpdateController(
        context = context,
        repository = "zapretkvn/ZapretKVN-android",
        currentVersionName = "1.0.0",
        currentVersionCode = 1,
        installIntentFactory = AndroidUpdateInstallIntentFactory(context),
        source = UpdateReleaseSource { candidate },
        http = object : UpdateHttpClient {
            override fun readText(url: String, maxBytes: Int): String = error("not used")
            override fun download(
                url: String,
                target: File,
                expectedBytes: Long,
                onProgress: (Long) -> Unit,
            ) {
                target.writeBytes(bytes)
                onProgress(bytes.size.toLong())
            }
        },
        verifier = verifier,
    )

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

    private fun updateRoot() = File(context.cacheDir, "updates")
}
