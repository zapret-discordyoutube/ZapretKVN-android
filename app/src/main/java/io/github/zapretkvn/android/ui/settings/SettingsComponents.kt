package io.github.zapretkvn.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.core.designsystem.Motion
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons

/**
 * Прокручиваемая страница настроек: поля по бокам, отступы под системные
 * панели берутся из Scaffold, но список уезжает под них при прокрутке.
 */
@Composable
internal fun SettingsPageList(
    contentPadding: PaddingValues,
    tag: String,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(tag),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = contentPadding.calculateTopPadding() + Spacing.s,
            bottom = contentPadding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
        content = content,
    )
}

/** Карточка без внутренних отступов — под строки `SettingsRow`/`SwitchRow`. */
@Composable
internal fun RowsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZapretCard(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = Spacing.xs),
        verticalSpacing = 0.dp,
        content = content,
    )
}

/**
 * Свёртываемая карточка («аккордеон»): заголовок со стрелкой всегда виден,
 * содержимое появляется короткой анимацией и композируется только раскрытым.
 * `summary` — строка-итог в шапке, пока секция свёрнута; в раскрытой её
 * заменяет само содержимое.
 */
@Composable
internal fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    headerTag: String? = null,
    padContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZapretCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        verticalSpacing = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .then(if (headerTag != null) Modifier.testTag(headerTag) else Modifier)
                .semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                if (!expanded && !summary.isNullOrBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                if (expanded) ZapretIcons.ExpandLess else ZapretIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(Motion.STANDARD)) + fadeIn(tween(Motion.STANDARD)),
            exit = shrinkVertically(tween(Motion.QUICK)) + fadeOut(tween(Motion.QUICK)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (padContent) Spacing.l else 0.dp,
                        end = if (padContent) Spacing.l else 0.dp,
                        bottom = Spacing.m,
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
                content = content,
            )
        }
    }
}

/** Пояснение мелким приглушённым текстом. */
@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
