package io.github.zapretkvn.android.ui.home

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import io.github.zapretkvn.android.routing.RoutingPreset
import io.github.zapretkvn.android.ui.DiagnosticShareButton
import io.github.zapretkvn.android.ui.formatLatency
import io.github.zapretkvn.android.ui.routing.PresetPicker
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.TrafficSample
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnSessionStats
import io.github.zapretkvn.core.designsystem.CardTitle
import io.github.zapretkvn.core.designsystem.FactRow
import io.github.zapretkvn.core.designsystem.Monogram
import io.github.zapretkvn.core.designsystem.Motion
import io.github.zapretkvn.core.designsystem.Spacing
import io.github.zapretkvn.core.designsystem.StatusDot
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.designsystem.ToneBadge
import io.github.zapretkvn.core.designsystem.ZapretCard
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
)

/**
 * Главная отвечает на три вопроса сверху вниз: что сейчас с VPN, через какой
 * сервер, как идёт трафик. Частые данные (трафик, часы) читаются только в
 * своих листовых элементах — экран целиком раз в секунду не перерисовывается.
 */
@Composable
internal fun HomeScreen(
    ui: HomeUi,
    stats: () -> VpnSessionStats,
    callbacks: HomeCallbacks,
    contentPadding: PaddingValues,
) {
    val connected = ui.vpnState as? VpnConnectionState.Connected
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home-list"),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = contentPadding.calculateTopPadding() + Spacing.l,
            bottom = contentPadding.calculateBottomPadding() + Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
    ) {
        item(key = "hero", contentType = "hero") { HeroCard(ui, callbacks) }
        if (connected != null) {
            item(key = "network", contentType = "card") {
                NetworkCard(server = ui.server, stats = stats)
            }
            item(key = "traffic", contentType = "card") { TrafficCard(stats) }
        }
        if (ui.preset != null) {
            item(key = "mode", contentType = "card") {
                var pickerOpen by rememberSaveable { mutableStateOf(false) }
                ModeCard(preset = ui.preset, onClick = { pickerOpen = true })
                if (pickerOpen) {
                    PresetPicker(
                        selected = ui.preset,
                        onSelect = callbacks.onSelectPreset,
                        onDismiss = { pickerOpen = false },
                    )
                }
            }
        }
        item(key = "apps", contentType = "card") {
            ZapretCard(onClick = callbacks.onOpenRouting) {
                CardTitle("Приложения", icon = ZapretIcons.Apps, action = {
                    Icon(ZapretIcons.Chevron, contentDescription = null)
                })
                Text(
                    ui.appsSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

@Composable
private fun HeroCard(ui: HomeUi, callbacks: HomeCallbacks) {
    val tone = statusTone(ui.vpnState)
    val container by animateColorAsState(
        when (tone) {
            Tone.Good -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            Tone.Bad -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(Motion.EMPHASIS),
        label = "heroContainer",
    )
    ZapretCard(
        containerColor = container,
        modifier = Modifier.animateContentSize(tween(Motion.STANDARD)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor by animateColorAsState(toneColor(tone), tween(Motion.STANDARD), label = "dot")
            StatusDot(tone, color = dotColor)
            Text(
                statusLabel(ui.vpnState),
                modifier = Modifier
                    .padding(start = Spacing.s)
                    .weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            (ui.vpnState as? VpnConnectionState.Connected)?.let {
                ElapsedTime(sinceEpochMillis = it.connectedAtEpochMillis)
            }
        }
        ui.profileName?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PowerButton(
            stage = ui.stage,
            action = ui.primary,
            onClick = { callbacks.onAction(ui.primary) },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = Spacing.s),
        )

        AnimatedContent(
            targetState = ui.headline,
            transitionSpec = {
                (fadeIn(tween(Motion.STANDARD)) + slideInVertically(tween(Motion.STANDARD)) { it / 6 }) togetherWith
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

        if (ui.server != null && ui.stage != HomeStage.NoProfile) {
            ServerPill(ui.server, onClick = callbacks.onOpenServers)
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
                )
            }
            is VpnConnectionState.Starting -> Text(
                "Проверка DNS и доступа может занять до 20 секунд.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
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

/**
 * Круглая кнопка: цвет плавно меняется с состоянием, при подключении вокруг
 * бежит одна дуга (рисуется в drawBehind — перерисовка без перекомпозиции),
 * при успехе — один короткий «вдох».
 */
@Composable
private fun PowerButton(
    stage: HomeStage,
    action: HomeAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = stage == HomeStage.Starting || stage == HomeStage.Reconnecting || stage == HomeStage.Stopping
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (stage) {
            HomeStage.Connected -> scheme.primary
            HomeStage.Error -> scheme.errorContainer
            HomeStage.Starting, HomeStage.Reconnecting, HomeStage.Stopping -> scheme.primaryContainer
            else -> scheme.surfaceContainerHighest
        },
        tween(Motion.EMPHASIS),
        label = "powerContainer",
    )
    val content by animateColorAsState(
        when (stage) {
            HomeStage.Connected -> scheme.onPrimary
            HomeStage.Error -> scheme.onErrorContainer
            HomeStage.Starting, HomeStage.Reconnecting, HomeStage.Stopping -> scheme.onPrimaryContainer
            else -> scheme.primary
        },
        tween(Motion.EMPHASIS),
        label = "powerContent",
    )
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(stage) {
        if (stage == HomeStage.Connected) {
            pulse.animateTo(1.06f, spring(stiffness = 600f))
            pulse.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f))
        }
    }
    val ringColor = scheme.primary
    val icon: ImageVector = when (action) {
        HomeAction.AddProfile -> ZapretIcons.Add
        HomeAction.SelectApps, HomeAction.SelectDirectApps -> ZapretIcons.Apps
        else -> ZapretIcons.Power
    }
    Column(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.s)
            .semantics(mergeDescendants = true) { role = Role.Button },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(
            modifier = Modifier
                .size(116.dp)
                .then(if (busy) Modifier.spinningRing(ringColor) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = container,
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = pulse.value
                        scaleY = pulse.value
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(40.dp))
                }
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

@Composable
private fun Modifier.spinningRing(color: androidx.compose.ui.graphics.Color): Modifier {
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "ringAngle",
    )
    return drawBehind {
        val stroke = 4.dp.toPx()
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
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
    Text(
        formatDuration((now - sinceEpochMillis).coerceAtLeast(0)),
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ServerPill(server: HomeServerUi, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = server.switchable,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home-server"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (server.flag != null) {
                Monogram(server.flag, emoji = true)
            } else {
                Monogram(server.monogram)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(server.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(server.protocol, server.modeLabel).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

@Composable
private fun NetworkCard(server: HomeServerUi?, stats: () -> VpnSessionStats) {
    ZapretCard {
        CardTitle("Сеть", icon = ZapretIcons.Globe)
        // IP приходит редко, но читается через лямбду, чтобы не тянуть за собой экран.
        ExternalIpRow(stats)
        if (server != null) {
            Text(
                "Relay HTTPS · ${formatLatency(server.relay)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "ICMP · ${formatLatency(server.icmp)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExternalIpRow(stats: () -> VpnSessionStats) {
    FactRow("IP выхода", stats().externalIp ?: "определяем…")
}

@Composable
private fun TrafficCard(stats: () -> VpnSessionStats) {
    ZapretCard {
        CardTitle("Трафик", icon = ZapretIcons.Traffic)
        TrafficRates(stats)
        TrafficChart(samples = { stats().samples })
        TrafficTotals(stats)
    }
}

@Composable
private fun TrafficRates(stats: () -> VpnSessionStats) {
    val last = stats().samples.lastOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
        RateValue("Загрузка", "↓ ${formatRate(last?.downloadBytesPerSecond ?: 0)}", Modifier.weight(1f))
        RateValue("Отдача", "↑ ${formatRate(last?.uploadBytesPerSecond ?: 0)}", Modifier.weight(1f))
    }
}

@Composable
private fun RateValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TrafficTotals(stats: () -> VpnSessionStats) {
    val current = stats()
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "За сессию",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "↓ ${formatBytes(current.downloadTotalBytes)}",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "↑ ${formatBytes(current.uploadTotalBytes)}",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * График за 60 секунд. Path создаётся один раз и переиспользуется; новые
 * точки читаются внутри фазы рисования — это перерисовка без перекомпозиции.
 */
@Composable
private fun TrafficChart(samples: () -> List<TrafficSample>) {
    val downloadColor = MaterialTheme.colorScheme.primary
    val uploadColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .semantics { contentDescription = "График загрузки и отдачи за последние 60 секунд" }
            .drawWithCache {
                val download = Path()
                val upload = Path()
                val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                onDrawBehind {
                    drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height))
                    val points = samples()
                    if (points.size < 2) return@onDrawBehind
                    val peak = max(1L, points.maxOf { max(it.downloadBytesPerSecond, it.uploadBytesPerSecond) })
                    fun Path.trace(value: (TrafficSample) -> Long) {
                        rewind()
                        points.forEachIndexed { index, sample ->
                            val x = size.width * index / max(1, points.size - 1)
                            val y = size.height * (1f - value(sample).toFloat() / peak.toFloat())
                            if (index == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    download.trace(TrafficSample::downloadBytesPerSecond)
                    upload.trace(TrafficSample::uploadBytesPerSecond)
                    drawPath(download, downloadColor, style = stroke)
                    drawPath(upload, uploadColor, style = stroke)
                }
            },
    ) {}
}

@Composable
private fun ModeCard(preset: RoutingPreset, onClick: () -> Unit) {
    ZapretCard(onClick = onClick) {
        CardTitle("Режим", icon = ZapretIcons.Route, action = {
            Icon(ZapretIcons.Chevron, contentDescription = null)
        })
        Text(preset.title, style = MaterialTheme.typography.titleSmall)
        Text(
            preset.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
