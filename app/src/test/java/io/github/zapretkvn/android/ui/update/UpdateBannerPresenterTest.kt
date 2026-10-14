package io.github.zapretkvn.android.ui.update

import io.github.zapretkvn.android.ui.settings.testCandidate
import io.github.zapretkvn.android.updates.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
