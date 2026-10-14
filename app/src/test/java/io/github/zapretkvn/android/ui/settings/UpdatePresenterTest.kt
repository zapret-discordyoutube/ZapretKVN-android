package io.github.zapretkvn.android.ui.settings

import io.github.zapretkvn.android.updates.UpdateOperation
import io.github.zapretkvn.android.updates.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePresenterTest {
    private val candidate = testCandidate()

    @Test
    fun `idle and up to date map to plain states`() {
        assertEquals(UpdateSectionUi.Idle, presentUpdate(UpdateState.Idle))
        assertEquals(
            UpdateSectionUi.UpToDate("У вас последняя версия: 1.4.2."),
            presentUpdate(UpdateState.UpToDate("v1.4.2", "1.4.2")),
        )
    }

    @Test
    fun `checking and retrying via vpn are both busy with different words`() {
        val checking = presentUpdate(UpdateState.Checking("Stable")) as UpdateSectionUi.Busy
        val retryCheck = presentUpdate(UpdateState.RetryingViaVpn(UpdateOperation.Check)) as UpdateSectionUi.Busy
        val retryDownload =
            presentUpdate(UpdateState.RetryingViaVpn(UpdateOperation.Download, candidate)) as UpdateSectionUi.Busy
        assertEquals(3, setOf(checking.message, retryCheck.message, retryDownload.message).size)
        assertTrue("VPN" in retryCheck.message)
    }

    @Test
    fun `available state exposes version details and notes`() {
        val ui = presentUpdate(UpdateState.Available(candidate)) as UpdateSectionUi.Available
        assertEquals("1.5.0", ui.release.versionName)
        assertEquals("Ядро v1.14.1 · arm64-v8a · 2.0 МБ", ui.release.details)
        assertEquals("### Что нового\n\n- быстрее", ui.release.notes)
    }

    @Test
    fun `downloading reports determinate progress`() {
        val ui = presentUpdate(UpdateState.Downloading(candidate, downloadedBytes = 512, totalBytes = 2048))
            as UpdateSectionUi.Downloading
        assertEquals(0.25f, ui.progress!!, 0.0001f)
        assertEquals("512 Б из 2.0 КБ · 25 %", ui.progressLabel)
    }

    @Test
    fun `downloading with unknown size is indeterminate and clamps overshoot`() {
        val unknown = presentUpdate(UpdateState.Downloading(candidate, 1024, 0)) as UpdateSectionUi.Downloading
        assertNull(unknown.progress)
        assertEquals("1.0 КБ", unknown.progressLabel)
        val over = presentUpdate(UpdateState.Downloading(candidate, 3000, 2000)) as UpdateSectionUi.Downloading
        assertEquals(1f, over.progress!!, 0.0001f)
    }

    @Test
    fun `ready and failure keep what the buttons need`() {
        assertTrue(presentUpdate(UpdateState.Ready(candidate)) is UpdateSectionUi.Ready)
        assertEquals(
            UpdateSectionUi.Failure("Нет сети", canRetryDownload = false),
            presentUpdate(UpdateState.Failure("Нет сети")),
        )
        assertEquals(
            UpdateSectionUi.Failure("Обрыв", canRetryDownload = true),
            presentUpdate(UpdateState.Failure("Обрыв", candidate)),
        )
    }
}
