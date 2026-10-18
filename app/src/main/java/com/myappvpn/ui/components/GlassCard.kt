package com.myappvpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.myappvpn.ui.theme.AetherBorder
import com.myappvpn.ui.theme.AetherPalette

/** Pseudo-glass card (no blur): translucent fill, 1dp gradient edge, one soft shadow in light theme. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderColor: Color = AetherBorder,
    content: @Composable BoxScope.() -> Unit
) {
    val c = AetherPalette
    val shape = RoundedCornerShape(cornerRadius)
    val top = when {
        borderColor != c.border -> borderColor.copy(alpha = borderColor.alpha * 0.9f)
        c.isDark -> c.highlight.copy(alpha = 0.12f)
        else -> c.border
    }
    val shadowColor = Color.Black.copy(alpha = 0.08f)
    Box(
        modifier = modifier
            .then(
                if (c.isDark) Modifier
                else Modifier.shadow(2.dp, shape, ambientColor = shadowColor, spotColor = shadowColor)
            )
            .clip(shape)
            .background(c.glass.copy(alpha = if (c.isDark) 0.72f else 0.80f))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(top, borderColor)),
                shape = shape
            ),
        content = content
    )
}
