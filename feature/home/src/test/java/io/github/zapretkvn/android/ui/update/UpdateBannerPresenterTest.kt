package io.github.zapretkvn.android.ui.update

import io.github.zapretkvn.android.updates.ForgejoAsset
import io.github.zapretkvn.android.updates.ForgejoRelease
import io.github.zapretkvn.android.updates.ReleaseMetadata
import io.github.zapretkvn.android.updates.UpdateCandidate
import io.github.zapretkvn.android.updates.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Same fixture as in :feature:settings tests: feature test source sets do not share code.
private fun testCandidate(version: String = "1.5.0", notes: String = "### Что нового\n\n- быстрее") =
    UpdateCandidate(
        release = ForgejoRelease(
            tag = "v$version",
            title = version,
            body = notes,
            pageUrl = "https://example.test/release",
            draft = false,
            prerelease = false,
            assets = emptyList(),
        ),
        metadata = ReleaseMetadata(
            versionName = version,
            versionCode = 150,
            applicationId = "io.github.zapretkvn.android",
            coreTag = "v1.14.1",
            coreCommit = "0".repeat(40),
            abi = listOf("arm64-v8a"),
            apkFile = "update.apk",
            apkSha256 = "0".repeat(64),
            apkSize = 2L * 1024 * 1024,
        ),
        apkAsset = ForgejoAsset("update.apk", "https://example.test/apk", 1, null),
        checksumAsset = ForgejoAsset("update.apk.sha256", "https://example.test/sha", 1, null),
    )

class UpdateBannerPresenterTest {
    private val candidate = testCandidate("0.5.2")

    @Test
    fun `ready shows an install button`() {
        val ui = presentBanner(UpdateState.Ready(candidate), dismissedVersionCode = 0)!!
        assertEquals("Обновление 0.5.2 готово", ui.text)
        assertEquals(BannerAction.Install, ui.action)
        assertTrue(ui.dismissible)
        // Состояние «нужно подтверждение» выглядит так же: нажатие откроет подтверждение Android.
        assertEquals(ui, presentBanner(UpdateState.Ready(candidate, needsConfirmation = true), 0))
    }

    @Test
    fun `downloading shows sizes and progress without a button`() {
        val ui = presentBanner(UpdateState.Downloading(candidate, 1_000, 4_000, savedBytes = 99), 0)!!
        assertEquals("Загружается обновление 0.5.2 · 1000 Б из 3.9 КБ", ui.text)
        assertEquals(0.25f, ui.progress!!, 0.0001f)
        assertNull(ui.action)
    }

    @Test
    fun `available offers the download for beta and metered networks`() {
        val ui = presentBanner(UpdateState.Available(candidate), 0)!!
        assertEquals(BannerAction.Download, ui.action)
    }

    @Test
    fun `dismissal hides the banner for that version only and never hides the install`() {
        val code = candidate.metadata.versionCode
        assertNull(presentBanner(UpdateState.Ready(candidate), dismissedVersionCode = code))
        assertNull(presentBanner(UpdateState.Downloading(candidate, 1, 2), dismissedVersionCode = code))
        assertEquals(
            "Установка видна всегда",
            false,
            presentBanner(UpdateState.Installing(candidate), dismissedVersionCode = code)!!.dismissible,
        )
        assertFalse(presentBanner(UpdateState.Ready(candidate), dismissedVersionCode = code - 1) == null)
    }

    @Test
    fun `nothing else produces a banner`() {
        listOf(
            UpdateState.Idle,
            UpdateState.Checking("Stable"),
            UpdateState.UpToDate("v1", "1"),
            UpdateState.Failure("x", candidate),
        ).forEach { assertNull(presentBanner(it, 0)) }
    }
}
