package com.myappvpn.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AetherDarkColorScheme = darkColorScheme(
    primary = AetherCyan,
    onPrimary = AetherTextOnPrimary,
    primaryContainer = AetherCyanGlow,
    onPrimaryContainer = AetherCyan,

    secondary = AetherPurple,
    onSecondary = AetherTextPrimary,
    secondaryContainer = AetherPurpleGlow,
    onSecondaryContainer = AetherPurple,

    tertiary = AetherConnected,
    onTertiary = AetherTextOnPrimary,

    background = AetherBackground,
    onBackground = AetherTextPrimary,

    surface = AetherSurface,
    onSurface = AetherTextPrimary,
    surfaceVariant = AetherSurfaceVariant,
    onSurfaceVariant = AetherTextSecondary,
    surfaceContainer = AetherSurfaceContainer,
    surfaceContainerHigh = AetherSurfaceVariant,

    error = AetherDisconnected,
    onError = AetherTextPrimary,
    errorContainer = AetherDisconnectedGlow,
    onErrorContainer = AetherDisconnected,

    outline = AetherBorder,
    outlineVariant = AetherDivider,

    inverseSurface = AetherTextPrimary,
    inverseOnSurface = AetherBackground,
    inversePrimary = AetherCyanDim,

    scrim = androidx.compose.ui.graphics.Color(0xCC000000)
)

@Composable
fun MyAppVPNTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = AetherBackground.toArgb()
            window.navigationBarColor = AetherBackground.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = AetherDarkColorScheme,
        typography = AetherTypography,
        content = content
    )
}

