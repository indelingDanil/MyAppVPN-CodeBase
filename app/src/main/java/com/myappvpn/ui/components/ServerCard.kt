package com.myappvpn.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myappvpn.data.model.LatencyCategory
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.ui.theme.*

@Composable
fun ServerCard(
    server: ServerProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) AetherCyan else AetherBorder

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                color = if (isSelected) AetherCyanGlow else AetherSurface
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = borderColor.copy(alpha = if (isSelected) 0.7f else 0.3f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Flag emoji
            Text(
                text = server.flag,
                fontSize = 28.sp
            )

            // Server info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.country,
                    color = AetherTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = server.name.take(30),
                    color = AetherTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }

            // Validation state badge
            ValidationBadge(server = server)

            // Latency badge
            LatencyBadge(server = server)

            // Selected indicator
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = AetherCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ValidationBadge(server: ServerProfile) {
    val (icon, tint) = when (server.validationState) {
        "VERIFIED" -> Icons.Default.VerifiedUser to AetherConnected
        "HANDSHAKE_OK" -> Icons.Default.Shield to LatencyMedium
        "STALE" -> Icons.Default.Shield to LatencyBad
        else -> return // UNTESTED, REACHABLE — no badge
    }
    Icon(
        imageVector = icon,
        contentDescription = server.validationState,
        tint = tint,
        modifier = Modifier.size(16.dp)
    )
}

@Composable
fun LatencyBadge(server: ServerProfile) {
    val (color, label) = when (server.latencyCategory) {
        LatencyCategory.GOOD -> LatencyGood to "${server.latencyMs}ms"
        LatencyCategory.MEDIUM -> LatencyMedium to "${server.latencyMs}ms"
        LatencyCategory.BAD -> LatencyBad to "${server.latencyMs}ms"
        LatencyCategory.TIMEOUT -> AetherTextTertiary to "✕"
        LatencyCategory.UNKNOWN -> AetherTextTertiary to "—"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
