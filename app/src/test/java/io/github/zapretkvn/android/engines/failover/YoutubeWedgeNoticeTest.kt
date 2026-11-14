package io.github.zapretkvn.android.engines.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeWedgeNoticeTest {
    private val now = 1_000_000L

    @Test
    fun `the two outcomes where the switch did not bring the video back ask for a restart of YouTube`() {
        for (outcome in listOf(YoutubeSwitchOutcome.Inconclusive, YoutubeSwitchOutcome.AllCandidatesSlow)) {
            val notice = YoutubeWedgeNotice.decide(outcome, null, now)
            assertNotNull(outcome.toString(), notice)
            assertTrue(notice!!.message.contains("Закройте YouTube"))
            assertTrue(notice.detail.contains("откройте видео заново"))
        }
    }

    @Test
    fun `outcomes that worked, already say restart, or are not about the player stay silent`() {
        val quiet = listOf(
            YoutubeSwitchOutcome.Gone,
            YoutubeSwitchOutcome.NoCandidates,
            YoutubeSwitchOutcome.Switched("A"),
            YoutubeSwitchOutcome.SwitchedUnverified("A"),
            YoutubeSwitchOutcome.TrialFailed,
            YoutubeSwitchOutcome.CommitRefused,
        )
        quiet.forEach { assertNull(it.toString(), YoutubeWedgeNotice.decide(it, null, now)) }
    }

    @Test
    fun `a notice is not repeated within ten minutes but is shown again after that`() {
        val shown = now
        assertNull(YoutubeWedgeNotice.decide(YoutubeSwitchOutcome.Inconclusive, shown, shown + YoutubeWedgeNotice.MIN_GAP_MILLIS - 1))
        assertNotNull(YoutubeWedgeNotice.decide(YoutubeSwitchOutcome.Inconclusive, shown, shown + YoutubeWedgeNotice.MIN_GAP_MILLIS))
        assertEquals(10 * 60_000L, YoutubeWedgeNotice.MIN_GAP_MILLIS)
    }
}
