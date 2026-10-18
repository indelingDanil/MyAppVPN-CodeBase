package com.myappvpn.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
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
    val c = AetherPalette
    val isConnected = vpnState == VpnState.CONNECTED
    // Any in-between state: rotating progress ring around the circle.
    val isTransient = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING ||
        vpnState == VpnState.BLOCKED
    // Actively trying to (re)establish the tunnel: a tap cancels.
    val isCancellable = vpnState == VpnState.CONNECTING || vpnState == VpnState.BLOCKED

    val glassFill = c.glass.copy(alpha = if (c.isDark) 0.72f else 0.80f)
    val fillColor by animateColorAsState(
        targetValue = if (isConnected) c.connected else glassFill,
        animationSpec = tween(250),
        label = "fill"
    )
    val iconColor by animateColorAsState(
        targetValue = if (isConnected) c.onAccent else c.accent,
        animationSpec = tween(250),
        label = "icon"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isConnected) c.onAccent else c.textPrimary,
        animationSpec = tween(250),
        label = "label"
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
        if (isConnected || isCancellable) R.string.main_connect_click_disconnect
        else R.string.main_connect_click_connect
    )
    val buttonDescription = stringResource(R.string.main_connect_cd)

    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )

    // Haptic on outcome: confirm when connected, reject on error (not on first composition).
    var lastState by remember { mutableStateOf(vpnState) }
    LaunchedEffect(vpnState) {
        if (vpnState == lastState) return@LaunchedEffect
        lastState = vpnState
        when (vpnState) {
            VpnState.CONNECTED -> view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
            VpnState.ERROR -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
            else -> Unit
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size + 24.dp)  // room for the progress ring
    ) {
        if (isTransient) {
            ProgressRing(color = c.accent, modifier = Modifier.size(size + 14.dp))
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .background(fillColor)
                .border(
                    width = 1.dp,
                    brush = if (isConnected) {
                        SolidColor(Color.Transparent)
                    } else {
                        glassBorderBrush(c)
                    },
                    shape = CircleShape
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
            // Visual only — semantics come from the clickable node above.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clearAndSetSemantics { }
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = buttonLabel,
                    color = labelColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                if (isCancellable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.main_connect_hint_cancel),
                        color = c.textSecondary,
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
private fun ProgressRing(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ring")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1100, easing = LinearEasing)),
        label = "ringAngle"
    )
    Canvas(modifier = modifier.graphicsLayer { rotationZ = angle.value }) {
        val stroke = 3.dp.toPx()
        drawCircle(
            color = color.copy(alpha = 0.15f),
            radius = (this.size.minDimension - stroke) / 2f,
            style = Stroke(width = stroke)
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(
                this.size.width - stroke,
                this.size.height - stroke
            ),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}
