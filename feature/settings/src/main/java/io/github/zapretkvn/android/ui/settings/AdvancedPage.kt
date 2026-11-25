package io.github.zapretkvn.android.ui.settings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import io.github.zapretkvn.core.designsystem.SettingsRow
import io.github.zapretkvn.core.designsystem.SwitchRow
import io.github.zapretkvn.core.designsystem.ZapretIcons

/** «Дополнительно»: то, что нужно редко, но не должно быть спрятано совсем. */
@Composable
fun AdvancedScreen(
    contentPadding: PaddingValues,
    hidingSummary: String,
    rawEditorLineWrap: Boolean,
    serverIpv6First: Boolean,
    warpEntryHop: Boolean,
    blockQuic: Boolean,
    resetStalledFlows: Boolean,
    youtubeSwitch: Boolean,
    serviceSlots: Boolean,
    onOpenPage: (SettingsPage) -> Unit,
    onRawEditorLineWrapChange: (Boolean) -> Unit,
    onServerIpv6FirstChange: (Boolean) -> Unit,
    onWarpEntryHopChange: (Boolean) -> Unit,
    onBlockQuicChange: (Boolean) -> Unit,
    onResetStalledFlowsChange: (Boolean) -> Unit,
    onYoutubeSwitchChange: (Boolean) -> Unit,
    onServiceSlotsChange: (Boolean) -> Unit,
    /** Подсказка с карточки ошибки подключения: прокрутить к строке WARP и подсветить её (владелец 2026-11-26). */
    highlightWarp: Boolean = false,
    onWarpHighlightHandled: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val spotlight = highlightWarp && !warpEntryHop
    LaunchedEffect(spotlight) {
        if (spotlight) {
            listState.animateScrollToItem(WARP_ITEM_INDEX)
            delay(WARP_SPOTLIGHT_MS)
            onWarpHighlightHandled()
        }
    }
    // Включили вход (или ушли с подсказки): подсветка своё отработала.
    LaunchedEffect(warpEntryHop, highlightWarp) {
        if (warpEntryHop && highlightWarp) onWarpHighlightHandled()
    }
    SettingsPageList(contentPadding, tag = "settings-page-advanced", state = listState) {
        item(key = "pages", contentType = "rows") {
            RowsCard {
                SettingsRow(
                    title = SettingsPage.Hiding.title,
                    subtitle = hidingSummary,
                    icon = ZapretIcons.Shield,
                    onClick = { onOpenPage(SettingsPage.Hiding) },
                )
                RowDivider()
                SettingsRow(
                    title = SettingsPage.Diagnostics.title,
                    subtitle = "Состояние VPN, этапы подключения, версии и логи",
                    icon = ZapretIcons.Bug,
                    onClick = { onOpenPage(SettingsPage.Diagnostics) },
                )
            }
        }
        item(key = "server-ipv6", contentType = "rows") {
            RowsCard {
                SwitchRow(
                    title = "IPv6 к серверу первым",
                    subtitle = "Если у сети есть IPv6, подключение к серверу начинается по нему, а IPv4 подхватывает его за доли " +
                        "секунды. Помогает, когда IPv4-адрес сервера заблокирован. Переключение перезапускает VPN.",
                    icon = ZapretIcons.Shield,
                    checked = serverIpv6First,
                    onCheckedChange = onServerIpv6FirstChange,
                    switchDescription = "IPv6 к серверу первым",
                )
            }
        }
        item(key = "warp-entry", contentType = "rows") {
            val pulse by rememberInfiniteTransition(label = "warp-spotlight").animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "warp-spotlight-alpha",
            )
            RowsCard(
                modifier = if (spotlight) {
                    Modifier
                        .testTag("warp-entry-spotlight")
                        .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = pulse), MaterialTheme.shapes.medium)
                } else {
                    Modifier
                },
            ) {
                SwitchRow(
                    title = "Вход через Cloudflare WARP",
                    subtitle = "Сначала соединение идёт к Cloudflare WARP, а уже оттуда к вашему серверу: провайдер видит " +
                        "только Cloudflare, а не адрес сервера. WARP служит только входом, выходом остаётся ваш сервер. " +
                        "Переключение перезапускает VPN.",
                    icon = ZapretIcons.Shield,
                    checked = warpEntryHop,
                    onCheckedChange = onWarpEntryHopChange,
                    switchDescription = "Вход через Cloudflare WARP",
                )
            }
            if (spotlight) {
                Text(
                    "Нажмите на переключатель, чтобы включить вход через WARP, и подключитесь снова.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("warp-entry-spotlight-hint").padding(top = 8.dp),
                )
            }
        }
        item(key = "quic", contentType = "rows") {
            RowsCard {
                SwitchRow(
                    title = "Блокировать QUIC (UDP 443)",
                    subtitle = "YouTube и браузеры сразу переходят на TCP: через VPN видео стартует быстрее и идёт ровнее. " +
                        "Применяется при следующем подключении.",
                    icon = ZapretIcons.Shield,
                    checked = blockQuic,
                    onCheckedChange = onBlockQuicChange,
                    switchDescription = "Блокировать QUIC (UDP 443)",
                )
                RowDivider()
                SwitchRow(
                    title = "Сбрасывать зависшие соединения YouTube",
                    subtitle = "Если видео-сервер YouTube принял запрос, но за 8 секунд почти ничего не прислал, соединение " +
                        "закрывается сразу, и плеер пробует снова, не дожидаясь своего долгого таймаута. Со следующего подключения.",
                    icon = ZapretIcons.Shield,
                    checked = resetStalledFlows,
                    onCheckedChange = onResetStalledFlowsChange,
                    switchDescription = "Сбрасывать зависшие соединения YouTube",
                )
                RowDivider()
                SwitchRow(
                    title = "Менять сервер при медленном YouTube",
                    subtitle = "Если видео голодает на текущем сервере, приложение пробует другой сервер профиля и оставляет его, " +
                        "когда видео там пошло; иначе возвращает прежний. Сервер может сменить страну выхода. Выключено по умолчанию.",
                    icon = ZapretIcons.Shield,
                    checked = youtubeSwitch,
                    onCheckedChange = onYoutubeSwitchChange,
                    switchDescription = "Менять сервер при медленном YouTube",
                )
                RowDivider()
                SwitchRow(
                    title = "Раздельные серверы (бета)",
                    subtitle = "У YouTube, Telegram и ИИ-приложений (ChatGPT, Claude, Gemini) свой сервер из профиля, " +
                        "остальные приложения идут через основной. Приложения вне VPN остаются напрямую. " +
                        "Включение перезапускает VPN. Выключено — один общий сервер, как раньше.",
                    icon = ZapretIcons.Shield,
                    checked = serviceSlots,
                    onCheckedChange = onServiceSlotsChange,
                    switchDescription = "Раздельные серверы",
                )
            }
        }
        item(key = "editor", contentType = "rows") {
            RowsCard {
                SwitchRow(
                    title = "Перенос строк в редакторе JSON",
                    subtitle = "Только вид редактора; профиль не меняется.",
                    icon = ZapretIcons.Code,
                    checked = rawEditorLineWrap,
                    onCheckedChange = onRawEditorLineWrapChange,
                    switchDescription = "Перенос строк в редакторе JSON",
                )
            }
        }
    }
}

/** Позиция строки WARP в списке «Дополнительно» (страницы, IPv6, WARP): к ней прокручивает подсказка. */
internal const val WARP_ITEM_INDEX = 2

/** Сколько подсветка держится, если переключатель так и не нажали. */
private const val WARP_SPOTLIGHT_MS = 12_000L
