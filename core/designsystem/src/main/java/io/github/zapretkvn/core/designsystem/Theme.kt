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

// Фирменная палитра — по умолчанию на всех версиях Android: динамические
// цвета на монохромных обоях превращают графики и акценты в серо-белые.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BEA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001849),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F5EE),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE3FF),
    onTertiaryContainer = Color(0xFF250059),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF171A23),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF171A23),
    surfaceVariant = Color(0xFFE1E4EF),
    onSurfaceVariant = Color(0xFF454A5A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F2F8),
    surfaceContainerHigh = Color(0xFFE9ECF4),
    surfaceContainerHighest = Color(0xFFE2E6EF),
    outline = Color(0xFF767B8C),
    outlineVariant = Color(0xFFC9CDDA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AA8FF),
    onPrimary = Color(0xFF00225F),
    primaryContainer = Color(0xFF2A47B8),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF4FDBC8),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF0B4F47),
    onSecondaryContainer = Color(0xFFC9F5EE),
    tertiary = Color(0xFFC4A8FF),
    onTertiary = Color(0xFF3B0A8C),
    tertiaryContainer = Color(0xFF4C2A9E),
    onTertiaryContainer = Color(0xFFEDE3FF),
    background = Color(0xFF0D1018),
    onBackground = Color(0xFFE3E5EE),
    surface = Color(0xFF0D1018),
    onSurface = Color(0xFFE3E5EE),
    surfaceVariant = Color(0xFF3F4455),
    onSurfaceVariant = Color(0xFFBFC4D4),
    surfaceContainerLowest = Color(0xFF080A10),
    surfaceContainerLow = Color(0xFF151925),
    surfaceContainer = Color(0xFF1A1F2C),
    surfaceContainerHigh = Color(0xFF232838),
    surfaceContainerHighest = Color(0xFF2D3344),
    outline = Color(0xFF8A8FA0),
    outlineVariant = Color(0xFF3F4455),
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
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Dynamic palettes follow the wallpaper; recompute only when inputs change,
    // not on every theme recomposition.
    val configuration = LocalConfiguration.current
    val useDarkColors = darkTheme || amoledTheme
    val colors = remember(useDarkColors, amoledTheme, dynamicColor, configuration) {
        val dynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val base = when {
            dynamic && useDarkColors -> dynamicDarkColorScheme(context)
            dynamic -> dynamicLightColorScheme(context)
            useDarkColors -> DarkColors
            else -> LightColors
        }
        if (amoledTheme) amoledColorScheme(base) else base
    }
    val status = if (useDarkColors) DarkStatusColors else LightStatusColors
    val accents = if (useDarkColors) DarkAccentColors else LightAccentColors

    CompositionLocalProvider(
        LocalStatusColors provides status,
        LocalAccentColors provides accents,
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
