package io.github.zapretkvn.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {
    @Test
    fun amoledSchemeUsesTrueBlackAndKeepsAccentColors() {
        val base = darkColorScheme(
            primary = Color(0xFFABCDEF),
            secondary = Color(0xFF123456),
        )

        val amoled = amoledColorScheme(base)

        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color.Black, amoled.surfaceContainerLowest)
        assertEquals(Color(0xFF0D0E10), amoled.surfaceContainer)
        assertEquals(Color(0xFFABCDEF), amoled.primary)
        assertEquals(Color(0xFF123456), amoled.secondary)
    }
}
