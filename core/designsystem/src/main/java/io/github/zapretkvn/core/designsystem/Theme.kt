package io.github.zapretkvn.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Fallback for Android 8–11 (no dynamic color): a full tonal scheme from the
// brand blue, so cards and containers do not collapse into one flat surface.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0057D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF006A6A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF1F0),
    onSecondaryContainer = Color(0xFF002020),
    tertiary = Color(0xFF4C5F7D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4E3FF),
    onTertiaryContainer = Color(0xFF061C36),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF80D5D4),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F4F),
    onSecondaryContainer = Color(0xFF9CF1F0),
    tertiary = Color(0xFFB4C7E8),
    onTertiary = Color(0xFF1E314C),
    tertiaryContainer = Color(0xFF344863),
    onTertiaryContainer = Color(0xFFD4E3FF),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF101318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF181B21),
    surfaceContainer = Color(0xFF1C1F25),
    surfaceContainerHigh = Color(0xFF272A30),
    surfaceContainerHighest = Color(0xFF32353B),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44474F),
)

fun amoledColorScheme(base: ColorScheme): ColorScheme = base.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF202124),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF08090A),
    surfaceContainer = Color(0xFF0D0E10),
    surfaceContainerHigh = Color(0xFF15171A),
    surfaceContainerHighest = Color(0xFF1D1F23),
    surfaceVariant = Color(0xFF1D1F23),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF303034),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    scrim = Color.Black,
)

val ZapretTypography = Typography()

val ZapretShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ZapretTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Dynamic palettes follow the wallpaper; recompute only when inputs change,
    // not on every theme recomposition.
    val configuration = LocalConfiguration.current
    val useDarkColors = darkTheme || amoledTheme
    val colors = remember(useDarkColors, amoledTheme, configuration) {
        val base = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useDarkColors ->
                dynamicDarkColorScheme(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
            useDarkColors -> DarkColors
            else -> LightColors
        }
        if (amoledTheme) amoledColorScheme(base) else base
    }
    val status = if (useDarkColors) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(
        LocalStatusColors provides status,
        LocalAmoled provides amoledTheme,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = ZapretTypography,
            shapes = ZapretShapes,
            content = content,
        )
    }
}
