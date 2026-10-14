package io.github.zapretkvn.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable

@Composable
@ReadOnlyComposable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Good -> ZapretColors.status.good
    Tone.Warning -> ZapretColors.status.warning
    Tone.Bad -> ZapretColors.status.bad
    Tone.Accent -> MaterialTheme.colorScheme.primary
}

@Composable
@ReadOnlyComposable
fun toneContainer(tone: Tone): Color = when (tone) {
    Tone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
    Tone.Good -> ZapretColors.status.goodContainer
    Tone.Warning -> ZapretColors.status.warningContainer
    Tone.Bad -> ZapretColors.status.badContainer
    Tone.Accent -> MaterialTheme.colorScheme.primaryContainer
}

@Composable
@ReadOnlyComposable
fun onToneContainer(tone: Tone): Color = when (tone) {
    Tone.Neutral -> MaterialTheme.colorScheme.onSurface
    Tone.Good -> ZapretColors.status.onGoodContainer
    Tone.Warning -> ZapretColors.status.onWarningContainer
    Tone.Bad -> ZapretColors.status.onBadContainer
    Tone.Accent -> MaterialTheme.colorScheme.onPrimaryContainer
}

/**
 * Карточка без тени: разделение тоном, а не elevation — тени в списках
 * дороги для GPU слабых телефонов. На AMOLED добавляется тонкая обводка.
 */
@Composable
fun ZapretCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(Spacing.l),
    verticalSpacing: androidx.compose.ui.unit.Dp = Spacing.m,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = if (LocalAmoled.current) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else {
        null
    }
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = colors,
            border = border,
            content = body,
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = colors,
            border = border,
            content = body,
        )
    }
}

/** Заголовок карточки: иконка, название (переносится, не режется) и действие справа. */
@Composable
fun CardTitle(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        action?.invoke(this)
    }
}

/** Подпись раздела списка: «ПОДПИСКИ», «ВНЕШНИЙ ВИД». */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.xs, top = Spacing.l, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/**
 * Строка настроек: иконка, название, значение под ним и элемент справа.
 * Растёт по тексту — никаких фиксированных высот, только минимум 56dp.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
        if (showChevron && trailing == null) {
            Icon(
                ZapretIcons.Chevron,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Переключатель со строкой целиком как зоной нажатия. Один компонент вместо копий. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    switchTestTag: String? = null,
    switchDescription: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier
                .then(if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier)
                .then(
                    if (switchDescription != null) {
                        Modifier.semantics { contentDescription = switchDescription }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/** Бейдж со словом и тоном: «быстро», «через VPN», «не отвечает». */
@Composable
fun ToneBadge(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .background(toneContainer(tone), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = onToneContainer(tone), modifier = Modifier.size(14.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = onToneContainer(tone),
        )
    }
}

/** Точка состояния рядом со статусом. */
@Composable
fun StatusDot(tone: Tone, modifier: Modifier = Modifier, color: Color = toneColor(tone)) {
    Box(
        modifier = modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

/** Круг с монограммой или эмодзи-флагом — без картинок в APK. */
@Composable
fun Monogram(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    emoji: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (emoji) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** Верхняя панель вложенного экрана: одна шапка, стрелка «Назад», действия. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapretTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 2) },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(ZapretIcons.Back, contentDescription = "Назад")
                }
            }
        },
        actions = actions,
    )
}

/** Пустое состояние: что это и что сделать — одной-двумя кнопками. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xl, horizontal = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        actions()
    }
}

/** Строка «ключ — значение» для фактов: IP, время, версия. Значение переносится. */
@Composable
fun FactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueTone: Tone? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueTone?.let { toneColor(it) } ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
        )
    }
}
