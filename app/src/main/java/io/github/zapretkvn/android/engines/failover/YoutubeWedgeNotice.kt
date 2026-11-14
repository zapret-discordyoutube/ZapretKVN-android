package io.github.zapretkvn.android.engines.failover

/**
 * What to tell the user when a "slow YouTube" episode ended without bringing the video back (owner decision
 * 2026-11-14, after the second measured ReVanced wedge).
 *
 * Field (2026-11-14, 16:48 to 16:59 UTC): the player held links of YouTube clusters that never answer. Two episodes
 * moved the YouTube slot to other servers and found no new video flows to judge, so each ended with the old server
 * restored; the video stayed at 0:10 until the client was force-stopped, then another video played at once. The app
 * cannot close another app, so it asks the user to do the one tap that works.
 *
 * Only the two outcomes that mean "the switch did not help" produce a notice. A switch that worked, one that is
 * unverified (it already says "restart the video"), a failed command and "no spare server" do not. A notice is shown
 * at most once per [MIN_GAP_MILLIS]: episodes already have their own cooldown, this keeps a stuck video from nagging.
 */
internal object YoutubeWedgeNotice {
    const val MIN_GAP_MILLIS = 10 * 60_000L

    data class Notice(val message: String, val detail: String)

    fun decide(outcome: YoutubeSwitchOutcome, lastShownAtMillis: Long?, nowMillis: Long): Notice? {
        val didNotHelp = outcome == YoutubeSwitchOutcome.Inconclusive || outcome == YoutubeSwitchOutcome.AllCandidatesSlow
        if (!didNotHelp) return null
        if (lastShownAtMillis != null && nowMillis - lastShownAtMillis < MIN_GAP_MILLIS) return null
        return Notice(
            message = "YouTube завис, и смена сервера не помогла. Закройте YouTube (смахните из списка приложений) " +
                "и откройте видео заново: плеер держит ссылки, которые не отвечают.",
            detail = "YouTube завис: закройте его и откройте видео заново",
        )
    }
}
