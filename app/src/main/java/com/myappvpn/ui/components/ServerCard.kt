package com.myappvpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myappvpn.R
import com.myappvpn.data.model.LatencyCategory
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.ui.theme.*

@Composable
fun ServerCard(
    server: ServerProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    /** The live session is using this server right now (shows a green "active" marker). */
    isActive: Boolean = false,
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
            // Less end padding when the 48dp remove button is present (it has its own inset).
            .padding(
                start = 16.dp,
                end = if (onRemove != null) 4.dp else 16.dp,
                top = if (onRemove != null) 4.dp else 14.dp,
                bottom = if (onRemove != null) 4.dp else 14.dp
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Flag emoji — decoration only (the country name is right next to it).
            Text(
                text = server.flag,
                fontSize = 28.sp,
                modifier = Modifier.clearAndSetSemantics { }
            )

            // Server info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = server.country,
                        color = AetherTextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isActive) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(AetherConnected)
                            )
                            Text(
                                text = stringResource(R.string.servers_active),
                                color = AetherConnected,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
                Text(
                    text = server.name,
                    color = AetherTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                    contentDescription = stringResource(R.string.servers_selected),
                    tint = AetherCyan,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Remove-from-pool action (only in the 24h working-pool section). Default 48dp target.
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.servers_remove_from_pool),
                        tint = AetherTextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ValidationBadge(server: ServerProfile) {
    val (icon, tint, labelRes) = when (server.validationState) {
        "VERIFIED" -> Triple(Icons.Default.VerifiedUser, AetherConnected, R.string.servers_validation_verified)
        "HANDSHAKE_OK" -> Triple(Icons.Default.Shield, LatencyMedium, R.string.servers_validation_handshake)
        "STALE" -> Triple(Icons.Default.Shield, LatencyBad, R.string.servers_validation_stale)
        else -> return // UNTESTED, REACHABLE — no badge
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(labelRes),
        tint = tint,
        modifier = Modifier.size(16.dp)
    )
}

@Composable
fun LatencyBadge(server: ServerProfile) {
    val ms = server.latencyMs
    val (color, label, description) = when (server.latencyCategory) {
        LatencyCategory.GOOD, LatencyCategory.MEDIUM, LatencyCategory.BAD -> Triple(
            when (server.latencyCategory) {
                LatencyCategory.GOOD -> LatencyGood
                LatencyCategory.MEDIUM -> LatencyMedium
                else -> LatencyBad
            },
            stringResource(R.string.servers_latency_ms, ms),
            stringResource(R.string.servers_latency_cd, ms)
        )
        LatencyCategory.TIMEOUT -> Triple(
            AetherTextTertiary, "✕", stringResource(R.string.servers_latency_timeout)
        )
        LatencyCategory.UNKNOWN -> Triple(
            AetherTextTertiary, "—", stringResource(R.string.servers_latency_unknown)
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall.numeric(),
            maxLines = 1
        )
    }
}
