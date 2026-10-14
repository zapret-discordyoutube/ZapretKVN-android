package io.github.zapretkvn.android.ui.home

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.diagnostics.VpnErrorContext
import io.github.zapretkvn.android.diagnostics.VpnErrorExplainer
import io.github.zapretkvn.android.network.probes.services.ServiceAvailabilityState
import io.github.zapretkvn.android.routing.RoutingPreset
import io.github.zapretkvn.android.ui.DiagnosticShareButton
import io.github.zapretkvn.android.ui.formatLatency
import io.github.zapretkvn.android.ui.routing.PresetPicker
import io.github.zapretkvn.android.ui.servers.FlagBadge
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.TrafficSample
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnSessionStats
import io.github.zapretkvn.core.designsystem.Motion
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ToneBadge
import io.github.zapretkvn.core.designsystem.ZapretCard
import io.github.zapretkvn.core.designsystem.ZapretColors
import io.github.zapretkvn.core.designsystem.ZapretIcons
import io.github.zapretkvn.core.designsystem.toneColor
import io.github.zapretkvn.core.uimodel.formatBytes
import io.github.zapretkvn.core.uimodel.formatDuration
import io.github.zapretkvn.core.uimodel.formatRate
import kotlinx.coroutines.delay
import kotlin.math.max

/** Текущий сервер, уже подготовленный для показа (адреса скрыты по настройке). */
internal data class HomeServerUi(
    val title: String,
    val flag: String?,
    val protocol: String,
    val monogram: String,
    val modeLabel: String?,
    val latency: LatencyBadgeUi?,
    val relay: LatencyProbeState?,
    val icmp: LatencyProbeState?,
    val switchable: Boolean,
)

internal data class HomeUi(
    val vpnState: VpnConnectionState,
    val stage: HomeStage,
    val headline: HomeHeadline,
    val primary: HomeAction,
    val secondary: HomeAction,
    val profileName: String?,
    val server: HomeServerUi?,
    val preset: RoutingPreset?,
    val appsSummary: String,
    val errorContext: VpnErrorContext,
)

internal class HomeCallbacks(
    val onAction: (HomeAction) -> Unit,
    val onOpenServers: () -> Unit,
    val onOpenRouting: () -> Unit,
    val onSelectPreset: (RoutingPreset) -> Unit,
    val createDiagnosticShare: suspend () -> Intent,
    val onCheckServices: () -> Unit,
)

/**
 * Главная: сверху — что с VPN (кнопка-сфера и понятный заголовок), ниже —
 * через какой сервер, и при подключении — цветные плитки скорости, график и
 * сеть. Частые данные (трафик, часы) читаются только в своих элементах;
 * анимации идут лишь при подключении или коротко при смене состояния.
 */
@Composable
internal fun HomeScreen(
    ui: HomeUi,
    stats: () -> VpnSessionStats,
    services: () -> ServiceAvailabilityState,
    callbacks: HomeCallbacks,
    contentPadding: PaddingValues,
) {
    val connected = ui.vpnState is VpnConnectionState.Connected
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home-list"),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = contentPadding.calculateTopPadding() + Spacing.s,
            bottom = contentPadding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
    ) {
        item(key = "hero", contentType = "hero") { Hero(ui, callbacks) }
        if (ui.server != null && ui.stage != HomeStage.NoProfile) {
            item(key = "server", contentType = "server") {
                ServerCard(ui.server, onClick = callbacks.onOpenServers, modifier = Modifier.animateItem())
            }
        }
        if (connected) {
            item(key = "tiles", contentType = "tiles") {
                SpeedTiles(stats = stats, server = ui.server, modifier = Modifier.animateItem())
            }
            item(key = "services", contentType = "card") {
                ServicesCard(state = services, onCheck = callbacks.onCheckServices, modifier = Modifier.animateItem())
            }
            item(key = "traffic", contentType = "card") {
                TrafficCard(stats, modifier = Modifier.animateItem())
            }
            item(key = "network", contentType = "card") {
                NetworkCard(ui.server, stats, modifier = Modifier.animateItem())
            }
        }
        item(key = "shortcuts", contentType = "shortcuts") {
            Shortcuts(ui, callbacks, modifier = Modifier.animateItem())
        }
        item(key = "version", contentType = "footer") {
            Text(
                "Версия приложения: ${BuildConfig.VERSION_NAME}",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.s),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------- hero

@Composable
private fun Hero(ui: HomeUi, callbacks: HomeCallbacks) {
    val tone = statusTone(ui.vpnState)
    val accent = ZapretColors.accent
    val glow by animateColorAsState(
        when (ui.stage) {
            HomeStage.Connected -> accent.heroEnd
            HomeStage.Starting, HomeStage.Reconnecting, HomeStage.Stopping -> accent.connecting
            HomeStage.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        tween(Motion.EMPHASIS),
        label = "heroGlow",
    )
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .drawBehind {
                // Мягкая подсветка цвета состояния сверху вниз — один градиент, без размытия.
                drawRect(
                    Brush.verticalGradient(
                        0f to glow.copy(alpha = 0.22f),
                        0.65f to surface,
                        1f to surface,
                    ),
                )
            }
            .animateContentSize(tween(Motion.STANDARD))
            .padding(horizontal = Spacing.l, vertical = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(statusLabel(ui.vpnState), tone)
            Box(Modifier.weight(1f))
            (ui.vpnState as? VpnConnectionState.Connected)?.let {
                ElapsedTime(sinceEpochMillis = it.connectedAtEpochMillis)
            }
        }
        ui.profileName?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        PowerOrb(
            stage = ui.stage,
            action = ui.primary,
            onClick = { callbacks.onAction(ui.primary) },
        )

        AnimatedContent(
            targetState = ui.headline,
            transitionSpec = {
                (fadeIn(tween(Motion.STANDARD)) + slideInVertically(tween(Motion.STANDARD)) { it / 5 }) togetherWith
                    fadeOut(tween(Motion.QUICK))
            },
            label = "headline",
        ) { headline ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    headline.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                headline.hint?.let { hint ->
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        when (val state = ui.vpnState) {
            is VpnConnectionState.Error -> VpnErrorDetails(
                error = state,
                context = ui.errorContext,
                onCreateDiagnosticShare = callbacks.createDiagnosticShare,
            )
            is VpnConnectionState.Reconnecting -> if (state.code.isNotBlank()) {
                val reason = VpnErrorExplainer.explain(state.code, context = ui.errorContext)
                Text(
                    "Причина: ${reason.title} (${state.code})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            is VpnConnectionState.Starting -> Text(
                "Проверка DNS и доступа может занять до 20 секунд.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            else -> Unit
        }

        when (ui.secondary) {
            HomeAction.None -> Unit
            HomeAction.Stop -> OutlinedButton(
                onClick = { callbacks.onAction(HomeAction.Stop) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(HomeAction.Stop.label) }
            else -> TextButton(
                onClick = { callbacks.onAction(ui.secondary) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(ui.secondary.label) }
        }
    }
}

@Composable
private fun StatusPill(label: String, tone: Tone) {
    val color by animateColorAsState(toneColor(tone), tween(Motion.STANDARD), label = "statusColor")
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Сфера подключения. Подключено — заливка фирменным градиентом и ровное
 * свечение; подключение — бегущая янтарная дуга и «дыхание» свечения;
 * выключено — спокойный тон с градиентным контуром. Все анимированные
 * значения читаются в фазе рисования — перекомпозиции нет.
 */
@Composable
private fun PowerOrb(
    stage: HomeStage,
    action: HomeAction,
    onClick: () -> Unit,
) {
    val accent = ZapretColors.accent
    val scheme = MaterialTheme.colorScheme
    val busy = stage == HomeStage.Starting || stage == HomeStage.Reconnecting || stage == HomeStage.Stopping
    val on = stage == HomeStage.Connected
    val fillStart by animateColorAsState(
        when {
            on -> accent.heroStart
            busy -> accent.connecting.copy(alpha = 0.30f)
            stage == HomeStage.Error -> scheme.errorContainer
            else -> scheme.surfaceContainerHighest
        },
        tween(Motion.EMPHASIS),
        label = "orbStart",
    )
    val fillEnd by animateColorAsState(
        when {
            on -> accent.heroEnd
            busy -> accent.connecting.copy(alpha = 0.18f)
            stage == HomeStage.Error -> scheme.errorContainer
            else -> scheme.surfaceContainerHigh
        },
        tween(Motion.EMPHASIS),
        label = "orbEnd",
    )
    val iconTint by animateColorAsState(
        when {
            on -> Color.White
            busy -> accent.connecting
            stage == HomeStage.Error -> scheme.onErrorContainer
            else -> scheme.primary
        },
        tween(Motion.EMPHASIS),
        label = "orbIcon",
    )
    val halo by animateColorAsState(
        when {
            on -> accent.heroEnd
            busy -> accent.connecting
            stage == HomeStage.Error -> scheme.error
            else -> accent.heroStart
        },
        tween(Motion.EMPHASIS),
        label = "orbHalo",
    )
    val ringStart = accent.heroStart
    val ringEnd = accent.heroEnd
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(stage) {
        if (on) {
            pulse.animateTo(1.08f, spring(stiffness = 500f))
            pulse.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f))
        }
    }
    val icon: ImageVector = when (action) {
        HomeAction.AddProfile -> ZapretIcons.Add
        HomeAction.SelectApps, HomeAction.SelectDirectApps -> ZapretIcons.Apps
        else -> ZapretIcons.Power
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(32.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.s)
            .semantics(mergeDescendants = true) { role = Role.Button },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(
            modifier = Modifier
                .size(184.dp)
                .then(if (busy) Modifier.breathingHalo(halo) else Modifier.staticHalo(halo, if (on) 0.38f else 0.16f))
                .then(if (busy) Modifier.spinningArc(accent.connecting) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .graphicsLayer {
                        scaleX = pulse.value
                        scaleY = pulse.value
                    }
                    .drawBehind {
                        drawCircle(Brush.linearGradient(listOf(fillStart, fillEnd)))
                        if (!on && !busy) {
                            // Контур-градиент подсказывает, что сферу можно нажать.
                            drawCircle(
                                Brush.sweepGradient(listOf(ringStart, ringEnd, ringStart)),
                                radius = size.minDimension / 2 - 1.5.dp.toPx(),
                                style = Stroke(3.dp.toPx()),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(52.dp))
            }
        }
        if (action != HomeAction.None) {
            Text(
                action.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun Modifier.staticHalo(color: Color, alpha: Float): Modifier = drawBehind {
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent)),
        radius = size.minDimension / 2,
    )
}

@Composable
private fun Modifier.breathingHalo(color: Color): Modifier {
    val transition = rememberInfiniteTransition(label = "halo")
    val alpha by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(1_100), RepeatMode.Reverse),
        label = "haloAlpha",
    )
    return drawBehind {
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent)),
            radius = size.minDimension / 2,
        )
    }
}

@Composable
private fun Modifier.spinningArc(color: Color): Modifier {
    val transition = rememberInfiniteTransition(label = "arc")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "arcAngle",
    )
    return drawBehind {
        val stroke = 4.dp.toPx()
        val inset = size.minDimension * 0.12f
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** Таймер сессии обновляет только сам себя, раз в секунду. */
@Composable
private fun ElapsedTime(sinceEpochMillis: Long) {
    val now by produceState(System.currentTimeMillis(), sinceEpochMillis) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            ZapretIcons.Hourglass,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = ZapretColors.accent.session,
        )
        Text(
            formatDuration((now - sinceEpochMillis).coerceAtLeast(0)),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ---------------------------------------------------------------- server

@Composable
private fun ServerCard(server: HomeServerUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        enabled = server.switchable,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .testTag("home-server"),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (server.flag != null) {
                    // Тот же встроенный шрифт флагов, что и в списке серверов: на части
                    // прошивок системный эмодзи-шрифт рисует флаги буквами.
                    FlagBadge(server.flag, size = 30.dp)
                } else {
                    Text(
                        server.monogram,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Сервер",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(server.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(server.protocol.takeIf(String::isNotBlank), server.modeLabel).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                server.latency?.let { badge ->
                    AnimatedContent(
                        targetState = badge,
                        transitionSpec = { fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(Motion.QUICK)) },
                        label = "latency",
                    ) { ToneBadge(it.text, it.tone) }
                }
                if (server.switchable) {
                    Icon(
                        ZapretIcons.Chevron,
                        contentDescription = "Сменить сервер",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- tiles

@Composable
private fun SpeedTiles(stats: () -> VpnSessionStats, server: HomeServerUi?, modifier: Modifier = Modifier) {
    val accent = ZapretColors.accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        RateTile("Загрузка", accent.download, ZapretIcons.Download, Modifier.weight(1f)) {
            "↓ ${formatRate(stats().samples.lastOrNull()?.downloadBytesPerSecond ?: 0)}"
        }
        RateTile("Отдача", accent.upload, ZapretIcons.Traffic, Modifier.weight(1f)) {
            "↑ ${formatRate(stats().samples.lastOrNull()?.uploadBytesPerSecond ?: 0)}"
        }
        RateTile("Пинг", accent.ping, ZapretIcons.Bolt, Modifier.weight(1f)) {
            server?.latency?.text?.substringBefore(" · ") ?: "—"
        }
    }
}

/** Плитка с цветной иконкой; значение — лямбда, чтобы тик трафика трогал только её текст. */
@Composable
private fun RateTile(
    label: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    value: () -> String,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent)))
            }
            .padding(Spacing.m)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(color.copy(alpha = 0.20f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TileValue(value)
    }
}

@Composable
private fun TileValue(value: () -> String) {
    Text(
        value(),
        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.SemiBold,
    )
}

// ---------------------------------------------------------------- traffic

@Composable
private fun TrafficCard(stats: () -> VpnSessionStats, modifier: Modifier = Modifier) {
    val accent = ZapretColors.accent
    ZapretCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text("Трафик", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            LegendDot("загрузка", accent.download)
            LegendDot("отдача", accent.upload)
        }
        Box(contentAlignment = Alignment.Center) {
            TrafficChart(samples = { stats().samples }, download = accent.download, upload = accent.upload)
            ChartPlaceholder(stats)
        }
        TrafficTotals(stats, accent.download, accent.upload)
    }
}

/** Первые секунды после подключения точек ещё нет — пустая рамка выглядела бы поломкой. */
@Composable
private fun ChartPlaceholder(stats: () -> VpnSessionStats) {
    if (stats().samples.size >= 2) return
    Text(
        "График появится через пару секунд",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrafficTotals(stats: () -> VpnSessionStats, download: Color, upload: Color) {
    val current = stats()
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "За сессию",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text("↓ ${formatBytes(current.downloadTotalBytes)}", color = download, style = MaterialTheme.typography.labelLarge)
        Text("↑ ${formatBytes(current.uploadTotalBytes)}", color = upload, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * График за 60 секунд: линия загрузки с градиентной заливкой и линия отдачи.
 * Масштаб по пику меняется плавно (одна короткая анимация на изменение), а
 * Path переиспользуются — новые точки рисуются без перекомпозиции.
 */
@Composable
private fun TrafficChart(samples: () -> List<TrafficSample>, download: Color, upload: Color) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val peakTarget = max(1L, samples().maxOfOrNull { max(it.downloadBytesPerSecond, it.uploadBytesPerSecond) } ?: 1L)
    val peak by animateFloatAsState(peakTarget.toFloat(), tween(Motion.EMPHASIS), label = "chartPeak")
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .semantics { contentDescription = "График загрузки и отдачи за последние 60 секунд" }
            .drawWithCache {
                val downloadLine = Path()
                val downloadFill = Path()
                val uploadLine = Path()
                val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val fill = Brush.verticalGradient(listOf(download.copy(alpha = 0.35f), download.copy(alpha = 0f)))
                onDrawBehind {
                    for (row in 1..3) {
                        val y = size.height * row / 4f
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    val points = samples()
                    if (points.size < 2) return@onDrawBehind
                    val scale = peak.coerceAtLeast(1f)
                    val step = size.width / (points.size - 1)
                    fun Path.trace(value: (TrafficSample) -> Long) {
                        rewind()
                        points.forEachIndexed { index, sample ->
                            val x = step * index
                            val y = size.height * (1f - (value(sample) / scale).coerceIn(0f, 1f))
                            if (index == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    downloadLine.trace(TrafficSample::downloadBytesPerSecond)
                    downloadFill.rewind()
                    downloadFill.addPath(downloadLine)
                    downloadFill.lineTo(size.width, size.height)
                    downloadFill.lineTo(0f, size.height)
                    downloadFill.close()
                    uploadLine.trace(TrafficSample::uploadBytesPerSecond)
                    drawPath(downloadFill, fill)
                    drawPath(downloadLine, download, style = stroke)
                    drawPath(uploadLine, upload, style = stroke)
                }
            },
    ) {}
}

// ---------------------------------------------------------------- network

@Composable
private fun NetworkCard(server: HomeServerUi?, stats: () -> VpnSessionStats, modifier: Modifier = Modifier) {
    val accent = ZapretColors.accent
    ZapretCard(modifier = modifier) {
        Text("Сеть", style = MaterialTheme.typography.titleMedium)
        NetworkFact(ZapretIcons.Globe, accent.session, "IP выхода") { stats().externalIp ?: "определяем…" }
        if (server != null) {
            NetworkFact(ZapretIcons.Bolt, accent.ping, "Через сервер") { "Relay HTTPS · ${formatLatency(server.relay)}" }
            NetworkFact(ZapretIcons.Route, accent.download, "До сервера") { "ICMP · ${formatLatency(server.icmp)}" }
        }
    }
}

@Composable
private fun NetworkFact(icon: ImageVector, color: Color, label: String, value: () -> String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(color.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

// ---------------------------------------------------------------- shortcuts

@Composable
private fun Shortcuts(ui: HomeUi, callbacks: HomeCallbacks, modifier: Modifier = Modifier) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val accent = ZapretColors.accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (ui.preset != null) {
            ShortcutTile(
                title = "Режим",
                value = ui.preset.title,
                detail = ui.preset.detail,
                icon = ZapretIcons.Route,
                color = accent.heroEnd,
                onClick = { pickerOpen = true },
                modifier = Modifier.weight(1f),
            )
        }
        ShortcutTile(
            title = "Приложения",
            value = ui.appsSummary,
            detail = null,
            icon = ZapretIcons.Apps,
            color = accent.session,
            onClick = callbacks.onOpenRouting,
            modifier = Modifier.weight(1f),
        )
    }
    if (pickerOpen && ui.preset != null) {
        PresetPicker(
            selected = ui.preset,
            onSelect = callbacks.onSelectPreset,
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun ShortcutTile(
    title: String,
    value: String,
    detail: String?,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.m),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(color.copy(alpha = 0.20f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                }
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(ZapretIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- error

/**
 * Ошибка подключения: сначала понятное объяснение и что делать, затем код
 * для поддержки. Технический текст скрыт под «Подробности», а диагностику
 * можно отправить прямо отсюда.
 */
@Composable
private fun VpnErrorDetails(
    error: VpnConnectionState.Error,
    context: VpnErrorContext,
    onCreateDiagnosticShare: suspend () -> Intent,
) {
    val code = error.code.ifBlank { "VPN-000" }
    val explanation = remember(code, error.technicalDetail, context) {
        VpnErrorExplainer.explain(code, error.technicalDetail, context)
    }
    var detailsOpen by rememberSaveable(error) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            .padding(Spacing.m)
            .testTag("vpn-error")
            .animateContentSize(tween(Motion.STANDARD)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            explanation.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
        Text(explanation.hint, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Код: $code",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { detailsOpen = !detailsOpen }) {
                Text(if (detailsOpen) "Скрыть подробности" else "Подробности")
            }
        }
        if (detailsOpen) {
            Text(
                error.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error.technicalDetail?.takeIf { it != error.message }?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DiagnosticShareButton(
            label = "Отправить диагностику",
            onCreateDiagnosticShare = onCreateDiagnosticShare,
            modifier = Modifier.fillMaxWidth(),
            testTag = "vpn-error-share-diagnostics",
        )
    }
}
