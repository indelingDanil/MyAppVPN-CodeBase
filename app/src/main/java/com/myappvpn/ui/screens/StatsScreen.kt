package com.myappvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myappvpn.R
import com.myappvpn.service.VpnState
import com.myappvpn.ui.components.GlassCard
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.HomeViewModel

@Composable
fun StatsScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val stats by viewModel.trafficStats.collectAsStateWithLifecycle()
    val activeServer by viewModel.activeServer.collectAsStateWithLifecycle()
    val pinnedServer by viewModel.pinnedServer.collectAsStateWithLifecycle()
    val autoMode by viewModel.autoMode.collectAsStateWithLifecycle()

    val isConnected = vpnState == VpnState.CONNECTED
    val hasSession = vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING ||
        vpnState == VpnState.BLOCKED
    // Live session → the server actually in use (synthetic "__balancer__" ignored);
    // otherwise the pinned one in Manual mode, if any.
    val realActive = activeServer?.takeIf { !it.id.startsWith("__") }
    val shownServer = realActive?.takeIf { hasSession } ?: pinnedServer.takeIf { autoMode == false }

    val (statusRes, statusColor) = when (vpnState) {
        VpnState.CONNECTED -> R.string.stats_vpn_active to AetherConnected
        VpnState.CONNECTING -> R.string.stats_vpn_connecting to AetherConnecting
        VpnState.BLOCKED -> R.string.stats_vpn_blocked to AetherConnecting
        VpnState.DISCONNECTING -> R.string.stats_vpn_disconnecting to AetherConnecting
        VpnState.ERROR -> R.string.stats_vpn_error to AetherDisconnected
        else -> R.string.stats_vpn_inactive to AetherDisconnected
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = stringResource(R.string.stats_title),
            style = MaterialTheme.typography.titleLarge,
            color = AetherTextPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )

        // Connection status banner
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = when (vpnState) {
                VpnState.CONNECTED -> AetherConnected
                VpnState.CONNECTING, VpnState.BLOCKED -> AetherConnecting
                else -> AetherBorder
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(statusRes),
                        color = if (isConnected) AetherTextPrimary else statusColor,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (shownServer != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = shownServer.flag,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clearAndSetSemantics { }
                            )
                            Text(
                                text = shownServer.country,
                                color = AetherTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (isConnected) {
                    Text(
                        text = stats.sessionDurationFormatted,
                        color = AetherCyan,
                        style = MaterialTheme.typography.titleSmall.numeric(),
                        maxLines = 1
                    )
                }
            }
        }

        // Speed metrics row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ArrowUpward,
                label = stringResource(R.string.stats_upload_speed),
                value = stats.uploadSpeedFormatted,
                color = AetherCyan
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ArrowDownward,
                label = stringResource(R.string.stats_download_speed),
                value = stats.downloadSpeedFormatted,
                color = AetherConnected
            )
        }

        // Total traffic row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Upload,
                label = stringResource(R.string.stats_uploaded),
                value = stats.uploadFormatted,
                color = AetherCyan.copy(alpha = 0.8f)
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Download,
                label = stringResource(R.string.stats_downloaded),
                value = stats.downloadFormatted,
                color = AetherConnected.copy(alpha = 0.8f)
            )
        }

        // Session duration
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = AetherPurpleLight,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.stats_session_duration),
                        color = AetherTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stats.sessionDurationFormatted,
                        color = AetherTextPrimary,
                        style = MaterialTheme.typography.titleMedium.numeric(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }

        // Note when not connected
        if (!isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AetherSurface)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.stats_hint_disconnected),
                    color = AetherTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            // Half-width card: one line, tabular digits, ellipsis instead of wrapping.
            Text(
                text = value,
                color = color,
                style = MaterialTheme.typography.titleMedium.numeric(),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                color = AetherTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
