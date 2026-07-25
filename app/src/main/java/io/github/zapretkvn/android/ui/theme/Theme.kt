package io.github.zapretkvn.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF0057D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF006A6A),
    secondaryContainer = Color(0xFF9CF1F0),
    tertiary = Color(0xFF4C5F7D),
    background = Color(0xFFF9F9FF),
    surface = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFE1E2EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF80D5D4),
    secondaryContainer = Color(0xFF004F4F),
    tertiary = Color(0xFFB4C7E8),
    background = Color(0xFF101318),
    surface = Color(0xFF101318),
    surfaceVariant = Color(0xFF44474F),
)

internal fun amoledColorScheme(base: ColorScheme): ColorScheme = base.copy(
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

@Composable
fun ZapretTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDarkColors = darkTheme || amoledTheme
    val baseColors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useDarkColors ->
            dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        useDarkColors -> DarkColors
        else -> LightColors
    }
    val colors = if (amoledTheme) amoledColorScheme(baseColors) else baseColors

    MaterialTheme(
        colorScheme = colors,
        typography = ZapretTypography,
        content = content,
    )
}
