package io.github.zapretkvn.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.zapretkvn.android.network.CurrentWifiSsidReader
import io.github.zapretkvn.android.network.NetworkAutomationSettings
import io.github.zapretkvn.android.network.TrustedWifiName
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NetworkAutomationSettingsScreen(
    contentPadding: PaddingValues,
    settings: NetworkAutomationSettings,
    viewModel: ProfilesViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editWifiName by rememberSaveable { mutableStateOf(false) }
    var readingCurrentWifi by remember { mutableStateOf(false) }
    var wifiMessage by remember { mutableStateOf<String?>(null) }
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
            if (ssid == null) {
                wifiMessage = "Android не открыл имя текущего Wi‑Fi. " +
                    "Проверьте точное местоположение и системную геолокацию."
            } else {
                viewModel.addTrustedWifi(ssid)
                wifiMessage = "Добавлен текущий Wi‑Fi: $ssid"
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
        if (locationPermissionGranted) {
            addCurrentWifi()
        } else {
            wifiMessage = "Без точного местоположения Android скрывает имя Wi‑Fi. " +
                "Доступно только общее правило для всех Wi‑Fi."
        }
    }

    if (editWifiName) {
        TrustedWifiDialog(
            onDismiss = { editWifiName = false },
            onSave = { ssid ->
                viewModel.addTrustedWifi(ssid)
                editWifiName = false
            },
        )
    }

    SettingsSubpage(contentPadding, "Автоматизация VPN", onBack) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NetworkRuleSwitch(
                    title = "Управлять VPN по сети",
                    subtitle = "После ручного включения KVN сам ставит туннель на паузу и возобновляет последний профиль.",
                    checked = settings.enabled,
                    onCheckedChange = viewModel::setNetworkAutomationEnabled,
                    testTag = "network-automation-enabled",
                )
                Text(
                    "Ручная остановка полностью выключает автоматику. На паузе работает только " +
                        "видимый foreground-сервис без TUN; периодических проверок и WakeLock нет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Где использовать KVN", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Оставьте нужные типы сетей включёнными. Например, для режима «только мобильная сеть» выключите Wi‑Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NetworkRuleSwitch(
                    title = "Wi‑Fi",
                    subtitle = "Любые беспроводные сети, кроме доверенных ниже",
                    checked = settings.useVpnOnWifi,
                    enabled = settings.enabled,
                    onCheckedChange = {
                        viewModel.setUseVpnOnNetwork(NetworkTransportSetting.Wifi, it)
                    },
                    testTag = "network-automation-wifi",
                )
                HorizontalDivider()
                NetworkRuleSwitch(
                    title = "Мобильная сеть",
                    subtitle = "Cellular / LTE / 5G",
                    checked = settings.useVpnOnCellular,
                    enabled = settings.enabled,
                    onCheckedChange = {
                        viewModel.setUseVpnOnNetwork(NetworkTransportSetting.Cellular, it)
                    },
                    testTag = "network-automation-cellular",
                )
                HorizontalDivider()
                NetworkRuleSwitch(
                    title = "Ethernet",
                    subtitle = "Проводная сеть через адаптер или док-станцию",
                    checked = settings.useVpnOnEthernet,
                    enabled = settings.enabled,
                    onCheckedChange = {
                        viewModel.setUseVpnOnNetwork(NetworkTransportSetting.Ethernet, it)
                    },
                    testTag = "network-automation-ethernet",
                )
                HorizontalDivider()
                NetworkRuleSwitch(
                    title = "Другие сети",
                    subtitle = "Неизвестный Android transport",
                    checked = settings.useVpnOnOther,
                    enabled = settings.enabled,
                    onCheckedChange = {
                        viewModel.setUseVpnOnNetwork(NetworkTransportSetting.Other, it)
                    },
                    testTag = "network-automation-other",
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Доверенные Wi‑Fi", style = MaterialTheme.typography.titleLarge)
                NetworkRuleSwitch(
                    title = "Пауза в доверенных сетях",
                    subtitle = "Список можно временно выключить, не удаляя сети",
                    checked = settings.pauseOnTrustedWifi,
                    enabled = settings.enabled && settings.useVpnOnWifi,
                    onCheckedChange = viewModel::setPauseOnTrustedWifi,
                    testTag = "network-automation-trusted-enabled",
                )
                Text(
                    "SSID хранится только локально и не попадает в диагностику. Если Android " +
                        "скрыл имя сети, правило не срабатывает и VPN остаётся включённым. " +
                        "Ручной ввод также требует разрешения для распознавания сети при подключении.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = !readingCurrentWifi,
                        onClick = {
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
                        modifier = Modifier.weight(1f).testTag("trusted-wifi-add-current"),
                    ) {
                        if (readingCurrentWifi) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text("Текущий Wi‑Fi")
                    }
                    OutlinedButton(
                        onClick = { editWifiName = true },
                        modifier = Modifier.weight(1f).testTag("trusted-wifi-add-manual"),
                    ) {
                        Text("Ввести имя")
                    }
                }
                wifiMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("Добавлен")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (settings.trustedWifiSsids.isEmpty()) {
                    Text(
                        "Доверенных сетей пока нет.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    settings.trustedWifiSsids.sorted().forEach { ssid ->
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ssid,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                            )
                            TextButton(onClick = { viewModel.removeTrustedWifi(ssid) }) {
                                Text("Удалить")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkRuleSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .testTag(testTag)
                .semantics { contentDescription = title },
        )
    }
}

@Composable
private fun TrustedWifiDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    val normalized = TrustedWifiName.normalize(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить доверенный Wi‑Fi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Имя сети (SSID)") },
                    singleLine = true,
                    isError = value.isNotEmpty() && normalized == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Имя чувствительно к регистру. Пароль Wi‑Fi не нужен и не сохраняется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalized != null,
                onClick = { normalized?.let(onSave) },
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

internal fun networkAutomationSummary(settings: NetworkAutomationSettings): String {
    if (!settings.enabled) return "Выключена · KVN работает как раньше"
    val enabled = buildList {
        if (settings.useVpnOnWifi) add("Wi‑Fi")
        if (settings.useVpnOnCellular) add("мобильная")
        if (settings.useVpnOnEthernet) add("Ethernet")
        if (settings.useVpnOnOther) add("другие")
    }
    val base = if (enabled.isEmpty()) "VPN везде на паузе" else "VPN: ${enabled.joinToString()}"
    val trusted = if (
        settings.useVpnOnWifi &&
        settings.pauseOnTrustedWifi &&
        settings.trustedWifiSsids.isNotEmpty()
    ) {
        " · доверенных Wi‑Fi: ${settings.trustedWifiSsids.size}"
    } else {
        ""
    }
    return base + trusted
}
