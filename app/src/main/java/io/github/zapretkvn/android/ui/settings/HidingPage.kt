package io.github.zapretkvn.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.hardening.TunMtuMode
import io.github.zapretkvn.android.hardening.VpnHidingOptions
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.core.designsystem.CardTitle
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.SwitchRow
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.toneColor

@Composable
internal fun HidingRoute(deps: AppDependencies, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    HidingScreen(
        contentPadding = padding,
        options = settings.vpnHiding,
        onBlockLocalEndpointsChange = deps.profiles.settings::setVpnHidingBlockLocalEndpoints,
        onNeutralSessionNameChange = deps.profiles.settings::setVpnHidingNeutralSessionName,
        onMtuModeChange = deps.profiles.settings::setVpnHidingTunMtuMode,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HidingScreen(
    contentPadding: PaddingValues,
    options: VpnHidingOptions,
    onBlockLocalEndpointsChange: (Boolean) -> Unit,
    onNeutralSessionNameChange: (Boolean) -> Unit,
    onMtuModeChange: (TunMtuMode) -> Unit,
) {
    SettingsPageList(contentPadding, tag = "settings-page-hiding") {
        item(key = "intro", contentType = "card") {
            ZapretCard {
                CardTitle("Возможности rootless-режима", icon = ZapretIcons.Shield)
                Text(
                    "Делает VPN менее заметным для других приложений.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                HintText(
                    "Защита закрывает локальные прокси и API и убирает технические признаки. " +
                        "Обычное приложение не может скрыть сам факт VPN-подключения и " +
                        "TUN-интерфейс — это делает Android. Фонового сканирования, таймеров и " +
                        "отдельных процессов здесь нет.",
                )
            }
        }

        item(key = "endpoints", contentType = "card") {
            ZapretCard(contentPadding = PaddingValues(bottom = Spacing.m), verticalSpacing = 0.dp) {
                SwitchRow(
                    title = "Закрыть локальные порты",
                    subtitle = "Защита от программ, которые проверяют localhost: убирает Clash/V2Ray API " +
                        "и дополнительные SOCKS/HTTP-входы из запущенного ядра.",
                    checked = options.blockLocalEndpoints,
                    onCheckedChange = onBlockLocalEndpointsChange,
                    modifier = Modifier.testTag("vpn-hiding-local-endpoints"),
                    switchDescription = "Блокировать localhost endpoints",
                )
                if (!options.blockLocalEndpoints) {
                    Text(
                        "Защита выключена: JSON профиля сможет открыть локальный порт для других приложений.",
                        style = MaterialTheme.typography.bodySmall,
                        color = toneColor(Tone.Bad),
                        modifier = Modifier.padding(horizontal = Spacing.l),
                    )
                }
            }
        }

        item(key = "session", contentType = "card") {
            RowsCard {
                SwitchRow(
                    title = "Нейтральное имя VPN-сессии",
                    subtitle = "Android показывает «Системная сеть» вместо названия приложения " +
                        "там, где это возможно.",
                    checked = options.neutralSessionName,
                    onCheckedChange = onNeutralSessionNameChange,
                    modifier = Modifier.testTag("vpn-hiding-session-name"),
                    switchDescription = "Нейтральное имя VPN-сессии",
                )
            }
        }

        item(key = "mtu", contentType = "card") {
            ZapretCard {
                CardTitle("MTU туннеля")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    TunMtuMode.entries.forEach { mode ->
                        FilterChip(
                            selected = options.tunMtuMode == mode,
                            onClick = { onMtuModeChange(mode) },
                            label = { Text(mode.title()) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("vpn-hiding-mtu-${mode.name}"),
                        )
                    }
                }
                HintText(options.tunMtuMode.description())
            }
        }

        item(key = "footer", contentType = "text") {
            HintText(
                "Изменение параметров контролируемо перезапускает активное подключение. " +
                    "Сохранённый JSON профиля не переписывается.",
            )
        }
    }
}
