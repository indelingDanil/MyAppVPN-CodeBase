package com.myappvpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myappvpn.R
import com.myappvpn.data.model.TrafficStats
import com.myappvpn.ui.theme.*

/** Live upload / download speed. Items share the width equally so nothing overflows at large font scales. */
@Composable
fun SpeedIndicator(
    stats: TrafficStats,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SpeedItem(
            icon = Icons.Default.ArrowUpward,
            label = stringResource(R.string.main_speed_up),
            value = stats.uploadSpeedFormatted,
            color = AetherCyan,
            modifier = Modifier.weight(1f)
        )
        SpeedItem(
            icon = Icons.Default.ArrowDownward,
            label = stringResource(R.string.main_speed_down),
            value = stats.downloadSpeedFormatted,
            color = AetherConnected,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SpeedItem(
    icon: ImageVector,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Column {
            Text(
                text = value,
                color = color,
                style = MaterialTheme.typography.titleSmall.numeric(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                color = AetherTextTertiary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatItem(
            label = stringResource(R.string.main_session_duration),
            value = stats.sessionDurationFormatted,
            modifier = Modifier.weight(1f)
        )
        StatItem(
            label = stringResource(R.string.main_session_received),
            value = stats.downloadFormatted,
            modifier = Modifier.weight(1f)
        )
        StatItem(
            label = stringResource(R.string.main_session_sent),
            value = stats.uploadFormatted,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = AetherTextPrimary,
            style = MaterialTheme.typography.bodyMedium.numeric(),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            color = AetherTextTertiary,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
