package io.github.zapretkvn.android.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.home.HomeRoute
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.onToneContainer
import io.github.zapretkvn.core.designsystem.toneContainer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Главная с тихой плашкой обновления сверху. Дерево composable не меняется, когда плашка появляется или
 * исчезает, поэтому прокрутка Главной не сбрасывается.
 */
@Composable
internal fun HomeWithUpdateBanner(deps: AppDependencies, nav: AppNavigation, padding: PaddingValues) {
    val banner by remember(deps.updates) {
        combine(deps.updates.state, deps.updates.bannerDismissedVersionCode, ::presentBanner).distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val layoutDirection = LocalLayoutDirection.current
    val homePadding = PaddingValues(
        start = if (layoutDirection == LayoutDirection.Ltr) {
            padding.calculateLeftPadding(layoutDirection)
        } else {
            padding.calculateRightPadding(layoutDirection)
        },
        end = if (layoutDirection == LayoutDirection.Ltr) {
            padding.calculateRightPadding(layoutDirection)
        } else {
            padding.calculateLeftPadding(layoutDirection)
        },
        top = if (banner == null) padding.calculateTopPadding() else 0.dp,
        bottom = padding.calculateBottomPadding(),
    )
    Column(Modifier.fillMaxSize()) {
        banner?.let { ui ->
            UpdateBanner(
                ui = ui,
                onAction = { action ->
                    when (action) {
                        BannerAction.Install -> deps.installUpdate()
                        BannerAction.Download -> deps.updates.download()
                    }
                },
                onDismiss = { deps.updates.dismissBanner(ui.versionCode) },
                modifier = Modifier.padding(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    top = padding.calculateTopPadding() + Spacing.s,
                ),
            )
        }
        Box(Modifier.weight(1f)) { HomeRoute(deps, nav, homePadding) }
    }
}

@Composable
internal fun UpdateBanner(
    ui: UpdateBannerUi,
    onAction: (BannerAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ZapretCard(
        modifier = modifier.testTag("update-banner"),
        containerColor = toneContainer(Tone.Accent),
        contentPadding = PaddingValues(start = Spacing.l, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalSpacing = Spacing.xs,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(
                ZapretIcons.Download,
                contentDescription = null,
                tint = onToneContainer(Tone.Accent),
                modifier = Modifier.size(20.dp),
            )
            Text(
                ui.text,
                style = MaterialTheme.typography.bodyMedium,
                color = onToneContainer(Tone.Accent),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = Spacing.s)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            ui.action?.let { action ->
                TextButton(
                    onClick = { onAction(action) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("update-banner-action"),
                ) { Text(action.label) }
            }
            if (ui.dismissible) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        ZapretIcons.Close,
                        contentDescription = "Скрыть для этой версии",
                        tint = onToneContainer(Tone.Accent),
                    )
                }
            }
        }
        ui.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(end = Spacing.l, bottom = Spacing.s),
            )
        }
    }
}
