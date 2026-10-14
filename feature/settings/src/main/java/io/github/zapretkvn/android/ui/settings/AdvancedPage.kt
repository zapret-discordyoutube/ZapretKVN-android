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
    onOpenPage: (SettingsPage) -> Unit,
    onRawEditorLineWrapChange: (Boolean) -> Unit,
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
