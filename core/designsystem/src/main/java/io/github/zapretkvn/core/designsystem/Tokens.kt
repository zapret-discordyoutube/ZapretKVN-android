package io.github.zapretkvn.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Сетка 4dp: поля экрана 16, внутри карточки 16, между карточками 12. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val screen = 16.dp
    val cardGap = 12.dp
}

/** Длительности анимаций; системный масштаб анимаций Compose применяет сам. */
object Motion {
    const val QUICK = 150
    const val STANDARD = 220
    const val EMPHASIS = 300
}

/**
 * Тон состояния. Цвет никогда не единственный носитель смысла: рядом всегда
 * есть слово или иконка, тон только усиливает их.
 */
enum class Tone { Neutral, Good, Warning, Bad, Accent }

/**
 * Цвета состояний не берутся из динамической палитры: обои могут сделать
 * зелёный и красный неотличимыми. Контраст текста на контейнере ≥ 4.5:1.
 */
@Immutable
data class StatusColors(
    val good: Color,
    val onGoodContainer: Color,
    val goodContainer: Color,
    val warning: Color,
    val onWarningContainer: Color,
    val warningContainer: Color,
    val bad: Color,
    val onBadContainer: Color,
    val badContainer: Color,
)

internal val LightStatusColors = StatusColors(
    good = Color(0xFF1B6C3A),
    onGoodContainer = Color(0xFF00210D),
    goodContainer = Color(0xFFB4F1C4),
    warning = Color(0xFF7A5900),
    onWarningContainer = Color(0xFF261A00),
    warningContainer = Color(0xFFFFDF9C),
    bad = Color(0xFFB3261E),
    onBadContainer = Color(0xFF410002),
    badContainer = Color(0xFFFFDAD6),
)

internal val DarkStatusColors = StatusColors(
    good = Color(0xFF8DD6A2),
    onGoodContainer = Color(0xFFB4F1C4),
    goodContainer = Color(0xFF005228),
    warning = Color(0xFFF2C14A),
    onWarningContainer = Color(0xFFFFDF9C),
    warningContainer = Color(0xFF5C4300),
    bad = Color(0xFFFFB4AB),
    onBadContainer = Color(0xFFFFDAD6),
    badContainer = Color(0xFF93000A),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * Акценты графиков и плиток. Не зависят от обоев, чтобы скорость, отдача и
 * задержка всегда различались цветом (и всегда подписаны словами).
 */
@Immutable
data class AccentColors(
    val download: Color,
    val upload: Color,
    val ping: Color,
    val session: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val connecting: Color,
)

internal val LightAccentColors = AccentColors(
    download = Color(0xFF2F6BFF),
    upload = Color(0xFFFF6B3D),
    ping = Color(0xFF12A37A),
    session = Color(0xFF8B5CF6),
    heroStart = Color(0xFF3B6CFF),
    heroEnd = Color(0xFF14B8A6),
    connecting = Color(0xFFF5A524),
)

internal val DarkAccentColors = AccentColors(
    download = Color(0xFF6E9BFF),
    upload = Color(0xFFFF8A5C),
    ping = Color(0xFF3DDC97),
    session = Color(0xFFB794FF),
    heroStart = Color(0xFF4F7BFF),
    heroEnd = Color(0xFF22D3B6),
    connecting = Color(0xFFFFB547),
)

val LocalAccentColors = staticCompositionLocalOf { LightAccentColors }

/** На чистом чёрном тональные карточки почти не видны — им нужна тонкая обводка. */
val LocalAmoled = staticCompositionLocalOf { false }

object ZapretColors {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
    val accent: AccentColors
        @Composable @ReadOnlyComposable get() = LocalAccentColors.current
}
