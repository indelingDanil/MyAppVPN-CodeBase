package com.myappvpn.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.myappvpn.ui.theme.AetherColors
import com.myappvpn.ui.theme.AetherPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Backdrop source for [GlassSurface] (set on the NavHost container); null → no real blur. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Glass container: real backdrop blur on API 31+ (not in power-save mode), otherwise a dense
 * translucent fill. Both get a 1dp border with a soft top highlight.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val c = AetherPalette
    val hazeState = LocalHazeState.current
    val powerSave = rememberPowerSaveMode()
    val blur = hazeState != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !powerSave

    val fill = if (blur) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = c.background,
                tint = HazeTint(c.glass.copy(alpha = if (c.isDark) 0.72f else 0.70f)),
                blurRadius = 24.dp,
                noiseFactor = 0f
            )
        )
    } else {
        Modifier.background(c.glass.copy(alpha = 0.92f))
    }

    Box(
        modifier = modifier
            .clip(shape)
            .then(fill)
            .border(width = 1.dp, brush = glassBorderBrush(c), shape = shape),
        content = content
    )
}

/** 1dp glass edge: a faint highlight at the top fading into the regular border. */
fun glassBorderBrush(c: AetherColors): Brush = Brush.verticalGradient(
    colors = listOf(
        if (c.isDark) c.highlight.copy(alpha = 0.12f) else c.border,
        c.border
    )
)

@Composable
private fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val pm = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var powerSave by remember { mutableStateOf(pm?.isPowerSaveMode == true) }
    DisposableEffect(context, pm) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                powerSave = pm?.isPowerSaveMode == true
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return powerSave
}
