package com.myappvpn.ui.theme

import androidx.compose.ui.graphics.Color

// ── Aether Dark Theme ──────────────────────────────────────────────────────────

// Backgrounds
val AetherBackground = Color(0xFF090E1A)
val AetherSurface = Color(0xFF111827)
val AetherSurfaceVariant = Color(0xFF1E2A3A)
val AetherSurfaceContainer = Color(0xFF162032)

// Primary — Cyan (connected state)
val AetherCyan = Color(0xFF00E5FF)
val AetherCyanDim = Color(0xFF00B4CC)
val AetherCyanGlow = Color(0x3300E5FF)

// Secondary — Purple
val AetherPurple = Color(0xFF7C3AED)
val AetherPurpleDim = Color(0xFF6D28D9)
val AetherPurpleGlow = Color(0x337C3AED)
// Use for purple *text* on dark surfaces (AetherPurple is only 3.1:1 on Surface)
val AetherPurpleLight = Color(0xFFA78BFA)

// States
val AetherConnected = Color(0xFF00E676)       // Green — connected
val AetherConnectedGlow = Color(0x3300E676)
val AetherConnecting = Color(0xFFFFB300)      // Amber — connecting
val AetherDisconnected = Color(0xFFFF5A69)    // Red — disconnected (≥4.5:1 on all surfaces)
val AetherDisconnectedGlow = Color(0x33FF4757)

// Latency badges
val LatencyGood = Color(0xFF00E676)    // < 100ms
val LatencyMedium = Color(0xFFFFB300)  // 100–300ms
val LatencyBad = Color(0xFFFF5A69)     // > 300ms

// Text
val AetherTextPrimary = Color(0xFFF1F5F9)
// Contrast (WCAG): Secondary 7.99:1 on Background / 7.35:1 on Surface;
// Tertiary 6.02:1 on Background / 5.54:1 on Surface / 4.53:1 on SurfaceVariant.
val AetherTextSecondary = Color(0xFF9AA8BC)
val AetherTextTertiary = Color(0xFF8291A7)
val AetherTextOnPrimary = Color(0xFF000000)

// Borders / dividers
val AetherBorder = Color(0xFF1E3A4A)
val AetherDivider = Color(0xFF1A2535)

// Gradient stops
val GradientCyanStart = Color(0xFF00E5FF)
val GradientCyanEnd = Color(0xFF7C3AED)
val GradientBackgroundStart = Color(0xFF090E1A)
val GradientBackgroundEnd = Color(0xFF0D1B2A)
