package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.importer.SubscriptionClientProfile
import io.github.zapretkvn.android.profiles.SubscriptionIdentityInput
import io.github.zapretkvn.android.profiles.SubscriptionSettingsState
import io.github.zapretkvn.android.ui.ScreenshotPrivacy

@Composable
internal fun UrlImportDialog(
    initialUrl: String,
    title: String = "Импорт по URL",
    hideServerAddresses: Boolean,
    onDismiss: () -> Unit,
    onImport: (String, SubscriptionIdentityInput) -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    // null — профиль ещё не выбран: его подскажет сама ссылка (happ://add и подобные).
    var clientProfile by rememberSaveable { mutableStateOf<SubscriptionClientProfile?>(null) }
    var sendHwid by rememberSaveable { mutableStateOf(false) }
    var hwid by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.take(4096) },
                    label = { Text("HTTPS URL подписки") },
                    singleLine = true,
                    visualTransformation = if (hideServerAddresses && !revealed) {
                        PasswordVisualTransformation(mask = '*')
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hideServerAddresses) {
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(if (revealed) "Скрыть URL" else "Показать URL")
                    }
                }
                Text(
                    "Открытые add/import-ссылки Happ, INCY и v2RayTun разворачиваются " +
                        "в адрес подписки автоматически.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SubscriptionIdentityFields(
                    clientProfile = clientProfile,
                    onClientProfile = { clientProfile = it },
                    sendHwid = sendHwid,
                    onSendHwid = { sendHwid = it },
                    hwid = hwid,
                    onHwid = { hwid = it },
                    hideSecrets = hideServerAddresses,
                    autoOption = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onImport(
                        url,
                        SubscriptionIdentityInput(clientProfile, sendHwid, hwid.trim()),
                    )
                },
                enabled = url.isNotBlank(),
            ) {
                Text("Загрузить preview")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun SubscriptionSettingsDialog(
    settings: SubscriptionSettingsState,
    hideServerAddresses: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (SubscriptionIdentityInput) -> Unit,
) {
    var clientProfile by remember(settings) {
        mutableStateOf<SubscriptionClientProfile?>(settings.clientProfile)
    }
    var sendHwid by remember(settings) { mutableStateOf(settings.sendHwid) }
    var hwid by remember(settings) { mutableStateOf(settings.hwid) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подписка: ${settings.profileName}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    ScreenshotPrivacy.subscriptionSource(settings.url, hideServerAddresses),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SubscriptionIdentityFields(
                    clientProfile = clientProfile,
                    onClientProfile = { clientProfile = it },
                    sendHwid = sendHwid,
                    onSendHwid = { sendHwid = it },
                    hwid = hwid,
                    onHwid = { hwid = it },
                    hideSecrets = hideServerAddresses,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(SubscriptionIdentityInput(clientProfile, sendHwid, hwid.trim()))
                },
                enabled = !busy,
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Панели с лимитом устройств узнают клиента по User-Agent и `X-HWID`. Пустое поле
 * HWID означает постоянный идентификатор этой установки, а не отсутствие заголовка.
 */
@Composable
private fun SubscriptionIdentityFields(
    clientProfile: SubscriptionClientProfile?,
    onClientProfile: (SubscriptionClientProfile?) -> Unit,
    sendHwid: Boolean,
    onSendHwid: (Boolean) -> Unit,
    hwid: String,
    onHwid: (String) -> Unit,
    hideSecrets: Boolean,
    autoOption: Boolean = false,
) {
    var hwidRevealed by rememberSaveable { mutableStateOf(false) }
    Text(
        "Профиль клиента",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (autoOption) {
            FilterChip(
                selected = clientProfile == null,
                onClick = { onClientProfile(null) },
                label = { Text("Из ссылки") },
            )
        }
        SubscriptionClientProfile.entries.forEach { profile ->
            FilterChip(
                selected = profile == clientProfile,
                onClick = { onClientProfile(profile) },
                label = { Text(profile.displayName()) },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Передавать стабильный HWID", modifier = Modifier.weight(1f))
        Switch(checked = sendHwid, onCheckedChange = onSendHwid)
    }
    if (sendHwid) {
        OutlinedTextField(
            value = hwid,
            onValueChange = { onHwid(it.take(128)) },
            label = { Text("HWID") },
            placeholder = { Text("Пусто — постоянный ID этой установки") },
            singleLine = true,
            visualTransformation = if (hideSecrets && !hwidRevealed) {
                PasswordVisualTransformation(mask = '*')
            } else {
                VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (hideSecrets) {
            TextButton(onClick = { hwidRevealed = !hwidRevealed }) {
                Text(if (hwidRevealed) "Скрыть HWID" else "Показать HWID")
            }
        }
    }
}

private fun SubscriptionClientProfile.displayName(): String = when (this) {
    SubscriptionClientProfile.Zapret -> "Zapret KVN"
    SubscriptionClientProfile.Happ -> "Happ"
    SubscriptionClientProfile.Incy -> "INCY"
    SubscriptionClientProfile.V2RayTun -> "v2RayTun"
    SubscriptionClientProfile.Custom -> "Другой"
}
@Composable
internal fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Название") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

