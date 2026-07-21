package com.myappvpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myappvpn.service.VpnState
import com.myappvpn.ui.theme.*

@Composable
fun ConnectButton(
    vpnState: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp
) {
    val isConnected = vpnState == VpnState.CONNECTED
    val isConnecting = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING ||
        vpnState == VpnState.BLOCKED

    // Pulse animation for connecting state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAlpha"
    )
    val ringRadius by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRadius"
    )

    // Arc rotation for connecting state
    val arcAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing)
        ),
        label = "arcAngle"
    )

    val primaryColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> AetherConnected
            VpnState.CONNECTING -> AetherConnecting
            VpnState.BLOCKED -> AetherConnecting
            VpnState.ERROR -> AetherDisconnected
            else -> AetherCyan
        },
        animationSpec = tween(500),
        label = "color"
    )

    val glowColor = primaryColor.copy(alpha = 0.3f)

    val buttonLabel = when (vpnState) {
        VpnState.CONNECTED -> "CONNECTED"
        VpnState.CONNECTING -> "CONNECTING"
        VpnState.DISCONNECTING -> "STOPPING"
        VpnState.BLOCKED -> "RECONNECTING"
        VpnState.ERROR -> "ERROR"
        else -> "CONNECT"
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size + 60.dp)  // extra space for rings
    ) {
        // Outer pulsing rings (only when connecting)
        if (isConnecting || isConnected) {
            Canvas(modifier = Modifier.size(size + 60.dp)) {
                val canvasCenter = Offset(this.size.width / 2f, this.size.height / 2f)
                val maxRadius = (this.size.minDimension / 2f)
                drawCircle(
                    color = glowColor.copy(alpha = ringAlpha * 0.5f),
                    radius = maxRadius * (0.7f + ringRadius * 0.3f),
                    center = canvasCenter,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = glowColor.copy(alpha = ringAlpha * 0.3f),
                    radius = maxRadius * (0.8f + ringRadius * 0.2f),
                    center = canvasCenter,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // Main button circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .scale(if (isConnecting) pulseScale else 1f)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    )
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
        ) {
            // Spinning arc for connecting state
            if (isConnecting) {
                Canvas(modifier = Modifier.size(size - 8.dp)) {
                    val strokeWidth = 3.dp.toPx()
                    drawArc(
                        color = primaryColor,
                        startAngle = arcAngle,
                        sweepAngle = 120f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            // Inner border circle
            Canvas(modifier = Modifier.size(size - 8.dp)) {
                drawCircle(
                    color = primaryColor.copy(alpha = if (isConnecting) 0.3f else 0.6f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Glow background
            Canvas(modifier = Modifier.size(size - 20.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )
                )
            }

            // Button label
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = buttonLabel,
                    color = primaryColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                if (isConnected) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "TAP TO STOP",
                        color = primaryColor.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp
                    )
                }
            }
        }
    }
}
