package com.myappvpn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** App theme preference (stored in DataStore as its name). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private fun colorSchemeFor(c: AetherColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.accentText,

        secondary = c.accent,
        onSecondary = c.onAccent,
        secondaryContainer = c.accentSoft,
        onSecondaryContainer = c.accentText,

        tertiary = c.connected,
        onTertiary = c.onAccent,

        background = c.background,
        onBackground = c.textPrimary,

        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceVariant,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.background,
        surfaceContainerLow = c.surfaceContainer,
        surfaceContainer = c.surfaceContainer,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surfaceVariant,

        error = c.error,
        onError = Color.White,
        errorContainer = c.errorSoft,
        onErrorContainer = c.error,

        outline = c.border,
        outlineVariant = c.divider,

        inverseSurface = c.textPrimary,
        inverseOnSurface = c.background,
        inversePrimary = c.accentStrong,

        scrim = Color(0x99000000),
    )
}

private val AetherShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * System bar colours/icons are handled by `enableEdgeToEdge` in MainActivity (bars are transparent,
 * content draws behind them).
 */
@Composable
fun MyAppVPNTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkAetherColors else LightAetherColors
    val scheme = remember(colors) { colorSchemeFor(colors) }
    val typography = remember(colors) { aetherTypography(colors) }
    CompositionLocalProvider(LocalAetherColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = AetherShapes,
            content = content
        )
    }
}
