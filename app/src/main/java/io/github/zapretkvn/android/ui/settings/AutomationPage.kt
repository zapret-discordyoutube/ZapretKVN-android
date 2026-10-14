package io.github.zapretkvn.android.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.zapretkvn.android.network.CurrentWifiSsidReader
import io.github.zapretkvn.android.network.NetworkAutomationSettings
import io.github.zapretkvn.android.network.TrustedWifiName
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.NetworkTransportSetting
import io.github.zapretkvn.core.designsystem.CardTitle
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.SwitchRow
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.toneColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Сторона Android: проверка разрешения и чтение имени текущей сети. */
@Composable
internal fun AutomationRoute(deps: AppDependencies, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var readingCurrentWifi by remember { mutableStateOf(false) }
    var wifiMessage by remember { mutableStateOf<WifiMessage?>(null) }
    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    fun addCurrentWifi() {
        scope.launch {
            readingCurrentWifi = true
            wifiMessage = null
            val ssid = withContext(Dispatchers.IO) {
                runCatching { CurrentWifiSsidReader(context).read() }.getOrNull()
            }
            wifiMessage = if (ssid == null) {
                WifiMessage.nameUnavailable()
            } else {
                deps.profiles.settings.addTrustedWifi(ssid)
                WifiMessage.added(ssid)
            }
            readingCurrentWifi = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationPermissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (locationPermissionGranted) addCurrentWifi() else wifiMessage = WifiMessage.permissionDenied()
    }

    AutomationScreen(
        contentPadding = padding,
        settings = settings.networkAutomation,
        readingCurrentWifi = readingCurrentWifi,
        wifiMessage = wifiMessage,
        onEnabledChange = deps.profiles.settings::setNetworkAutomationEnabled,
        onTransportChange = deps.profiles.settings::setUseVpnOnNetwork,
        onPauseOnTrustedChange = deps.profiles.settings::setPauseOnTrustedWifi,
        onAddCurrentWifi = {
            if (locationPermissionGranted) {
                addCurrentWifi()
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            }
        },
        onAddWifi = deps.profiles.settings::addTrustedWifi,
        onRemoveWifi = deps.profiles.settings::removeTrustedWifi,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutomationScreen(
    contentPadding: PaddingValues,
    settings: NetworkAutomationSettings,
    readingCurrentWifi: Boolean,
    wifiMessage: WifiMessage?,
    onEnabledChange: (Boolean) -> Unit,
    onTransportChange: (NetworkTransportSetting, Boolean) -> Unit,
    onPauseOnTrustedChange: (Boolean) -> Unit,
    onAddCurrentWifi: () -> Unit,
    onAddWifi: (String) -> Unit,
    onRemoveWifi: (String) -> Unit,
) {
    var editWifiName by rememberSaveable { mutableStateOf(false) }
    if (editWifiName) {
        TrustedWifiDialog(
            onDismiss = { editWifiName = false },
            onSave = { ssid ->
                onAddWifi(ssid)
                editWifiName = false
            },
        )
    }
    val trusted = remember(settings.trustedWifiSsids) { settings.trustedWifiSsids.sorted() }

    SettingsPageList(contentPadding, tag = "settings-page-automation") {
        item(key = "master", contentType = "card") {
            ZapretCard(contentPadding = PaddingValues(bottom = Spacing.l), verticalSpacing = 0.dp) {
                SwitchRow(
                    title = "Управлять VPN по сети",
                    subtitle = if (settings.enabled) "Включено" else "Выключено",
                    icon = ZapretIcons.Wifi,
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("network-automation-enabled"),
                    switchDescription = "Управлять VPN по сети",
                )
                HintText(
                    "После ручного включения Zapret KVN сам ставит VPN на паузу в ненужных сетях " +
                        "и возобновляет последний профиль. Ручная остановка полностью выключает " +
                        "автоматику. На паузе работает только уведомление без туннеля; " +
                        "периодических проверок и WakeLock нет.",
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
            }
        }
        item(key = "networks", contentType = "card") {
            ZapretCard(contentPadding = PaddingValues(top = Spacing.l, bottom = Spacing.xs), verticalSpacing = 0.dp) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    CardTitle("Где использовать VPN")
                    HintText(
                        "Оставьте нужные типы сетей включёнными. Например, для режима " +
                            "«только мобильная сеть» выключите Wi‑Fi.",
                    )
                }
                NetworkSwitch(
                    "Wi‑Fi", "Любые беспроводные сети, кроме доверенных ниже",
                    settings.useVpnOnWifi, settings.enabled, "network-automation-wifi",
                ) { onTransportChange(NetworkTransportSetting.Wifi, it) }
                RowDivider()
                NetworkSwitch(
                    "Мобильная сеть", "LTE и 5G",
                    settings.useVpnOnCellular, settings.enabled, "network-automation-cellular",
                ) { onTransportChange(NetworkTransportSetting.Cellular, it) }
                RowDivider()
                NetworkSwitch(
                    "Ethernet", "Проводная сеть через адаптер или док-станцию",
                    settings.useVpnOnEthernet, settings.enabled, "network-automation-ethernet",
                ) { onTransportChange(NetworkTransportSetting.Ethernet, it) }
                RowDivider()
                NetworkSwitch(
                    "Другие сети", "Подключения неизвестного Android типа",
                    settings.useVpnOnOther, settings.enabled, "network-automation-other",
                ) { onTransportChange(NetworkTransportSetting.Other, it) }
            }
        }
        item(key = "trusted", contentType = "card") {
            ZapretCard(contentPadding = PaddingValues(top = Spacing.l, bottom = Spacing.s), verticalSpacing = 0.dp) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    CardTitle("Доверенные Wi‑Fi")
                    HintText(
                        "В доверенной сети (например, домашней) VPN встаёт на паузу. Имена сетей " +
                            "хранятся только на устройстве и не попадают в диагностику. Если Android " +
                            "скрыл имя сети, правило не срабатывает и VPN остаётся включённым.",
                    )
                }
                SwitchRow(
                    title = "Пауза в доверенных сетях",
                    subtitle = "Список можно временно выключить, не удаляя сети",
                    checked = settings.pauseOnTrustedWifi,
                    enabled = settings.enabled && settings.useVpnOnWifi,
                    onCheckedChange = onPauseOnTrustedChange,
                    modifier = Modifier.testTag("network-automation-trusted-enabled"),
                    switchDescription = "Пауза в доверенных сетях",
                )
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        Button(
                            enabled = !readingCurrentWifi,
                            onClick = onAddCurrentWifi,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("trusted-wifi-add-current"),
                        ) {
                            if (readingCurrentWifi) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = Spacing.s),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text("Добавить текущую сеть")
                        }
                        OutlinedButton(
                            onClick = { editWifiName = true },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("trusted-wifi-add-manual"),
                        ) { Text("Ввести имя") }
                    }
                    HintText(LOCATION_PERMISSION_EXPLANATION)
                    wifiMessage?.let { message ->
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = toneColor(
                                if (message.kind == WifiMessageKind.Success) Tone.Good else Tone.Bad,
                            ),
                        )
                    }
                }
                if (trusted.isEmpty()) {
                    Text(
                        "Доверенных сетей пока нет.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                    )
                } else {
                    trusted.forEach { ssid ->
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = Spacing.l),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(start = Spacing.l, end = Spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ssid,
                                modifier = Modifier.weight(1f).padding(vertical = Spacing.s),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            TextButton(onClick = { onRemoveWifi(ssid) }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    SwitchRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        enabled = enabled,
        onCheckedChange = onChange,
        modifier = Modifier.testTag(tag),
        switchDescription = title,
    )
}

@Composable
private fun TrustedWifiDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    val normalized = remember(value) { TrustedWifiName.normalize(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить доверенный Wi‑Fi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Имя сети (SSID)") },
                    singleLine = true,
                    isError = value.isNotEmpty() && normalized == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                HintText("Имя чувствительно к регистру. Пароль Wi‑Fi не нужен и не сохраняется.")
            }
        },
        confirmButton = {
            TextButton(enabled = normalized != null, onClick = { normalized?.let(onSave) }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
