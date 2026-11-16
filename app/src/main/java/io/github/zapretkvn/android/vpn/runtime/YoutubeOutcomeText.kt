package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.engines.failover.YoutubeSwitchOutcome

/**
 * The end of a "slow YouTube" episode in the runtime log. [slotPlace] is where the YouTube slot sits after the episode
 * (null without a slot). The slot host never goes back to the server the video starved on, so "the previous server
 * returned" is not always true there (field 2026-11-16 13:02 UTC: the log said so while the slot stayed on the trial
 * server): with a slot the line names the real place instead. [name] turns a server id into its display name.
 */
internal fun youtubeOutcomeText(outcome: YoutubeSwitchOutcome, slotPlace: String?, name: (String) -> String): String =
    when (outcome) {
        YoutubeSwitchOutcome.Gone -> "сессия закончилась"
        YoutubeSwitchOutcome.NoCandidates -> "запасного сервера нет"
        is YoutubeSwitchOutcome.Switched -> "выбран ${name(outcome.serverId)}"
        is YoutubeSwitchOutcome.SwitchedUnverified -> "выбран ${name(outcome.serverId)}, перезапустите видео"
        YoutubeSwitchOutcome.Inconclusive ->
            slotPlace?.let { "видео не пошло, слот YouTube сейчас на ${name(it)}" } ?: "видео не пошло, прежний сервер возвращён"
        YoutubeSwitchOutcome.AllCandidatesSlow ->
            slotPlace?.let { "запасные серверы тоже медленные, слот YouTube сейчас на ${name(it)}" }
                ?: "запасные серверы тоже медленные, прежний возвращён"
        is YoutubeSwitchOutcome.StarvesEverywhere ->
            "видео голодает на всех ${outcome.servers} проверенных серверах, отметки сняты" +
                (outcome.movedTo?.let { ", слот перешёл на ${name(it)}, где видео недавно шло быстро" } ?: "")
        YoutubeSwitchOutcome.TrialFailed -> "переключение не удалось"
        YoutubeSwitchOutcome.CommitRefused -> "выбор не сохранён, прежний сервер возвращён"
    }
