package com.myappvpn.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Aether palette: neutral base + one calm blue accent; status colours only for status ──
//
// All tokens are opaque (or a fixed alpha for the *Soft ones), so `.copy(alpha = …)` at call sites
// keeps meaning "this colour at that opacity". Read them through the `Aether*` getters below —
// they follow the active (light/dark) palette.

@Immutable
data class AetherColors(
    val isDark: Boolean,
    // Surfaces
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceContainer: Color,
    /** Fill of translucent ("glass") surfaces, before alpha. */
    val glass: Color,
    // Accent
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    /** Accent used for small text/icons on surfaces (contrast-checked). */
    val accentText: Color,
    val onAccent: Color,
    // Status
    val connected: Color,
    val connectedSoft: Color,
    val connecting: Color,
    val error: Color,
    val errorSoft: Color,
    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // Lines
    val border: Color,
    val divider: Color,
    /** Top-edge highlight of glass surfaces. */
    val highlight: Color,
)

val DarkAetherColors = AetherColors(
    isDark = true,
    background = Color(0xFF0B0D10),
    surface = Color(0xFF14171C),
    surfaceVariant = Color(0xFF1C2027),
    surfaceContainer = Color(0xFF181B21),
    glass = Color(0xFF1C2027),
    accent = Color(0xFF4C8DFF),
    accentStrong = Color(0xFF3A78E8),
    accentSoft = Color(0x294C8DFF),
    accentText = Color(0xFF7AAAFF),
    onAccent = Color(0xFFFFFFFF),
    connected = Color(0xFF2FBF71),
    connectedSoft = Color(0x292FBF71),
    connecting = Color(0xFFF5A524),
    error = Color(0xFFF2555A),
    errorSoft = Color(0x29F04438),
    // Contrast (WCAG) on background: primary 17:1, secondary 7.6:1, tertiary 5.0:1.
    textPrimary = Color(0xFFF2F4F7),
    textSecondary = Color(0xFF9AA3AF),
    textTertiary = Color(0xFF7C8594),
    border = Color(0xFF262A31),
    divider = Color(0xFF1E2228),
    highlight = Color(0xFFFFFFFF),
)

val LightAetherColors = AetherColors(
    isDark = false,
    background = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEFF3),
    surfaceContainer = Color(0xFFF0F2F5),
    glass = Color(0xFFFFFFFF),
    accent = Color(0xFF2F6FE4),
    accentStrong = Color(0xFF245CC4),
    accentSoft = Color(0x1F2F6FE4),
    accentText = Color(0xFF2560CF),
    onAccent = Color(0xFFFFFFFF),
    connected = Color(0xFF1F9D5A),
    connectedSoft = Color(0x1F1F9D5A),
    connecting = Color(0xFFB7740C),
    error = Color(0xFFD92D20),
    errorSoft = Color(0x1FD92D20),
    // Contrast (WCAG) on background: primary 18:1, secondary 5.6:1, tertiary 3.6:1 (hints only).
    textPrimary = Color(0xFF0B0D10),
    textSecondary = Color(0xFF545D6B),
    textTertiary = Color(0xFF6E7684),
    border = Color(0xFFDDE1E7),
    divider = Color(0xFFE6E9EE),
    highlight = Color(0xFFFFFFFF),
)

val LocalAetherColors = staticCompositionLocalOf { DarkAetherColors }

/** Current palette. */
val AetherPalette: AetherColors
    @Composable @ReadOnlyComposable get() = LocalAetherColors.current

// ── Named tokens (kept from the original dark theme so call sites stay unchanged) ──

// Backgrounds
val AetherBackground: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.background
val AetherSurface: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.surface
val AetherSurfaceVariant: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.surfaceVariant
val AetherSurfaceContainer: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.surfaceContainer

// Primary accent (historical name: was neon cyan)
val AetherCyan: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.accent
val AetherCyanDim: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.accentStrong
val AetherCyanGlow: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.accentSoft

// Secondary (historical: purple) — folded into the single accent
val AetherPurple: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.accent
val AetherPurpleDim: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.accentStrong
val AetherPurpleGlow: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.accentSoft
val AetherPurpleLight: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.accentText

// States
val AetherConnected: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.connected
val AetherConnectedGlow: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.connectedSoft
val AetherConnecting: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.connecting
val AetherDisconnected: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.error
val AetherDisconnectedGlow: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.errorSoft

// Latency badges
val LatencyGood: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.connected
val LatencyMedium: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.connecting
val LatencyBad: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.error

// Text
val AetherTextPrimary: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.textPrimary
val AetherTextSecondary: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.textSecondary
val AetherTextTertiary: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.textTertiary
val AetherTextOnPrimary: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.onAccent

// Borders / dividers
val AetherBorder: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.border
val AetherDivider: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.divider

// Gradient stops (now flat: the redesign drops decorative gradients)
val GradientCyanStart: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.accent
val GradientCyanEnd: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.accent
val GradientBackgroundStart: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.background
val GradientBackgroundEnd: Color @Composable @ReadOnlyComposable get() = LocalAetherColors.current.background
