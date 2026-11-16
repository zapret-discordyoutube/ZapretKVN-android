package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.engines.failover.YoutubeSwitchOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeOutcomeTextTest {
    private val name: (String) -> String = { "<$it>" }

    @Test
    fun `with a slot the line names where the slot really sits`() {
        // Field 2026-11-16 13:02 UTC: the trial on 4vusa stayed silent, the slot host kept the slot off the starved
        // Rusina, and the log claimed the previous server was back.
        assertEquals(
            "видео не пошло, слот YouTube сейчас на <4vusa>",
            youtubeOutcomeText(YoutubeSwitchOutcome.Inconclusive, "4vusa", name),
        )
        assertEquals(
            "запасные серверы тоже медленные, слот YouTube сейчас на <Hosfinano>",
            youtubeOutcomeText(YoutubeSwitchOutcome.AllCandidatesSlow, "Hosfinano", name),
        )
    }

    @Test
    fun `without a slot the previous server really returns`() {
        assertEquals("видео не пошло, прежний сервер возвращён", youtubeOutcomeText(YoutubeSwitchOutcome.Inconclusive, null, name))
        assertEquals(
            "запасные серверы тоже медленные, прежний возвращён",
            youtubeOutcomeText(YoutubeSwitchOutcome.AllCandidatesSlow, null, name),
        )
        assertEquals("выбран <x>", youtubeOutcomeText(YoutubeSwitchOutcome.Switched("x"), null, name))
    }
}
