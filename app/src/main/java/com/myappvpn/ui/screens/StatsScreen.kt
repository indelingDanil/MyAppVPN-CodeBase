package com.myappvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.myappvpn.data.model.TrafficStats
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import com.myappvpn.ui.components.GlassCard
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.HomeViewModel

@Composable
fun StatsScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val vpnState by viewModel.vpnState.collectAsState()
    val stats by viewModel.trafficStats.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()

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
            text = "Statistics",
            style = MaterialTheme.typography.titleLarge,
            color = AetherTextPrimary,
            fontWeight = FontWeight.Bold
        )

        // Connection status banner
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = if (vpnState == VpnState.CONNECTED) AetherConnected else AetherBorder
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            if (vpnState == VpnState.CONNECTED) AetherConnected
                            else AetherDisconnected
                        )
                )
                Column {
                    Text(
                        text = if (vpnState == VpnState.CONNECTED) "VPN Active" else "VPN Inactive",
                        color = AetherTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (selectedServer != null) {
                        Text(
                            text = "${selectedServer!!.flag} ${selectedServer!!.country}",
                            color = AetherTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (vpnState == VpnState.CONNECTED) {
                    Text(
                        text = stats.sessionDurationFormatted,
                        color = AetherCyan,
                        fontWeight = FontWeight.Medium
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
                label = "Upload Speed",
                value = stats.uploadSpeedFormatted,
                color = AetherCyan
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ArrowDownward,
                label = "Download Speed",
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
                label = "Uploaded",
                value = stats.uploadFormatted,
                color = AetherCyan.copy(alpha = 0.8f)
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Download,
                label = "Downloaded",
                value = stats.downloadFormatted,
                color = AetherConnected.copy(alpha = 0.8f)
            )
        }

        // Session duration
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = AetherPurple,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "Session Duration",
                        color = AetherTextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = stats.sessionDurationFormatted,
                        color = AetherTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                }
            }
        }

        // Note when disconnected
        if (vpnState != VpnState.CONNECTED) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AetherSurface)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Connect to a VPN to see live statistics",
                    color = AetherTextSecondary,
                    fontSize = 13.sp
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = value,
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Text(
                text = label,
                color = AetherTextSecondary,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}
