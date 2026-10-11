package com.myappvpn.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myappvpn.R
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
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val trafficStats by viewModel.trafficStats.collectAsStateWithLifecycle()
    val autoMode by viewModel.autoMode.collectAsStateWithLifecycle()
    val pinnedServer by viewModel.pinnedServer.collectAsStateWithLifecycle()
    val activeServer by viewModel.activeServer.collectAsStateWithLifecycle()
    val switching by viewModel.switching.collectAsStateWithLifecycle()
    val hotspotInfo by viewModel.hotspotInfo.collectAsStateWithLifecycle()
    // Last non-null value, so the banner keeps its content while the exit animation runs.
    var lastHotspotInfo by remember { mutableStateOf(hotspotInfo) }
    if (hotspotInfo != null && hotspotInfo != lastHotspotInfo) lastHotspotInfo = hotspotInfo
    val snackbarHostState = remember { SnackbarHostState() }

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        } else {
            viewModel.onVpnPermissionDenied()
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
            // Scrollable: when connected (stats card + hotspot banner) the content is taller
            // than most phone screens, and without scroll the bottom part was unreachable.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                // App title
                Text(
                    text = stringResource(R.string.app_name),
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
                    label = "status",
                    modifier = Modifier.fillMaxWidth()
                ) { state ->
                    Text(
                        text = stringResource(
                            when (state) {
                                VpnState.CONNECTED -> R.string.home_status_connected
                                VpnState.CONNECTING -> R.string.home_status_connecting
                                VpnState.DISCONNECTING -> R.string.home_status_disconnecting
                                VpnState.BLOCKED -> R.string.home_status_blocked
                                VpnState.ERROR -> R.string.home_status_error
                                else -> R.string.home_status_disconnected
                            }
                        ),
                        color = when (state) {
                            VpnState.CONNECTED -> AetherConnected
                            VpnState.CONNECTING -> AetherConnecting
                            VpnState.BLOCKED -> AetherConnecting
                            VpnState.ERROR -> AetherDisconnected
                            else -> AetherTextSecondary
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Mode card: Auto (balancer) by default, or the pinned manual server.
                ModeCard(
                    autoMode = autoMode,
                    pinnedServer = pinnedServer,
                    activeServer = activeServer,
                    vpnState = vpnState,
                    switching = switching,
                    onSelectAuto = { viewModel.setAuto() },
                    onSelectManual = {
                        val pin = pinnedServer
                        if (pin != null) viewModel.pin(pin) else onNavigateToServers()
                    },
                    onOpenServers = onNavigateToServers
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
                                modifier = Modifier.fillMaxWidth()
                            )

                            HorizontalDivider(color = AetherDivider, thickness = 1.dp)

                            SessionStats(
                                stats = trafficStats,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Hotspot sharing banner (visible when connected + hotspot sharing enabled)
                AnimatedVisibility(
                    visible = vpnState == VpnState.CONNECTED && hotspotInfo != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val shown = lastHotspotInfo
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        HotspotBanner(
                            ip = shown?.ip ?: "—",
                            httpPort = shown?.httpPort ?: 10809,
                            onClick = onNavigateToHotspotSetup
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HotspotBanner(ip: String, httpPort: Int, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "📡", fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics { })
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_hotspot_title),
                    color = AetherCyan,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.home_hotspot_subtitle, ip, httpPort),
                    color = AetherTextSecondary,
                    style = MaterialTheme.typography.bodySmall
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

/**
 * Mode selector: a two-segment [⚡ Auto] [📍 Manual] control + one info row (→ Servers).
 * The segment reflects the STORED mode (DataStore); the info row shows what the tunnel uses.
 */
@Composable
private fun ModeCard(
    autoMode: Boolean?,
    pinnedServer: ServerProfile?,
    activeServer: ServerProfile?,
    vpnState: VpnState,
    switching: Boolean,
    onSelectAuto: () -> Unit,
    onSelectManual: () -> Unit,
    onOpenServers: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            if (autoMode == null) {
                // DataStore not loaded yet: reserve the space, never flash a wrong mode.
                Spacer(modifier = Modifier.fillMaxWidth().height(120.dp))
            } else {
                ModeCardContent(
                    autoMode = autoMode,
                    pinnedServer = pinnedServer,
                    activeServer = activeServer,
                    vpnState = vpnState,
                    switching = switching,
                    onSelectAuto = onSelectAuto,
                    onSelectManual = onSelectManual,
                    onOpenServers = onOpenServers
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ModeCardContent(
    autoMode: Boolean,
    pinnedServer: ServerProfile?,
    activeServer: ServerProfile?,
    vpnState: VpnState,
    switching: Boolean,
    onSelectAuto: () -> Unit,
    onSelectManual: () -> Unit,
    onOpenServers: () -> Unit
) {
    // ── Segmented control ──
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AetherBackground.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeSegment(
            emoji = "⚡",
            label = stringResource(R.string.home_mode_auto),
            selected = autoMode,
            enabled = !switching,
            onClick = onSelectAuto,
            modifier = Modifier.weight(1f)
        )
        ModeSegment(
            emoji = "📍",
            label = stringResource(R.string.home_mode_manual),
            selected = !autoMode,
            enabled = !switching,
            onClick = onSelectManual,
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // ── Info row (whole row → Servers) ──
    // The service may expose a synthetic "__balancer__" profile before a real pin is known.
    val realActive = activeServer?.takeIf { !it.id.startsWith("__") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                onClickLabel = stringResource(R.string.home_choose_server),
                role = Role.Button,
                onClick = onOpenServers
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            switching -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AetherCyan,
                    strokeWidth = 2.dp
                )
                InfoText(
                    text = stringResource(R.string.home_switching),
                    modifier = Modifier.weight(1f)
                )
            }
            autoMode -> {
                if (vpnState == VpnState.CONNECTED && realActive != null) {
                    FlagText(realActive.flag)
                    InfoText(
                        text = stringResource(R.string.home_auto_active, realActive.country),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    InfoText(
                        text = stringResource(R.string.home_auto_hint),
                        secondary = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            pinnedServer != null -> {
                FlagText(pinnedServer.flag)
                InfoText(
                    text = if (pinnedServer.latencyMs > 0)
                        stringResource(
                            R.string.home_pinned_latency,
                            pinnedServer.country,
                            pinnedServer.latencyMs
                        )
                    else pinnedServer.country,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.home_change),
                    color = AetherCyan,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            else -> {
                InfoText(
                    text = stringResource(R.string.home_no_server),
                    secondary = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = AetherTextTertiary
        )
    }

    // ── Manual server down: kill switch holds traffic; offer the way out ──
    if (!autoMode && vpnState == VpnState.BLOCKED && !switching) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "⚠",
                color = AetherConnecting,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clearAndSetSemantics { }
            )
            Text(
                text = stringResource(R.string.home_server_down),
                color = AetherConnecting,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onSelectAuto,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_switch_to_auto),
                    color = AetherCyan,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun FlagText(flag: String) {
    // Decoration only — the country name follows.
    Text(text = flag, fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics { })
}

@Composable
private fun ModeSegment(
    emoji: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    val textColor = if (selected) AetherCyan else AetherTextSecondary
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) AetherCyan.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) AetherCyan.copy(alpha = 0.7f) else Color.Transparent,
                shape = shape
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Text(
            text = emoji,
            fontSize = 15.sp,
            modifier = Modifier.clearAndSetSemantics { }
        )
        Text(
            text = label,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InfoText(text: String, modifier: Modifier = Modifier, secondary: Boolean = false) {
    Text(
        text = text,
        color = if (secondary) AetherTextSecondary else AetherTextPrimary,
        style = if (secondary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
        fontWeight = if (secondary) FontWeight.Normal else FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
