package io.github.zapretkvn.android.ui.settings

import io.github.zapretkvn.android.updates.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `checking is a single quiet busy state`() {
        val checking = presentUpdate(UpdateState.Checking("Stable")) as UpdateSectionUi.Busy
        assertEquals("Проверяем обновления…", checking.message)
        // Ни одного состояния, где говорится про VPN или «не отвечает напрямую».
        assertFalse("VPN" in checking.message)
    }

    @Test
    fun `available state exposes version details and notes`() {
        val ui = presentUpdate(UpdateState.Available(candidate)) as UpdateSectionUi.Available
        assertEquals("1.5.0", ui.release.versionName)
        assertEquals("Ядро v1.14.1 · arm64-v8a · 2.0 МБ", ui.release.details)
        assertEquals("### Что нового\n\n- быстрее", ui.release.notes)
    }

    @Test
    fun `downloading reports determinate progress and the saving`() {
        val ui = presentUpdate(
            UpdateState.Downloading(candidate, downloadedBytes = 512, totalBytes = 2048),
        ) as UpdateSectionUi.Downloading
        assertEquals(0.25f, ui.progress!!, 0.0001f)
        assertEquals("Загружается 512 Б из 2.0 КБ", ui.progressLabel)
        assertEquals("", ui.savingLabel)

        val delta = presentUpdate(
            UpdateState.Downloading(candidate, 1_200_000, 3_100_000, savedBytes = 114L * 1024 * 1024),
        ) as UpdateSectionUi.Downloading
        assertTrue(delta.savingLabel.startsWith("экономия 114"))
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
    fun `ready keeps the confirmation flag and installing is its own state`() {
        assertEquals(UpdateSectionUi.Ready(releaseOf(), false), presentUpdate(UpdateState.Ready(candidate)))
        assertEquals(
            UpdateSectionUi.Ready(releaseOf(), true),
            presentUpdate(UpdateState.Ready(candidate, needsConfirmation = true)),
        )
        assertTrue(presentUpdate(UpdateState.Installing(candidate)) is UpdateSectionUi.Installing)
    }

    @Test
    fun `failure keeps what the buttons need`() {
        assertEquals(
            UpdateSectionUi.Failure("Нет сети", canRetryDownload = false),
            presentUpdate(UpdateState.Failure("Нет сети")),
        )
        assertEquals(
            UpdateSectionUi.Failure("Обрыв", canRetryDownload = true),
            presentUpdate(UpdateState.Failure("Обрыв", candidate)),
        )
    }

    private fun releaseOf() = (presentUpdate(UpdateState.Available(candidate)) as UpdateSectionUi.Available).release
}
