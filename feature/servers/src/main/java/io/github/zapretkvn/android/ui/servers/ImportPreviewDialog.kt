package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.profiles.ImportPreview
import io.github.zapretkvn.android.profiles.MAX_SPLIT_PROFILES
import io.github.zapretkvn.core.uimodel.ScreenshotPrivacy

@Composable
fun ImportPreviewDialog(
    preview: ImportPreview,
    hideServerAddresses: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onSplit: (String) -> Unit,
    onAppend: (String) -> Unit,
    onRefresh: (Boolean) -> Unit,
) {
    var name by remember(preview) { mutableStateOf(preview.suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (preview.isRefresh) "Обновление подписки" else "Предпросмотр импорта") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (preview.hasSubscriptionUrl) {
                        ScreenshotPrivacy.subscriptionSource(
                            preview.sourceDescription,
                            hideServerAddresses,
                        )
                    } else {
                        preview.sourceDescription
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (preview.serverCount > 0) "Серверов: ${preview.serverCount}" else "Готовый sing-box JSON",
                )
                if (preview.serverLabels.isNotEmpty()) {
                    Text(
                        if (hideServerAddresses) {
                            preview.serverLabels.joinToString(" • ") {
                                ScreenshotPrivacy.serverLabel(it, hidden = true)
                            }
                        } else {
                            preview.serverLabels.joinToString(" • ")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!preview.isRefresh) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(80) },
                        label = { Text("Название профиля") },
                        singleLine = true,
                    )
                }
                preview.activityWarning?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                preview.importWarnings.forEach {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (preview.selectionChanged) {
                    Text(
                        "Текущий server tag исчез: будет выбран первый доступный сервер.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                preview.splitRefreshSummary?.let { summary ->
                    Text(
                        "Раздельные профили: ${summary.updated} обновится, " +
                            "${summary.added} добавится, ${summary.removed} удалится.",
                        color = if (summary.removed > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (summary.connectedProfileRemoved) {
                        Text(
                            "Подключённый сервер удалён из подписки: после обновления VPN будет отключён.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (preview.activeRefresh) {
                    Text(
                        "Профиль сейчас подключён. Перезапуск возможен только отдельным подтверждением ниже.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (!preview.isRefresh && preview.splittableServerCount > 1) {
                    HorizontalDivider()
                    Text(
                        if (preview.splitSupported) {
                            "Один профиль-группа даёт переключение сервера прямо в приложении. " +
                                "Отдельные профили удобны, когда у серверов разные маршруты и настройки" +
                                if (preview.hasSubscriptionUrl) {
                                    "; они сохранят индивидуальные маршруты и будут синхронизироваться " +
                                        "вместе по ссылке подписки."
                                } else {
                                    "."
                                }
                        } else {
                            "Серверов больше $MAX_SPLIT_PROFILES: разложить их по отдельным " +
                                "профилям нельзя. Сохраните профиль-группу и переключайте сервер " +
                                "в приложении."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!preview.isRefresh && preview.isSingleManaged && preview.appendTargets.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Или добавить сервер в managed-группу:")
                    preview.appendTargets.take(4).forEach { profile ->
                        TextButton(onClick = { onAppend(profile.id) }, enabled = !busy) {
                            Text(profile.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (preview.isRefresh) {
                Column(horizontalAlignment = Alignment.End) {
                    if (preview.activeRefresh) {
                        TextButton(onClick = { onRefresh(true) }, enabled = !busy) {
                            Text(
                                if (preview.splitRefreshSummary?.connectedProfileRemoved == true) {
                                    "Обновить и отключить"
                                } else {
                                    "Сохранить и переподключить"
                                },
                            )
                        }
                    }
                    if (preview.splitRefreshSummary?.connectedProfileRemoved != true) {
                        TextButton(onClick = { onRefresh(false) }, enabled = !busy) {
                            Text(if (preview.activeRefresh) "Сохранить без перезапуска" else "Обновить")
                        }
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank() && !busy) {
                        Text(
                            if (preview.splittableServerCount > 1) {
                                "Один профиль-группа"
                            } else {
                                "Новый профиль"
                            },
                        )
                    }
                    if (preview.splitSupported) {
                        TextButton(
                            onClick = { onSplit(name) },
                            enabled = name.isNotBlank() && !busy,
                            modifier = Modifier.testTag("import-split-profiles"),
                        ) {
                            Text("Отдельные профили: ${preview.splittableServerCount}")
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}
