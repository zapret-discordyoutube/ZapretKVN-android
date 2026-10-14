package io.github.zapretkvn.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.zapretkvn.android.vpn.LATENCY_FRESHNESS_MILLIS
import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencyUnsupportedReason
import io.github.zapretkvn.android.vpn.withFreshness
import io.github.zapretkvn.core.uimodel.LATENCY_OLD_MILLIS
import io.github.zapretkvn.core.uimodel.formatAge
import io.github.zapretkvn.core.uimodel.formatPing

/*
 * Текст задержки с учётом свежести замера. Используется строками серверов
 * и тестами; чистые форматтеры живут в :core:ui-model.
 */

internal fun formatLatency(
    rawState: LatencyProbeState?,
    nowEpochMillis: Long = System.currentTimeMillis(),
): String {
    if (rawState == null) return "Не проверено"
    val state = rawState.withFreshness(nowEpochMillis, rawState.sampleIdentity())
    return when (state) {
        LatencyProbeState.NotTested -> "Не проверено"
        is LatencyProbeState.Running -> state.previous?.let {
            "Проверяется · было ${formatPing(it.millis.toLong())}"
        } ?: "Проверяется"
        is LatencyProbeState.Success -> formatPing(state.sample.millis.toLong())
        is LatencyProbeState.Failed -> {
            val label = when (state.reason) {
                LatencyFailure.NoResponse -> "Нет ответа"
                LatencyFailure.Dns -> "Ошибка DNS"
                LatencyFailure.Failed -> "Ошибка"
            }
            val failedAge = state.failedAtEpochMillis?.let { nowEpochMillis - it }
            when {
                failedAge != null && failedAge >= LATENCY_FRESHNESS_MILLIS ->
                    "$label · ${formatAge(failedAge)}"
                state.previous != null -> "$label · было ${formatPing(state.previous.millis.toLong())}"
                else -> label
            }
        }
        is LatencyProbeState.Unsupported -> when (state.reason) {
            LatencyUnsupportedReason.MissingEndpoint -> "Нет endpoint"
            LatencyUnsupportedReason.NestedGroup -> "Группа · откройте отдельно"
        }
        is LatencyProbeState.Stale -> {
            val age = nowEpochMillis - state.sample.measuredAtEpochMillis
            if (age < LATENCY_FRESHNESS_MILLIS) {
                // Свежий замер из другой сети Android.
                "Устарело · ${formatPing(state.sample.millis.toLong())}"
            } else {
                "${formatPing(state.sample.millis.toLong())} · ${formatAge(age)}"
            }
        }
    }
}

@Composable
internal fun latencyColor(state: LatencyProbeState?, nowEpochMillis: Long, default: Color): Color =
    if (isLatencyOld(state, nowEpochMillis)) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    } else {
        default
    }

internal fun isLatencyOld(state: LatencyProbeState?, nowEpochMillis: Long): Boolean {
    val measuredAt = when (state) {
        null,
        LatencyProbeState.NotTested,
        is LatencyProbeState.Unsupported,
        is LatencyProbeState.Running,
        -> null
        is LatencyProbeState.Success -> state.sample.measuredAtEpochMillis
        is LatencyProbeState.Stale -> state.sample.measuredAtEpochMillis
        is LatencyProbeState.Failed -> state.failedAtEpochMillis
    } ?: return false
    return nowEpochMillis - measuredAt >= LATENCY_OLD_MILLIS
}

private fun LatencyProbeState.sampleIdentity(): String? = when (this) {
    LatencyProbeState.NotTested,
    is LatencyProbeState.Unsupported,
    -> null
    is LatencyProbeState.Running -> previous?.networkIdentity
    is LatencyProbeState.Success -> sample.networkIdentity
    is LatencyProbeState.Failed -> previous?.networkIdentity
    is LatencyProbeState.Stale -> sample.networkIdentity
}
