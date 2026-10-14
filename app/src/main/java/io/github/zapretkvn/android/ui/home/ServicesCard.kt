package io.github.zapretkvn.android.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.network.probes.services.ServiceAvailabilityState
import io.github.zapretkvn.core.designsystem.CardTitle
import io.github.zapretkvn.core.designsystem.Motion
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.StatusDot
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.toneColor
import kotlinx.coroutines.delay

/**
 * «Доступность сервисов». Вся логика в [servicesCardUi]; здесь только вёрстка.
 * Нажатие в любом месте карточки запускает проверку (повтор ограничен паузой
 * в координаторе). Возраст результатов обновляется раз в полминуты, пока карточка
 * на экране и есть что обновлять: это подпись на экране, а не сетевой таймер.
 */
@Composable
internal fun ServicesCard(
    state: () -> ServiceAvailabilityState,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state()
    val hasResult = current.items.isNotEmpty()
    val now by produceState(System.currentTimeMillis(), hasResult) {
        while (hasResult) {
            value = System.currentTimeMillis()
            delay(AGE_REFRESH_MILLIS)
        }
    }
    val ui = servicesCardUi(current, now)
    ZapretCard(
        modifier = modifier
            .animateContentSize(tween(Motion.STANDARD))
            .testTag("services-card"),
        onClick = if (ui.checking) null else onCheck,
        verticalSpacing = Spacing.s,
    ) {
        CardTitle(
            title = "Доступность сервисов",
            icon = ZapretIcons.Globe,
            action = {
                if (ui.checking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        ZapretIcons.Refresh,
                        contentDescription = "Проверить сервисы",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        )
        Text(ui.summary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            ui.rows.forEach { row -> ServiceRow(row) }
        }
        Text(
            ui.footnote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun ServiceRow(row: ServiceRowUi) {
    val verdictColor = if (row.checking) MaterialTheme.colorScheme.onSurfaceVariant else toneColor(row.tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = row.description }
            .testTag("service-row-${row.id.token}"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        // Вердикт и пояснение переносятся на сколько угодно строк: длинное «не работает из региона» не режется.
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                StatusDot(row.tone, color = verdictColor)
                Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Text(
                row.verdict,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = verdictColor,
            )
            row.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            row.latency?.let {
                Text(it, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
            }
            row.age?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.stale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

private const val AGE_REFRESH_MILLIS = 30_000L
