package io.github.zapretkvn.android.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import io.github.zapretkvn.core.designsystem.SettingsRow
import io.github.zapretkvn.core.designsystem.SwitchRow
import io.github.zapretkvn.core.designsystem.ZapretIcons

/** «Дополнительно»: то, что нужно редко, но не должно быть спрятано совсем. */
@Composable
fun AdvancedScreen(
    contentPadding: PaddingValues,
    hidingSummary: String,
    rawEditorLineWrap: Boolean,
    blockQuic: Boolean,
    resetStalledFlows: Boolean,
    youtubeSwitch: Boolean,
    serviceSlots: Boolean,
    onOpenPage: (SettingsPage) -> Unit,
    onRawEditorLineWrapChange: (Boolean) -> Unit,
    onBlockQuicChange: (Boolean) -> Unit,
    onResetStalledFlowsChange: (Boolean) -> Unit,
    onYoutubeSwitchChange: (Boolean) -> Unit,
    onServiceSlotsChange: (Boolean) -> Unit,
) {
    SettingsPageList(contentPadding, tag = "settings-page-advanced") {
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
