package io.github.zapretkvn.android.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.apps.InstalledApp
import io.github.zapretkvn.core.designsystem.Monogram
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.toneColor

/**
 * Строка приложения. Иконки не грузим: монограмма-заглушка дешёвая и не требует кэша.
 * Вся строка — зона нажатия; отключённое в Android приложение выбрать по-прежнему можно.
 */
@Composable
internal fun AppRow(
    app: InstalledApp,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    showSuggestion: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag("app-row-${app.packageName}")
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = onSelectedChange)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(appMonogram(app.label))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val note = appNote(app, showSuggestion)
            if (note.isNotEmpty()) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelMedium,
                    color = toneColor(if (app.enabled) Tone.Accent else Tone.Bad),
                )
            }
        }
        Checkbox(checked = selected, onCheckedChange = null)
    }
}

internal fun appNote(app: InstalledApp, showSuggestion: Boolean): String = buildList {
    if (showSuggestion) app.suggestion?.let { add("Рекомендуем: $it") }
    if (app.system) add("Системное")
    if (!app.enabled) add("Отключено в Android")
}.joinToString(" · ")

/** Пакет из списка, которого уже нет на устройстве: его можно убрать из выбора. */
@Composable
internal fun MissingAppRow(packageName: String, blocked: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                packageName,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                if (blocked) {
                    "Приложение удалено или недоступно. Блокировка будет пропущена."
                } else {
                    "Приложение удалено или недоступно и будет пропущено при подключении."
                },
                style = MaterialTheme.typography.bodySmall,
                color = toneColor(Tone.Bad),
            )
        }
        TextButton(onClick = onRemove) { Text("Убрать") }
    }
}
