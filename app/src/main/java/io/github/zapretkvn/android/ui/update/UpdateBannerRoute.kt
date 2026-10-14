package io.github.zapretkvn.android.ui.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zapretkvn.android.ui.AppDependencies
import io.github.zapretkvn.android.ui.AppNavigation
import io.github.zapretkvn.android.ui.home.HomeRoute
import io.github.zapretkvn.core.designsystem.Spacing
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
