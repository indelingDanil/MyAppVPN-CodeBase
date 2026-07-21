package com.myappvpn.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.service.VpnState
import com.myappvpn.ui.components.*
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToServers: () -> Unit,
    onNavigateToHotspotSetup: () -> Unit = {}
) {
    val vpnState by viewModel.vpnState.collectAsState()
    val trafficStats by viewModel.trafficStats.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val autoMode by viewModel.autoMode.collectAsState()
    val hotspotProxyInfo by viewModel.hotspotProxyInfo.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        }
    }

    // Observe VPN permission needed
    LaunchedEffect(Unit) {
        viewModel.vpnPermissionNeeded.collectLatest { intent ->
            vpnPermissionLauncher.launch(intent)
        }
    }

    // Observe errors
    LaunchedEffect(Unit) {
        viewModel.error.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = AetherBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            GradientBackgroundStart,
                            GradientBackgroundEnd
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                // App title
                Text(
                    text = "MyAppVPN",
                    style = MaterialTheme.typography.titleLarge,
                    color = AetherTextPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Status text
                AnimatedContent(
                    targetState = vpnState,
                    transitionSpec = {
                        fadeIn() + slideInVertically() togetherWith
                        fadeOut() + slideOutVertically()
                    },
                    label = "status"
                ) { state ->
                    Text(
                        text = when (state) {
                            VpnState.CONNECTED -> "Защищено"
                            VpnState.CONNECTING -> "Поиск рабочего сервера…"
                            VpnState.DISCONNECTING -> "Отключение…"
                            VpnState.BLOCKED -> "Соединение потеряно — трафик защищён, переподключение…"
                            VpnState.ERROR -> "Ошибка подключения"
                            else -> "Не защищено"
                        },
                        color = when (state) {
                            VpnState.CONNECTED -> AetherConnected
                            VpnState.CONNECTING -> AetherConnecting
                            VpnState.BLOCKED -> AetherConnecting
                            VpnState.ERROR -> AetherDisconnected
                            else -> AetherTextSecondary
                        },
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Mode card: Auto (balancer) by default, or the pinned manual server.
                ModeCard(
                    autoMode = autoMode,
                    server = selectedServer,
                    onClick = onNavigateToServers,
                    onSwitchToAuto = { viewModel.switchToAuto() }
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Single connect button — connects via balancer (Auto) or the pinned server.
                ConnectButton(
                    vpnState = vpnState,
                    onClick = { viewModel.onConnectClick() },
                    size = 180.dp
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Speed indicators (visible when connected)
                AnimatedVisibility(
                    visible = vpnState == VpnState.CONNECTED,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            SpeedIndicator(
                                stats = trafficStats,
                                modifier = Modifier.fillMaxWidth().wrapContentWidth()
                            )

                            Divider(color = AetherDivider, thickness = 1.dp)

                            SessionStats(
                                stats = trafficStats,
                                modifier = Modifier.fillMaxWidth().wrapContentWidth()
                            )
                        }
                    }
                }

                // Hotspot sharing banner (visible when connected + hotspot sharing enabled)
                AnimatedVisibility(
                    visible = vpnState == VpnState.CONNECTED && hotspotProxyInfo != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    hotspotProxyInfo?.let { proxyInfo ->
                        val ip = proxyInfo.substringBeforeLast(":")
                        Spacer(modifier = Modifier.height(12.dp))
                        HotspotBanner(ip = ip, onClick = onNavigateToHotspotSetup)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HotspotBanner(ip: String, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "📡", fontSize = 22.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hotspot Sharing",
                    color = AetherCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "SOCKS5  $ip : 10808  · Нажмите для инструкций",
                    color = AetherTextSecondary,
                    fontSize = 11.sp
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = AetherTextTertiary
            )
        }
    }
}

@Composable
private fun ModeCard(
    autoMode: Boolean,
    server: ServerProfile?,
    onClick: () -> Unit,
    onSwitchToAuto: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (autoMode) {
                Text(text = "⚡", fontSize = 30.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Авто-режим",
                        color = AetherTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Выбирает лучший сервер автоматически",
                        color = AetherTextSecondary,
                        fontSize = 12.sp
                    )
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = "Выбрать вручную",
                    tint = AetherTextTertiary
                )
            } else {
                Text(text = server?.flag ?: "🌐", fontSize = 30.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server?.country ?: "Выберите сервер",
                        color = AetherTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (server != null && server.latencyMs > 0)
                            "Ручной выбор · ${server.latencyMs}ms"
                        else "Ручной выбор",
                        color = AetherTextSecondary,
                        fontSize = 12.sp
                    )
                }
                // Quick return to Auto
                Text(
                    text = "⚡ Авто",
                    color = AetherCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onSwitchToAuto)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}
