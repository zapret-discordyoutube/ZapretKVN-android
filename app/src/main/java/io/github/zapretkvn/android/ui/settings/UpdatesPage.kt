package io.github.zapretkvn.android.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.ReleaseNotesMarkdown
import io.github.zapretkvn.android.updates.UpdateChannel
import io.github.zapretkvn.core.designsystem.CardTitle
import io.github.zapretkvn.core.designsystem.Motion
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.toneColor
import kotlinx.coroutines.flow.map

@Composable
internal fun UpdatesRoute(deps: AppDependencies, padding: PaddingValues) {
    val settings by rememberUiSettings(deps)
    // Презентер вызывается в потоке, а не в composable: при загрузке состояние
    // меняется часто, и пересчёт нужен один раз на значение.
    val ui by remember(deps.updates) {
        deps.updates.state.map(::presentUpdate)
    }.collectAsStateWithLifecycle(
        initialValue = remember(deps.updates) { presentUpdate(deps.updates.state.value) },
    )
    val channel = settings.updateChannel
    UpdatesScreen(
        contentPadding = padding,
        currentVersion = AppInfo.versionName,
        channel = channel,
        ui = ui,
        onChannelChange = deps.profiles.settings::setUpdateChannel,
        onCheck = { deps.updates.check(channel) },
        onDownload = deps.updates::download,
        onInstall = deps.installUpdate,
        onCancel = deps.updates::cancelAndDelete,
    )
}

@Composable
internal fun UpdatesScreen(
    contentPadding: PaddingValues,
    currentVersion: String,
    channel: UpdateChannel,
    ui: UpdateSectionUi,
    onChannelChange: (UpdateChannel) -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    SettingsPageList(contentPadding, tag = "settings-page-updates") {
        item(key = "channel", contentType = "card") {
            ZapretCard {
                CardTitle("Канал обновлений", icon = ZapretIcons.Download)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val channels = UpdateChannel.entries
                    channels.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = item == channel,
                            onClick = { onChannelChange(item) },
                            shape = SegmentedButtonDefaults.itemShape(index, channels.size),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(item.title()) }
                    }
                }
                HintText(
                    "Stable — проверенные версии. Beta — свежие сборки с новыми возможностями, " +
                        "в них возможны ошибки. Для каждой новой версии канал выбирается по её " +
                        "сборке, его можно сменить вручную. Ядро обновляется вместе с приложением.",
                )
            }
        }
        item(key = "status", contentType = "card") {
            ZapretCard {
                Text(
                    "Установлена версия $currentVersion",
                    style = MaterialTheme.typography.titleMedium,
                )
                UpdateControls(
                    ui = ui,
                    onCheck = onCheck,
                    onDownload = onDownload,
                    onInstall = onInstall,
                    onCancel = onCancel,
                )
                HintText(
                    "Приложение проверяет обновления само, примерно раз в сутки и при запуске. В канале Stable " +
                        "обновление скачивается в фоне, по возможности только изменившиеся части; на мобильной сети " +
                        "автоматически качается лишь небольшое. В фоне обновление может встать без окон, а по " +
                        "нажатию «Установить» Android спросит одно подтверждение.",
                )
            }
        }
    }
}

/** Стейт-машина блока обновлений: что показать и какая кнопка основная. */
@Composable
internal fun UpdateControls(
    ui: UpdateSectionUi,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    when (ui) {
        UpdateSectionUi.Idle -> PrimaryButton("Проверить сейчас", onCheck)

        is UpdateSectionUi.Busy -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(ui.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Отмена") }
        }

        is UpdateSectionUi.UpToDate -> {
            Text(ui.message, style = MaterialTheme.typography.bodyMedium, color = toneColor(Tone.Good))
            PrimaryButton("Проверить сейчас", onCheck)
        }

        is UpdateSectionUi.Available -> {
            ReleaseHeader(ui.release)
            ReleaseNotes(ui.release.notes, initiallyExpanded = true)
            PrimaryButton("Скачать", onDownload)
        }

        is UpdateSectionUi.Downloading -> {
            ReleaseHeader(ui.release)
            if (ui.progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                val animated by animateFloatAsState(ui.progress, tween(Motion.EMPHASIS), label = "update-progress")
                LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                listOf(ui.progressLabel, ui.savingLabel).filter(String::isNotEmpty).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            ReleaseNotes(ui.release.notes, initiallyExpanded = false)
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Отмена") }
        }

        is UpdateSectionUi.Ready -> {
            ReleaseHeader(ui.release)
            Text(
                if (ui.needsConfirmation) {
                    "Файл проверен: контрольная сумма, пакет и подпись совпадают. " +
                        "Android просит одно подтверждение — нажмите «Установить»."
                } else {
                    "Файл скачан и проверен: контрольная сумма, пакет и подпись совпадают. " +
                        "Фоновая установка без вопросов возможна, пока приложение не на экране; " +
                        "по вашему нажатию Android попросит одно подтверждение."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            ReleaseNotes(ui.release.notes, initiallyExpanded = false)
            PrimaryButton("Установить", onInstall)
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Удалить загруженный файл") }
        }

        is UpdateSectionUi.Installing -> {
            ReleaseHeader(ui.release)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(
                    "Устанавливаем обновление. Приложение перезапустится.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        is UpdateSectionUi.Failure -> {
            Text(
                ui.message,
                style = MaterialTheme.typography.bodyMedium,
                color = toneColor(Tone.Bad),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            PrimaryButton("Повторить", if (ui.canRetryDownload) onDownload else onCheck)
            if (ui.canRetryDownload) {
                OutlinedButton(
                    onClick = onCheck,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Проверить заново") }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}

@Composable
private fun ReleaseHeader(release: ReleaseUi) {
    Text(
        "Доступна версия ${release.versionName}",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    HintText(release.details)
}

/** «Что нового» — заметки к релизу; в заголовке состояния «Доступно» раскрыты, дальше свёрнуты. */
@Composable
private fun ReleaseNotes(notes: String, initiallyExpanded: Boolean) {
    if (notes.isBlank()) return
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(if (expanded) "Свернуть заметки" else "Что нового") }
    if (expanded) {
        ReleaseNotesMarkdown(
            markdown = notes,
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs).testTag("update-release-notes"),
        )
    }
}
