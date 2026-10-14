package io.github.zapretkvn.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.DnsOverride
import io.github.zapretkvn.core.designsystem.CardTitle
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.SwitchRow
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DnsScreen(
    contentPadding: PaddingValues,
    dnsMode: DnsMode,
    ipv4Only: Boolean,
    dnsOverride: DnsOverride,
    cacheClearRequested: Boolean,
    onDnsModeChange: (DnsMode) -> Unit,
    onIpv4OnlyChange: (Boolean) -> Unit,
    onOverrideEnabledChange: (Boolean) -> Unit,
    onOverrideSave: (String, String) -> Unit,
    onClearCache: () -> Unit,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var editOverride by rememberSaveable { mutableStateOf(false) }
    if (editOverride) {
        DnsOverrideDialog(
            current = dnsOverride,
            onDismiss = { editOverride = false },
            onSave = { hostname, ipv4Address ->
                onOverrideSave(hostname, ipv4Address)
                editOverride = false
            },
        )
    }

    SettingsPageList(contentPadding, tag = "settings-page-dns") {
        item(key = "mode", contentType = "card") {
            ZapretCard {
                CardTitle("Откуда брать адреса сайтов", icon = ZapretIcons.Globe)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    DnsMode.entries.forEach { mode ->
                        FilterChip(
                            selected = dnsMode == mode,
                            onClick = { onDnsModeChange(mode) },
                            label = { Text(mode.title()) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                Text(dnsMode.description(), style = MaterialTheme.typography.bodyMedium)
                HintText("Перехватывается TCP/UDP 53; встроенный DoH, DoT и mDNS не перехватываются.")
            }
        }

        item(key = "ipv4", contentType = "card") {
            RowsCard {
                SwitchRow(
                    title = "Только IPv4 через VPN",
                    subtitle = ipv4OnlyHint(dnsMode),
                    checked = ipv4Only,
                    onCheckedChange = onIpv4OnlyChange,
                    enabled = dnsMode != DnsMode.FromJson,
                    switchDescription = "Только IPv4 через VPN",
                )
            }
        }

        item(key = "cache", contentType = "card") {
            ZapretCard {
                CardTitle("DNS-кэш")
                HintText(
                    "Сбрасывает сохранённые адреса серверов и перезапускает ядро VPN. " +
                        "Кэш самого Android не очищается.",
                )
                OutlinedButton(
                    onClick = onClearCache,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Очистить DNS-кэш") }
                if (cacheClearRequested) {
                    HintText("Запрос отправлен. Если VPN включён, он перезапустится сам.")
                }
            }
        }

        item(key = "advanced", contentType = "collapsible") {
            CollapsibleCard(
                title = "Дополнительно",
                summary = "Переопределение DNS для одного сайта",
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded },
                padContent = false,
            ) {
                SwitchRow(
                    title = "DNS-переопределение",
                    subtitle = "${dnsOverride.hostname} → ${dnsOverride.ipv4Address}",
                    checked = dnsOverride.enabled,
                    onCheckedChange = onOverrideEnabledChange,
                    switchDescription = "DNS-переопределение включено",
                )
                HintText(
                    dnsOverrideHint(dnsMode),
                    modifier = Modifier.padding(horizontal = Spacing.l),
                )
                OutlinedButton(
                    onClick = { editOverride = true },
                    modifier = Modifier
                        .padding(horizontal = Spacing.l)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("dns-override-edit"),
                ) { Text("Изменить DNS-переопределение") }
            }
        }
    }
}

@Composable
private fun DnsOverrideDialog(
    current: DnsOverride,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var hostname by rememberSaveable(current.hostname) { mutableStateOf(current.hostname) }
    var ipv4Address by rememberSaveable(current.ipv4Address) { mutableStateOf(current.ipv4Address) }
    val validationMessage = remember(hostname, ipv4Address) {
        DnsOverride.validationMessage(hostname, ipv4Address)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DNS-переопределение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it },
                    label = { Text("Точный домен") },
                    singleLine = true,
                    isError = DnsOverride.validationMessage(hostname, DnsOverride.DEFAULT_IPV4_ADDRESS) != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dns-override-hostname")
                        .semantics { contentDescription = "Домен DNS-переопределения" },
                )
                OutlinedTextField(
                    value = ipv4Address,
                    onValueChange = { ipv4Address = it },
                    label = { Text("IPv4-адрес") },
                    singleLine = true,
                    isError = DnsOverride.validationMessage(DnsOverride.DEFAULT_HOSTNAME, ipv4Address) != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dns-override-ipv4")
                        .semantics { contentDescription = "IPv4 DNS-переопределения" },
                )
                Text(
                    validationMessage ?: "HTTPS продолжит проверять исходное имя домена и его сертификат.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (validationMessage == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(hostname, ipv4Address) },
                enabled = validationMessage == null,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
