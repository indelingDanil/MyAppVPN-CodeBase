package com.myappvpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.myappvpn.ui.theme.AetherBorder
import com.myappvpn.ui.theme.AetherSurface
import com.myappvpn.ui.theme.AetherSurfaceVariant

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderColor: Color = AetherBorder,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        AetherSurfaceVariant.copy(alpha = 0.8f),
                        AetherSurface.copy(alpha = 0.9f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        borderColor.copy(alpha = 0.4f),
                        borderColor.copy(alpha = 0.1f)
                    )
                ),
                shape = RoundedCornerShape(cornerRadius)
            ),
        content = content
    )
}
