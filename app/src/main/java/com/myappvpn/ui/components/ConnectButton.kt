package com.myappvpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myappvpn.R
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
    // Any in-between state: spinner arc + dimmed inner ring.
    val isTransient = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING ||
        vpnState == VpnState.BLOCKED
    // Breathing pulse only while we're actively trying to (re)establish the tunnel.
    val isPulsing = vpnState == VpnState.CONNECTING || vpnState == VpnState.BLOCKED

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

    val buttonLabel = stringResource(
        when (vpnState) {
            VpnState.CONNECTED -> R.string.main_connect_action_disconnect
            VpnState.CONNECTING -> R.string.main_connect_progress_connecting
            VpnState.DISCONNECTING -> R.string.main_connect_progress_disconnecting
            VpnState.BLOCKED -> R.string.main_connect_progress_recovering
            VpnState.ERROR -> R.string.main_connect_action_retry
            else -> R.string.main_connect_action_connect
        }
    )
    val stateText = stringResource(
        when (vpnState) {
            VpnState.CONNECTED -> R.string.main_state_connected
            VpnState.CONNECTING -> R.string.main_state_connecting
            VpnState.DISCONNECTING -> R.string.main_state_disconnecting
            VpnState.BLOCKED -> R.string.main_state_blocked
            VpnState.ERROR -> R.string.main_state_error
            else -> R.string.main_state_disconnected
        }
    )
    val clickLabel = stringResource(
        if (isConnected || isPulsing) R.string.main_connect_click_disconnect
        else R.string.main_connect_click_connect
    )
    val buttonDescription = stringResource(R.string.main_connect_cd)

    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    // Animated values are read only inside graphicsLayer / draw lambdas → no per-frame recomposition.
    val pulseScale: State<Float>? = if (isPulsing) rememberPulseScale() else null

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size + 60.dp)  // extra space for rings
    ) {
        // Expanding glow rings — only while connected.
        if (isConnected) {
            ConnectedRings(color = primaryColor, modifier = Modifier.size(size + 60.dp))
        }

        // Main button circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = pulseScale?.value ?: 1f
                    scaleX = s
                    scaleY = s
                }
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
                    role = Role.Button,
                    onClickLabel = clickLabel,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    }
                )
                .semantics {
                    contentDescription = buttonDescription
                    stateDescription = stateText
                }
        ) {
            // Spinning arc for in-between states
            if (isTransient) {
                SpinnerArc(color = primaryColor, modifier = Modifier.size(size - 8.dp))
            }

            // Inner border circle
            Canvas(modifier = Modifier.size(size - 8.dp)) {
                drawCircle(
                    color = primaryColor.copy(alpha = if (isTransient) 0.3f else 0.6f),
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

            // Button label (visual only — semantics come from the clickable node above)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clearAndSetSemantics { }
            ) {
                Text(
                    text = buttonLabel,
                    color = primaryColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                if (isPulsing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.main_connect_hint_cancel),
                        color = primaryColor.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberPulseScale(): State<Float> {
    val transition = rememberInfiniteTransition(label = "pulse")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
}

@Composable
private fun ConnectedRings(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "rings")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = EaseOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringProgress"
    )
    Canvas(
        modifier = modifier.graphicsLayer {
            val p = progress.value
            val s = 0.8f + 0.2f * p
            scaleX = s
            scaleY = s
            alpha = 0.6f * (1f - p)
        }
    ) {
        val maxRadius = this.size.minDimension / 2f
        drawCircle(
            color = color.copy(alpha = 0.5f),
            radius = maxRadius * 0.92f,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = color.copy(alpha = 0.3f),
            radius = maxRadius * 0.99f,
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}

@Composable
private fun SpinnerArc(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "arc")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing)),
        label = "arcAngle"
    )
    Canvas(modifier = modifier.graphicsLayer { rotationZ = angle.value }) {
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 120f,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
