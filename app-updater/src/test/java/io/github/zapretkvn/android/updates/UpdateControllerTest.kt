package io.github.zapretkvn.android.updates

import android.content.Intent
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateControllerTest {
    private val url = "https://git.zapret.moe/zapretkvn/ZapretKVN-android/releases/download/v2.0.0/app.apk"

    private class Fixture(val root: File = File(TestZips.tempDir(), "updates"))

    private fun candidate(apk: ByteArray, versionCode: Long = 2): UpdateCandidate {
        val name = "Zapret-KVN-v2.0.0-arm64-v8a.apk"
        val sha = TestZips.sha256(apk)
        return UpdateCandidate(
            release = ForgejoRelease("v2.0.0", "2.0.0", "Заметки", "https://git.zapret.moe/r", false, false, emptyList()),
            metadata = ReleaseMetadata(
                versionName = "2.0.0", versionCode = versionCode, applicationId = "app",
                coreTag = "v1", coreCommit = "0".repeat(40), abi = listOf("arm64-v8a"),
                apkFile = name, apkSha256 = sha, apkSize = apk.size.toLong(),
            ),
            apkAsset = ForgejoAsset(name, url, apk.size.toLong(), "sha256:$sha"),
            checksumAsset = ForgejoAsset("$name.sha256", "$url.sha256", 100, null),
        )
    }

    private class Setup(
        val controller: UpdateController,
        val root: File,
        val http: FakeRangeHttp,
        val installs: MutableList<Pair<File, String>>,
        val silentFlags: MutableList<Boolean>,
        val states: MutableList<UpdateState>,
        val prefs: UpdatePrefs,
    )

    private fun controller(
        apk: ByteArray,
        installedApk: ByteArray? = null,
        metered: Boolean = false,
        vpnActive: Boolean = false,
        unattended: Boolean = false,
        foreground: Boolean = true,
        failDirect: Boolean = false,
        failAll: Boolean = false,
        source: ((UpdateHttpClient) -> UpdateReleaseSource)? = null,
        verifier: ApkUpdateVerifier = ApkUpdateVerifier { _, _ -> },
        root: File = File(TestZips.tempDir(), "updates"),
        archiveVersion: (File) -> Long? = { null },
        notifier: UpdateNotifier? = null,
        prefs: UpdatePrefs = InMemoryUpdatePrefs(),
        installerThrows: Boolean = false,
    ): Setup {
        val http = FakeRangeHttp(mutableMapOf(url to apk))
        val candidate = candidate(apk)
        val installs = CopyOnWriteArrayList<Pair<File, String>>()
        val silentFlags = CopyOnWriteArrayList<Boolean>()
        val states = CopyOnWriteArrayList<UpdateState>()
        val installed = installedApk?.let { File(TestZips.tempDir(), "base.apk").also { f -> f.writeBytes(it) } }
        val failing = object : UpdateHttpClient {
            override fun open(url: String, start: Long, endInclusive: Long?): UpdateResponse =
                throw UpdateException("Не удалось связаться с Forgejo.", retryViaVpn = true)
        }
        val controller = UpdateController(
            updatesRoot = root,
            currentVersionName = "1.0.0",
            currentVersionCode = 1,
            clientFactory = { route ->
                if (failAll || (failDirect && route == UpdateRoute.Direct)) failing else http
            },
            sourceFactory = source ?: { client ->
                UpdateReleaseSource {
                    client.open("https://git.zapret.moe/x").close() // маршрут обязан быть рабочим
                    candidate
                }
            },
            verifier = verifier,
            vpnFallback = UpdateVpnFallback { UpdateVpnSession { } },
            installer = UpdateInstaller { file, sha, _, silent ->
                if (installerThrows) throw UpdateException("Сессия установки не открылась.")
                installs += file to sha
                silentFlags += silent
            },
            environment = UpdateEnvironment(
                vpnActive = { vpnActive },
                isMetered = { metered },
                canInstallUnattended = { unattended },
                appInForeground = { foreground },
                installedApk = installed,
                archiveVersionCode = archiveVersion,
            ),
            notifier = notifier,
            prefs = prefs,
            downloader = ApkDownloader(
                installed, minReuseBytes = 100_000, minApkBytesForDelta = 0, minSavingBytes = 1,
            ),
            hedgeMillis = 20,
        )
        http.files["https://git.zapret.moe/x"] = ByteArray(1)
        return Setup(controller, root, http, installs, silentFlags, states, prefs)
    }

    private fun Setup.record() {
        // Каждое состояние записывается синхронно в потоке публикации, чтобы тест видел и промежуточные.
        kotlinx.coroutines.GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect { states += it }
        }
    }

    private fun Setup.await(timeoutMillis: Long = 15_000, predicate: (UpdateState) -> Boolean): UpdateState =
        runBlocking { withTimeout(timeoutMillis) { controller.state.first(predicate) } }

    private val bytes = TestZips.randomBytes(300_000, 1)

    @Test
    fun `stable automatic check downloads by itself and ends ready without any failure`() {
        val setup = controller(bytes)
        setup.record()
        setup.controller.checkOnce(UpdateChannel.Stable)
        val ready = setup.await { it is UpdateState.Ready } as UpdateState.Ready

        assertFalse(ready.needsConfirmation)
        assertTrue(setup.states.any { it is UpdateState.Downloading })
        assertTrue(setup.states.none { it is UpdateState.Failure })
        assertArrayEquals(bytes, File(setup.root, ready.candidate.metadata.apkFile).readBytes())
        assertEquals(0, setup.installs.size)
    }

    @Test
    fun `direct blocked while vpn is up still ends ready with no failure state`() {
        val setup = controller(bytes, vpnActive = true, failDirect = true)
        setup.record()
        setup.controller.checkOnce(UpdateChannel.Stable)
        setup.await { it is UpdateState.Ready }
        assertTrue(setup.states.none { it is UpdateState.Failure })
        assertEquals(UpdateRoute.Vpn, setup.prefs.lastRoute)
    }

    @Test
    fun `beta channel only announces the update`() {
        val setup = controller(bytes)
        setup.controller.checkOnce(UpdateChannel.Beta)
        setup.await { it is UpdateState.Available }
        assertEquals(0, setup.http.requests.size - 1)
    }

    @Test
    fun `manual check never downloads and the button downloads even on a metered network`() {
        val setup = controller(bytes, metered = true)
        setup.controller.check(UpdateChannel.Stable)
        setup.await { it is UpdateState.Available }
        assertEquals(1, setup.http.requests.size) // только пробный запрос проверки

        setup.controller.download()
        setup.await { it is UpdateState.Ready }
    }

    @Test
    fun `metered network defers a large automatic download`() {
        val big = TestZips.randomBytes(30 * 1024 * 1024, 2)
        val setup = controller(big, metered = true)
        setup.controller.checkOnce(UpdateChannel.Stable)
        val state = setup.await { it is UpdateState.Available }
        assertTrue(state is UpdateState.Available)
        assertEquals(1, setup.http.requests.size) // тело APK не запрашивалось
    }

    @Test
    fun `all routes failing is a plain failure only for a manual check`() {
        val manual = controller(bytes, failAll = true)
        manual.controller.check(UpdateChannel.Stable)
        val failure = manual.await { it is UpdateState.Failure } as UpdateState.Failure
        assertEquals("Не удалось проверить обновления. Проверим позже.", failure.message)
        assertNotNull(failure.detail)
        assertNotNull(manual.controller.lastTechnicalDetail)

        val automatic = controller(bytes, failAll = true)
        automatic.record()
        automatic.controller.checkOnce(UpdateChannel.Stable)
        Thread.sleep(500)
        assertEquals(UpdateState.Idle, automatic.controller.state.value)
        assertTrue(automatic.states.none { it is UpdateState.Failure })
    }

    @Test
    fun `current version is reported as up to date and cache is cleaned`() {
        val setup = controller(bytes, source = {
            UpdateReleaseSource { candidate(bytes, versionCode = 1) }
        })
        setup.root.mkdirs()
        File(setup.root, "old.apk").writeBytes(byteArrayOf(1))
        setup.controller.check(UpdateChannel.Stable)
        setup.await { it is UpdateState.UpToDate }
        assertFalse(setup.root.exists())
    }

    @Test
    fun `delta download reports savings and rebuilds the published apk`() {
        val (old, new) = TestZips.apkPair()
        val setup = controller(new, installedApk = old)
        setup.record()
        setup.controller.checkOnce(UpdateChannel.Stable)
        val ready = setup.await { it is UpdateState.Ready } as UpdateState.Ready

        val downloading = setup.states.filterIsInstance<UpdateState.Downloading>().last()
        assertTrue("saved ${downloading.savedBytes}", downloading.savedBytes > 550_000)
        assertTrue(downloading.totalBytes < new.size - 550_000)
        assertArrayEquals(new, File(setup.root, ready.candidate.metadata.apkFile).readBytes())
    }

    @Test
    fun `already downloaded apk is verified again and reused without downloading`() {
        val root = File(TestZips.tempDir(), "updates")
        val first = controller(bytes, root = root)
        first.controller.checkOnce(UpdateChannel.Stable)
        first.await { it is UpdateState.Ready }

        var verified = 0
        val second = controller(
            bytes, root = root, archiveVersion = { 2 },
            verifier = ApkUpdateVerifier { _, _ -> verified++ },
        )
        second.controller.checkOnce(UpdateChannel.Stable)
        second.await { it is UpdateState.Ready }
        assertEquals(1, verified)
        assertEquals("only the check probe, no apk request", 1, second.http.requests.size)
    }

    @Test
    fun `start cleanup removes part files and obsolete apks but keeps a newer verified one`() {
        val root = File(TestZips.tempDir(), "updates").also { it.mkdirs() }
        File(root, "a.apk.part").writeBytes(byteArrayOf(1))
        File(root, "old.apk").writeBytes(byteArrayOf(2))
        File(root, "new.apk").writeBytes(byteArrayOf(3))
        File(root, "junk.txt").writeBytes(byteArrayOf(4))
        controller(
            bytes, root = root,
            archiveVersion = { file -> if (file.name == "new.apk") 5L else 1L },
        )
        assertEquals(listOf("new.apk"), root.list()!!.toList())
    }

    @Test
    fun `installation hands the verified file to the installer once and deletes the cache copy`() {
        val setup = controller(bytes)
        setup.controller.checkOnce(UpdateChannel.Stable)
        val ready = setup.await { it is UpdateState.Ready } as UpdateState.Ready

        setup.controller.install(userInitiated = true)
        setup.await { it is UpdateState.Installing }
        runBlocking { withTimeout(10_000) { while (setup.installs.isEmpty()) kotlinx.coroutines.yield() } }

        assertEquals(1, setup.installs.size)
        assertEquals("a tap asks Android for the normal confirmation", listOf(false), setup.silentFlags)
        assertEquals(ready.candidate.metadata.apkSha256, setup.installs.single().second)
        assertTrue(setup.prefs.installingVersionCode == 2L)
        Thread.sleep(200)
        assertFalse(File(setup.root, ready.candidate.metadata.apkFile).exists())
    }

    @Test
    fun `tampered file in cache never reaches the installer`() {
        val setup = controller(bytes)
        setup.controller.checkOnce(UpdateChannel.Stable)
        val ready = setup.await { it is UpdateState.Ready } as UpdateState.Ready
        File(setup.root, ready.candidate.metadata.apkFile).appendBytes(byteArrayOf(0))

        setup.controller.install(userInitiated = true)
        setup.await { it is UpdateState.Failure }
        assertEquals(0, setup.installs.size)
    }

    @Test
    fun `signature verifier failure keeps the apk from becoming ready`() {
        val setup = controller(bytes, verifier = ApkUpdateVerifier { _, _ ->
            throw UpdateException("Подпись APK не совместима с установленным приложением.")
        })
        setup.controller.checkOnce(UpdateChannel.Stable)
        val failure = setup.await { it is UpdateState.Failure } as UpdateState.Failure
        assertTrue("Подпись" in failure.message)
        assertFalse(setup.root.exists())
    }

    @Test
    fun `background run installs unattended when allowed and posts nothing`() {
        val notes = mutableListOf<String>()
        val setup = controller(
            bytes, unattended = true, foreground = false,
            notifier = object : UpdateNotifier {
                override fun updateReady(versionName: String) { notes += "ready" }
                override fun confirmationNeeded(versionName: String, intent: Intent) { notes += "confirm" }
                override fun installed(versionName: String) { notes += "installed" }
            },
        )
        val job = setup.controller.startBackground { UpdateChannel.Stable }!!
        runBlocking { withTimeout(15_000) { job.join() } }
        assertEquals(1, setup.installs.size)
        assertEquals(listOf(true), setup.silentFlags)
        assertTrue(notes.isEmpty())
        assertEquals(2L, setup.prefs.silentAttemptVersionCode)
    }

    @Test
    fun `a silent attempt that never finished is not repeated and the user is asked instead`() {
        val notes = mutableListOf<String>()
        val prefs = InMemoryUpdatePrefs().apply { silentAttemptVersionCode = 2 }
        val setup = controller(
            bytes, unattended = true, foreground = false, prefs = prefs,
            notifier = object : UpdateNotifier {
                override fun updateReady(versionName: String) { notes += "ready:$versionName" }
                override fun confirmationNeeded(versionName: String, intent: Intent) {}
                override fun installed(versionName: String) {}
            },
        )
        val job = setup.controller.startBackground { UpdateChannel.Stable }!!
        runBlocking { withTimeout(15_000) { job.join() } }
        assertEquals(0, setup.installs.size)
        assertEquals(listOf("ready:2.0.0"), notes)
    }

    @Test
    fun `background run without install permission leaves a quiet notification`() {
        val notes = mutableListOf<String>()
        val setup = controller(
            bytes, unattended = false, foreground = false,
            notifier = object : UpdateNotifier {
                override fun updateReady(versionName: String) { notes += "ready:$versionName" }
                override fun confirmationNeeded(versionName: String, intent: Intent) {}
                override fun installed(versionName: String) {}
            },
        )
        val job = setup.controller.startBackground { UpdateChannel.Stable }!!
        runBlocking { withTimeout(15_000) { job.join() } }
        assertEquals(listOf("ready:2.0.0"), notes)
        assertEquals(0, setup.installs.size)
        assertTrue(setup.controller.state.value is UpdateState.Ready)
    }

    @Test
    fun `pending confirmation becomes a quiet ready state with an intent and a one shot launch request`() {
        val notes = mutableListOf<String>()
        val setup = controller(
            bytes, foreground = true,
            notifier = object : UpdateNotifier {
                override fun updateReady(versionName: String) {}
                override fun confirmationNeeded(versionName: String, intent: Intent) { notes += "confirm" }
                override fun installed(versionName: String) {}
            },
        )
        setup.controller.checkOnce(UpdateChannel.Stable)
        setup.await { it is UpdateState.Ready }
        setup.controller.install(userInitiated = true)
        runBlocking { withTimeout(10_000) { while (setup.installs.isEmpty()) kotlinx.coroutines.yield() } }

        val intent = Intent()
        setup.controller.onInstallResult(UpdateInstallResult.NeedsConfirmation(intent))
        val state = setup.controller.state.value as UpdateState.Ready
        assertTrue(state.needsConfirmation)
        assertTrue(setup.controller.confirmationIntent() === intent)
        assertTrue(setup.controller.consumeConfirmationLaunchRequest())
        assertFalse(setup.controller.consumeConfirmationLaunchRequest())
        assertTrue("user is looking at the app, no notification", notes.isEmpty())
    }

    @Test
    fun `installer failure and system abort become a failure with the candidate`() {
        val setup = controller(bytes, installerThrows = true)
        setup.controller.checkOnce(UpdateChannel.Stable)
        setup.await { it is UpdateState.Ready }
        setup.controller.install()
        val failure = setup.await { it is UpdateState.Failure } as UpdateState.Failure
        assertNotNull(failure.candidate)
        assertEquals(0L, setup.prefs.installingVersionCode)

        setup.controller.onInstallResult(UpdateInstallResult.Failed("Установка отменена.", "ABORTED"))
        assertEquals("Установка отменена.", (setup.controller.state.value as UpdateState.Failure).message)
        assertEquals("ABORTED", setup.controller.lastTechnicalDetail)
    }

    @Test
    fun `finished install is announced once after the restart`() {
        val prefs = InMemoryUpdatePrefs().apply { installingVersionCode = 1; installingVersionName = "1.0.0" }
        val notes = mutableListOf<String>()
        controller(
            bytes, prefs = prefs,
            notifier = object : UpdateNotifier {
                override fun updateReady(versionName: String) {}
                override fun confirmationNeeded(versionName: String, intent: Intent) {}
                override fun installed(versionName: String) { notes += versionName }
            },
        )
        assertEquals(listOf("1.0.0"), notes)
        assertEquals(0L, prefs.installingVersionCode)
    }

    @Test
    fun `banner dismissal is remembered per version`() {
        val setup = controller(bytes)
        assertEquals(0L, setup.controller.bannerDismissedVersionCode.value)
        setup.controller.dismissBanner(2)
        assertEquals(2L, setup.controller.bannerDismissedVersionCode.value)
        assertEquals(2L, setup.prefs.bannerDismissedVersionCode)
        assertNull(setup.controller.confirmationIntent())
    }

    @Test
    fun `cancel removes every cached file`() {
        val setup = controller(bytes)
        setup.controller.checkOnce(UpdateChannel.Stable)
        setup.await { it is UpdateState.Ready }
        setup.controller.cancelAndDelete()
        setup.await { it == UpdateState.Idle }
        assertFalse(setup.root.exists())
    }
}
