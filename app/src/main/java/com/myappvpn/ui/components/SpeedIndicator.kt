package com.myappvpn.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myappvpn.data.model.TrafficStats
import com.myappvpn.ui.theme.*

@Composable
fun SpeedIndicator(
    stats: TrafficStats,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        SpeedItem(
            icon = Icons.Default.ArrowUpward,
            label = "UP",
            value = stats.uploadSpeedFormatted,
            color = AetherCyan
        )
        SpeedItem(
            icon = Icons.Default.ArrowDownward,
            label = "DOWN",
            value = stats.downloadSpeedFormatted,
            color = AetherConnected
        )
    }
}

@Composable
private fun SpeedItem(
    icon: ImageVector,
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Column {
            Text(
                text = value,
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.animateContentSize()
            )
            Text(
                text = label,
                color = AetherTextTertiary,
                fontSize = 9.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun SessionStats(
    stats: TrafficStats,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        StatItem(label = "DURATION", value = stats.sessionDurationFormatted)
        StatItem(label = "TOTAL ↓", value = stats.downloadFormatted)
        StatItem(label = "TOTAL ↑", value = stats.uploadFormatted)
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = AetherTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = label,
            color = AetherTextTertiary,
            fontSize = 9.sp,
            letterSpacing = 1.sp
        )
    }
}
