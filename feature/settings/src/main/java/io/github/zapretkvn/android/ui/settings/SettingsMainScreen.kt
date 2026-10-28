package io.github.zapretkvn.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.ui.SubscriptionAutoUpdate
import io.github.zapretkvn.android.ui.ThemeMode
import io.github.zapretkvn.core.designsystem.SectionHeader
import io.github.zapretkvn.core.designsystem.SettingsRow
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.SwitchRow
import io.github.zapretkvn.core.designsystem.ZapretIcons
import kotlinx.coroutines.launch

/**
 * Главный список настроек. Строка = иконка, название, текущее значение
 * под названием. Прямо в списке только переключатели; остальное — на
 * отдельных страницах или в нижней шторке.
 */
@Composable
fun SettingsMainScreen(
    ui: SettingsMainUi,
    contentPadding: PaddingValues,
    onOpenPage: (SettingsPage) -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onSlowServerSwitchChange: (Boolean) -> Unit,
    onConnectOnLaunchChange: (Boolean) -> Unit = {},
    onHideServerAddressesChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onSubscriptionUpdateChange: (Int) -> Unit = {},
) {
    var themeSheetOpen by rememberSaveable { mutableStateOf(false) }
    if (themeSheetOpen) {
        ThemeSheet(
            selected = ui.themeMode,
            onSelected = onThemeSelected,
            onDismiss = { themeSheetOpen = false },
        )
    }
    var subscriptionSheetOpen by rememberSaveable { mutableStateOf(false) }
    if (subscriptionSheetOpen) {
        SubscriptionUpdateSheet(
            selected = ui.subscriptionUpdateHours,
            onSelected = onSubscriptionUpdateChange,
            onDismiss = { subscriptionSheetOpen = false },
        )
    }

    SettingsPageList(contentPadding, tag = "settings-list") {
        item(key = "h-connection", contentType = "header") { SectionHeader("ПОДКЛЮЧЕНИЕ") }
        item(key = "connection", contentType = "rows") {
            RowsCard {
                SettingsRow(
                    title = SettingsPage.Dns.title,
                    subtitle = ui.dnsSummary,
                    icon = ZapretIcons.Globe,
                    onClick = { onOpenPage(SettingsPage.Dns) },
                )
                RowDivider()
                SettingsRow(
                    title = SettingsPage.Automation.title,
                    subtitle = ui.automationSummary,
                    icon = ZapretIcons.Wifi,
                    onClick = { onOpenPage(SettingsPage.Automation) },
                )
                RowDivider()
                SettingsRow(
                    title = SettingsPage.Services.title,
                    subtitle = ui.servicesSummary,
                    icon = ZapretIcons.Globe,
                    onClick = { onOpenPage(SettingsPage.Services) },
                    modifier = Modifier.testTag("settings-services"),
                )
                RowDivider()
                SwitchRow(
                    title = "Автопереключение медленного сервера",
                    subtitle = "Если скорость долго низкая, переключимся на более быстрый сервер " +
                        "этого профиля. Не чаще 3 раз в час, текущие соединения при этом " +
                        "обрываются. Переход на резервный сервер при отказе работает всегда.",
                    icon = ZapretIcons.Bolt,
                    checked = ui.slowServerSwitch,
                    onCheckedChange = onSlowServerSwitchChange,
                    modifier = Modifier.testTag("slow-server-switch"),
                    switchDescription = "Переключать при низкой скорости",
                )
                RowDivider()
                SwitchRow(
                    title = "Подключаться при запуске",
                    subtitle = "Открыли приложение — VPN подключается к выбранному профилю сам. " +
                        "Выключено — подключение только кнопкой.",
                    icon = ZapretIcons.Power,
                    checked = ui.connectOnLaunch,
                    onCheckedChange = onConnectOnLaunchChange,
                    modifier = Modifier.testTag("connect-on-launch"),
                    switchDescription = "Подключаться при запуске",
                )
            }
        }

        item(key = "h-appearance", contentType = "header") { SectionHeader("ВНЕШНИЙ ВИД") }
        item(key = "appearance", contentType = "rows") {
            RowsCard {
                SettingsRow(
                    title = "Оформление",
                    subtitle = ui.themeSummary,
                    icon = ZapretIcons.Palette,
                    onClick = { themeSheetOpen = true },
                )
                RowDivider()
                SwitchRow(
                    title = "Цвета обоев",
                    subtitle = "Подстраивать цвета приложения под обои (Android 12+). " +
                        "Выключено — фирменная яркая палитра.",
                    icon = ZapretIcons.Palette,
                    checked = ui.dynamicColor,
                    onCheckedChange = onDynamicColorChange,
                    modifier = Modifier.testTag("dynamic-color"),
                    switchDescription = "Цвета обоев",
                )
                RowDivider()
                SwitchRow(
                    title = "Скрывать адреса серверов",
                    subtitle = "Адреса серверов и ссылки подписок не показываются нигде, в том числе на скриншотах.",
                    icon = ZapretIcons.Shield,
                    checked = ui.hideServerAddresses,
                    onCheckedChange = onHideServerAddressesChange,
                    modifier = Modifier.testTag("hide-server-addresses"),
                    switchDescription = "Скрывать адреса серверов",
                )
            }
        }

        item(key = "h-updates", contentType = "header") { SectionHeader("ОБНОВЛЕНИЯ") }
        item(key = "updates", contentType = "rows") {
            RowsCard {
                SettingsRow(
                    title = SettingsPage.Updates.title,
                    subtitle = ui.updateSummary,
                    icon = ZapretIcons.Download,
                    onClick = { onOpenPage(SettingsPage.Updates) },
                )
                RowDivider()
                SettingsRow(
                    title = "Автообновление подписок",
                    subtitle = "${SubscriptionAutoUpdate.title(ui.subscriptionUpdateHours)}. " +
                        "Новые серверы приходят сами, подключение не прерывается.",
                    icon = ZapretIcons.Refresh,
                    onClick = { subscriptionSheetOpen = true },
                    modifier = Modifier.testTag("subscription-auto-update"),
                )
            }
        }

        item(key = "h-community", contentType = "header") { SectionHeader("СООБЩЕСТВО") }
        item(key = "community", contentType = "rows") {
            RowsCard {
                SettingsRow(
                    title = SettingsPage.Community.title,
                    subtitle = "Telegram-каналы Zapret KVN",
                    icon = ZapretIcons.Chat,
                    onClick = { onOpenPage(SettingsPage.Community) },
                )
            }
        }

        item(key = "h-about", contentType = "header") { SectionHeader("О ПРИЛОЖЕНИИ") }
        item(key = "about", contentType = "rows") {
            RowsCard {
                SettingsRow(
                    title = SettingsPage.About.title,
                    subtitle = ui.aboutSummary,
                    icon = ZapretIcons.Info,
                    onClick = { onOpenPage(SettingsPage.About) },
                )
            }
        }

        item(key = "h-advanced", contentType = "header") { SectionHeader("ДОПОЛНИТЕЛЬНО") }
        item(key = "advanced", contentType = "rows") {
            RowsCard {
                SettingsRow(
                    title = SettingsPage.Advanced.title,
                    subtitle = ui.advancedSummary,
                    icon = ZapretIcons.Code,
                    onClick = { onOpenPage(SettingsPage.Advanced) },
                )
            }
        }
    }
}

@Composable
fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.l),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Автообновление подписок: как часто, или только вручную. Выбор закрывает шторку. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionUpdateSheet(
    selected: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = Spacing.xl)) {
            Text(
                "Автообновление подписок",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            )
            Text(
                "Список серверов подписки обновляется в фоне. Работающий VPN не перезапускается: " +
                    "новые серверы появятся сразу, а подключатся при следующем подключении.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
            SubscriptionAutoUpdate.CHOICES.forEach { hours ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(
                            selected = hours == selected,
                            role = Role.RadioButton,
                            onClick = {
                                onSelected(hours)
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                            },
                        )
                        .padding(horizontal = Spacing.l, vertical = Spacing.s)
                        .testTag("subscription-auto-update-$hours"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    RadioButton(selected = hours == selected, onClick = null)
                    Text(SubscriptionAutoUpdate.title(hours), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Выбор темы: четыре варианта в нижней шторке, выбор закрывает её. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSheet(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                "Тема приложения",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
            )
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(
                            selected = mode == selected,
                            role = Role.RadioButton,
                            onClick = {
                                onSelected(mode)
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                            },
                        )
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    RadioButton(selected = mode == selected, onClick = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mode.title(), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            mode.description(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
