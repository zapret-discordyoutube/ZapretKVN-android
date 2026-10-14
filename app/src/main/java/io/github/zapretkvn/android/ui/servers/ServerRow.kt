package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.core.designsystem.Monogram
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.ToneBadge
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.toneColor

/**
 * Одна строка сервера на все места: вкладка «Серверы», быстрый выбор на Главной.
 *
 * Название переносится (до трёх строк, без «…»), протокол — бейдж, адрес маскируется
 * заранее (в [ServerRowUi.endpoint]). Выбранный сервер: тональный фон и галочка на флаге.
 * [onClick] == null — строка справочная (группа выбирается автоматически).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServerRow(
    row: ServerRowUi,
    position: RowPosition,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.large.let {
        val zero = CornerSize(0.dp)
        when (position) {
            RowPosition.Only -> it
            RowPosition.First -> it.copy(bottomStart = zero, bottomEnd = zero)
            RowPosition.Middle -> it.copy(topStart = zero, topEnd = zero, bottomStart = zero, bottomEnd = zero)
            RowPosition.Last -> it.copy(topStart = zero, topEnd = zero)
        }
    }
    val container = if (row.selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val semanticsModifier = Modifier.semantics(mergeDescendants = true) {
        contentDescription = row.a11y
        if (row.selected) stateDescription = "выбран"
    }
    val clickModifier = if (onClick != null) {
        Modifier.selectable(selected = row.selected, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier
    }
    val wide = LocalDensity.current.fontScale > WIDE_FONT_SCALE

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .then(clickModifier)
            .then(semanticsModifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Leading(row)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 3,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    ProtocolChip(row.protocol)
                    row.endpoint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                row.badge?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (wide) LatencyBlock(row.latency, Alignment.Start)
            }
            if (!wide) LatencyBlock(row.latency, Alignment.End, Modifier.widthIn(max = 132.dp))
        }
        if (position == RowPosition.First || position == RowPosition.Middle) RowDivider()
    }
}

private const val WIDE_FONT_SCALE = 1.5f

@Composable
private fun Leading(row: ServerRowUi) {
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (row.flag != null) {
            Text(row.flag, style = MaterialTheme.typography.titleLarge)
        } else {
            Monogram(row.monogram, Modifier.size(36.dp))
        }
        if (row.selected) {
            Icon(
                ZapretIcons.Ok,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            )
        }
    }
}

@Composable
private fun ProtocolChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun LatencyBlock(
    latency: LatencyDisplay,
    horizontal: Alignment.Horizontal,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = horizontal, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            latency.value,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = if (latency.value == DASH) MaterialTheme.colorScheme.onSurfaceVariant else toneColor(latency.tone),
            textAlign = if (horizontal == Alignment.End) TextAlign.End else TextAlign.Start,
        )
        ToneBadge(latency.word, latency.tone, icon = latency.icon.vector())
    }
}

private fun LatencyIcon.vector() = when (this) {
    LatencyIcon.None -> null
    LatencyIcon.Ok -> ZapretIcons.Ok
    LatencyIcon.Slow -> ZapretIcons.Warning
    LatencyIcon.Failed -> ZapretIcons.Close
    LatencyIcon.Old -> ZapretIcons.Hourglass
}

/** Тонкий разделитель между строками одной карточки (между строками с общим фоном). */
@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.l),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}
