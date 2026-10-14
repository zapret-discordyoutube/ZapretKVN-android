package io.github.zapretkvn.android.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.core.designsystem.FlagBadge
import io.github.zapretkvn.core.designsystem.Monogram
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.toneColor

/**
 * Одна строка сервера на все места: вкладка «Серверы», быстрый выбор на Главной.
 *
 * Компактная: флаг 28 dp, название (переносится, без «…»), под ним мелко протокол
 * (и адрес, только если пользователь отключил скрытие адресов), справа задержка:
 * число и маленькое слово цветом тона. Высота одиночной строки ~56 dp.
 * Выбранный сервер: тональный фон и галочка на флаге.
 * [onClick] == null — строка справочная (группа выбирается автоматически).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerRow(
    row: ServerRowUi,
    position: RowPosition,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = cardShape(position)
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
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Leading(row)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (row.selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 3,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.protocol,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    row.endpoint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    row.badge?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (wide) LatencyInline(row.latency)
                }
            }
            if (!wide) LatencyBlock(row.latency)
        }
        if (position == RowPosition.First || position == RowPosition.Middle) RowDivider()
    }
}

private const val WIDE_FONT_SCALE = 1.5f
private val ROW_MIN_HEIGHT = 56.dp

/** Скругления карточки по положению строки; соседние строки склеиваются в одну карточку. */
@Composable
fun cardShape(position: RowPosition): CornerBasedShape {
    val base = MaterialTheme.shapes.large
    val zero = CornerSize(0.dp)
    return when (position) {
        RowPosition.Only -> base
        RowPosition.First -> base.copy(bottomStart = zero, bottomEnd = zero)
        RowPosition.Middle -> base.copy(topStart = zero, topEnd = zero, bottomStart = zero, bottomEnd = zero)
        RowPosition.Last -> base.copy(topStart = zero, topEnd = zero)
    }
}

@Composable
private fun Leading(row: ServerRowUi) {
    Box(modifier = Modifier.size(LEADING_SIZE), contentAlignment = Alignment.Center) {
        if (row.flag != null) {
            FlagBadge(row.flag, size = LEADING_SIZE)
        } else {
            Monogram(row.monogram, Modifier.size(26.dp))
        }
        if (row.selected) {
            Icon(
                ZapretIcons.Ok,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            )
        }
    }
}

private val LEADING_SIZE = 28.dp

/** Задержка справа: число (цвет тона) и под ним слово того же тона. */
@Composable
private fun LatencyBlock(latency: LatencyDisplay) {
    val untested = latency.value == DASH && !latency.failed
    Column(horizontalAlignment = Alignment.End) {
        if (!untested) {
            Text(
                latency.value,
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = if (latency.value == DASH) MaterialTheme.colorScheme.onSurfaceVariant else toneColor(latency.tone),
                textAlign = TextAlign.End,
            )
        }
        Text(
            latency.word,
            style = MaterialTheme.typography.labelSmall,
            color = if (latency.tone == io.github.zapretkvn.core.designsystem.Tone.Neutral) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                toneColor(latency.tone)
            },
            textAlign = TextAlign.End,
        )
    }
}

/** Задержка в одну строку для крупного шрифта: число и слово рядом с протоколом. */
@Composable
private fun LatencyInline(latency: LatencyDisplay) {
    val text = if (latency.value == DASH) latency.word else "${latency.value} · ${latency.word}"
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (latency.tone == io.github.zapretkvn.core.designsystem.Tone.Neutral) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            toneColor(latency.tone)
        },
    )
}

/**
 * Заголовок секции «страна»: флаг, название, «8 серверов · лучший 84 мс» и шеврон.
 * Развёрнутая секция — верх общей карточки со строками под ней.
 */
@Composable
fun SectionRow(
    item: ServerListItem.Section,
    onToggle: (key: String, expand: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val section = item.section
    val shape = cardShape(if (item.expanded) RowPosition.First else RowPosition.Only)
    Column(
        modifier = modifier
            .padding(top = Spacing.s)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = if (item.expanded) "Свернуть" else "Развернуть", role = Role.Button) {
                onToggle(section.key, !item.expanded)
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "${section.title}, ${item.meta}"
                stateDescription = if (item.expanded) "развёрнуто" else "свёрнуто"
            }
            .testTag("server-section-${section.code ?: "other"}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(start = Spacing.l, end = Spacing.s, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (section.flag != null) {
                FlagBadge(section.flag, size = LEADING_SIZE)
            } else {
                Icon(
                    ZapretIcons.Globe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(LEADING_SIZE).padding(2.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    item.meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (item.expanded) ZapretIcons.ExpandLess else ZapretIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.s),
            )
        }
        if (item.expanded) RowDivider()
    }
}

/** Тонкий разделитель между строками одной карточки (между строками с общим фоном). */
@Composable
fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.l),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}
