package io.github.zapretkvn.android.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.core.designsystem.SettingsRow
import io.github.zapretkvn.core.designsystem.SwitchRow
import io.github.zapretkvn.core.designsystem.ZapretIcons

/** «Дополнительно»: то, что нужно редко, но не должно быть спрятано совсем. */
@Composable
internal fun AdvancedRoute(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    val hidingSummary = vpnHidingSummary(settings.vpnHiding)
    SettingsPageList(padding, tag = "settings-page-advanced") {
        item(key = "pages", contentType = "rows") {
            RowsCard {
                SettingsRow(
                    title = SettingsPage.Hiding.title,
                    subtitle = hidingSummary,
                    icon = ZapretIcons.Shield,
                    onClick = { nav.openSettingsPage(SettingsPage.Hiding.id) },
                )
                RowDivider()
                SettingsRow(
                    title = SettingsPage.Diagnostics.title,
                    subtitle = "Состояние VPN, этапы подключения, версии и логи",
                    icon = ZapretIcons.Bug,
                    onClick = { nav.openSettingsPage(SettingsPage.Diagnostics.id) },
                )
            }
        }
        item(key = "editor", contentType = "rows") {
            RowsCard {
                SwitchRow(
                    title = "Перенос строк в редакторе JSON",
                    subtitle = "Только вид редактора; профиль не меняется.",
                    icon = ZapretIcons.Code,
                    checked = settings.rawEditorLineWrap,
                    onCheckedChange = deps.profiles.settings::setRawEditorLineWrap,
                    switchDescription = "Перенос строк в редакторе JSON",
                )
            }
        }
    }
}
