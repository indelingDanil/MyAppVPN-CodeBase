package com.myappvpn.ui.screens

import android.app.Activity
import android.os.SystemClock
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import com.myappvpn.ui.components.*
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.HomeViewModel
import com.myappvpn.ui.viewmodel.OpenFluxConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToServers: () -> Unit,
    onNavigateToHotspotSetup: () -> Unit = {},
    onNavigateToOpenFlux: () -> Unit = {}
) {
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val trafficStats by viewModel.trafficStats.collectAsStateWithLifecycle()
    val autoMode by viewModel.autoMode.collectAsStateWithLifecycle()
    val pinnedServer by viewModel.pinnedServer.collectAsStateWithLifecycle()
    val activeServer by viewModel.activeServer.collectAsStateWithLifecycle()
    val switching by viewModel.switching.collectAsStateWithLifecycle()
    val hotspotInfo by viewModel.hotspotInfo.collectAsStateWithLifecycle()
    val openfluxEnabled by viewModel.openfluxEnabled.collectAsStateWithLifecycle()
    val openfluxHost by viewModel.openfluxHost.collectAsStateWithLifecycle()
    val openfluxIsDemo by viewModel.openfluxIsDemo.collectAsStateWithLifecycle()
    val openFluxStatus by viewModel.openFluxStatus.collectAsStateWithLifecycle()
    val demoLeftSec by viewModel.openFluxDemoLeftSec.collectAsStateWithLifecycle()
    val demoLeftMb by viewModel.openFluxDemoLeftMb.collectAsStateWithLifecycle()
    val openfluxChannel by viewModel.openfluxChannel.collectAsStateWithLifecycle()
    val openFluxCarrier by viewModel.openFluxCarrier.collectAsStateWithLifecycle()
    val openFluxCaptcha by viewModel.openFluxCaptcha.collectAsStateWithLifecycle()
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    val openFluxStatusRes = OpenFluxConfig.statusMessageRes(openFluxStatus)
    val connectedViaOpenFlux = vpnState == VpnState.CONNECTED &&
        activeServer?.id == OpenFluxConfig.SYNTHETIC_SERVER_ID
    // Demo trial left: seconds as reported (at session start) minus the time since then,
    // re-evaluated every 30 s; MB as reported.
    var nowElapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(connectedViaOpenFlux, openfluxIsDemo, demoLeftSec) {
        while (connectedViaOpenFlux && openfluxIsDemo && demoLeftSec != null) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(30_000L)
        }
    }
    val demoLeftText: String? = if (connectedViaOpenFlux && openfluxIsDemo) {
        val minutes = demoLeftSec?.let { sec ->
            val elapsedSec = ((nowElapsed - MyVpnService.openFluxDemoLeftSecAt) / 1000L).coerceAtLeast(0L)
            ((sec - elapsedSec).coerceAtLeast(0L) + 59L) / 60L
        }?.toInt()
        val mb = demoLeftMb
        when {
            minutes != null && mb != null -> stringResource(R.string.openflux_demo_left, minutes, mb)
            minutes != null -> stringResource(R.string.openflux_demo_left_min, minutes)
            mb != null -> stringResource(R.string.openflux_demo_left_mb, mb)
            else -> null
        }
    } else null
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

    // OpenFlux refusal: snackbar once when a reason first appears (saveable → not repeated when
    // coming back to Home; reset once the reason is cleared so a new refusal shows again).
    val openFluxStatusMsg = openFluxStatusRes?.let { stringResource(it) }
    var snackedOpenFluxStatus by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openFluxStatus) {
        val s = openFluxStatus
        if (s == null) {
            snackedOpenFluxStatus = null
        } else if (s != snackedOpenFluxStatus && openFluxStatusMsg != null) {
            snackedOpenFluxStatus = s
            snackbarHostState.showSnackbar(openFluxStatusMsg, duration = SnackbarDuration.Long)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.padding(bottom = LocalBottomBarPadding.current)
            )
        },
        containerColor = AetherBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    openfluxEnabled = openfluxEnabled,
                    openfluxHost = openfluxHost,
                    openfluxIsDemo = openfluxIsDemo,
                    openfluxChannel = openfluxChannel,
                    openfluxCarrier = openFluxCarrier,
                    demoLeftText = demoLeftText,
                    onDisableOpenFlux = { viewModel.disableOpenFlux() },
                    onSelectAuto = { viewModel.setAuto() },
                    onSelectManual = {
                        val pin = pinnedServer
                        if (pin != null) viewModel.pin(pin) else onNavigateToServers()
                    },
                    onOpenServers = onNavigateToServers
                )

                // OpenFlux refusal (session was stopped): why + a way out.
                AnimatedVisibility(
                    visible = openFluxStatusMsg != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    // Keep the last text while the exit animation runs.
                    var lastMsg by remember { mutableStateOf(openFluxStatusMsg.orEmpty()) }
                    if (openFluxStatusMsg != null && openFluxStatusMsg != lastMsg) lastMsg = openFluxStatusMsg
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        OpenFluxStatusCard(
                            message = lastMsg,
                            showHowTo = openFluxStatus == "expired" || openFluxStatus == "ended",
                            onRegularServers = { viewModel.disableOpenFlux() },
                            onHowTo = onNavigateToOpenFlux
                        )
                    }
                }

                // OpenFlux carrier asks for a browser check (captcha): open it in-app.
                AnimatedVisibility(
                    visible = openFluxCaptcha != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    var lastUrl by remember { mutableStateOf(openFluxCaptcha.orEmpty()) }
                    if (openFluxCaptcha != null && openFluxCaptcha != lastUrl) lastUrl = openFluxCaptcha!!
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        OpenFluxCaptchaCard(onOpen = {
                            if (lastUrl.isNotEmpty()) {
                                homeContext.startActivity(com.myappvpn.ui.CaptchaActivity.intent(homeContext, lastUrl))
                            }
                        })
                    }
                }

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
                            httpPort = shown?.httpPort ?: 0,   // banner is only visible with live info
                            onClick = onNavigateToHotspotSetup
                        )
                    }
                }

                // Partner link — one quiet row, can be hidden for good with ✕.
                com.myappvpn.ui.components.PartnerCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    dismissible = true
                )

                Spacer(modifier = Modifier.height(16.dp + LocalBottomBarPadding.current))
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
    openfluxEnabled: Boolean,
    openfluxHost: String,
    openfluxIsDemo: Boolean,
    openfluxChannel: com.myappvpn.domain.OpenFluxChannel,
    openfluxCarrier: String?,
    demoLeftText: String?,
    onDisableOpenFlux: () -> Unit,
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
            } else if (openfluxEnabled) {
                OpenFluxModeContent(
                    host = openfluxHost,
                    isDemo = openfluxIsDemo,
                    channel = openfluxChannel,
                    carrier = openfluxCarrier,
                    connectedViaOpenFlux = vpnState == VpnState.CONNECTED &&
                        activeServer?.id == OpenFluxConfig.SYNTHETIC_SERVER_ID,
                    demoLeftText = demoLeftText,
                    switching = switching,
                    onDisable = onDisableOpenFlux
                )
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
    // ── Segmented control: capsule track + sliding indicator ──
    val indicatorColor = AetherPalette.accentSoft
    val indicatorPos by animateFloatAsState(
        targetValue = if (autoMode) 0f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "modeIndicator"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(AetherSurfaceVariant)
            .padding(4.dp)
            .drawBehind {
                val gap = 4.dp.toPx()
                val w = (size.width - gap) / 2f
                drawRoundRect(
                    color = indicatorColor,
                    topLeft = Offset((w + gap) * indicatorPos, 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(size.height / 2f)
                )
            },
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

/** Compact mode card while OpenFlux (Beta) is on: replaces the Auto/Manual segment. */
@Composable
private fun OpenFluxModeContent(
    host: String,
    isDemo: Boolean,
    channel: com.myappvpn.domain.OpenFluxChannel,
    carrier: String?,
    connectedViaOpenFlux: Boolean,
    demoLeftText: String?,
    switching: Boolean,
    onDisable: () -> Unit
) {
    val direct = channel == com.myappvpn.domain.OpenFluxChannel.DIRECT
    // Live carrier ("через Mail.ru"); before it is reported — the configured channel.
    val carrierName = carrier?.let { openFluxCarrierName(it) }
    val channelName = when (channel) {
        com.myappvpn.domain.OpenFluxChannel.MAILRU -> "Mail.ru"
        com.myappvpn.domain.OpenFluxChannel.CUPS -> "Cups"
        com.myappvpn.domain.OpenFluxChannel.LINK -> stringResource(R.string.openflux_channel_short_link)
        com.myappvpn.domain.OpenFluxChannel.DIRECT -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (switching) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = AetherCyan,
                strokeWidth = 2.dp
            )
        } else {
            FlagText("🧪")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.openflux_home_title),
                color = AetherCyan,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    switching -> stringResource(R.string.home_switching)
                    // Demo exit: never show its host.
                    isDemo && connectedViaOpenFlux -> stringResource(R.string.openflux_home_via_demo)
                    isDemo -> stringResource(R.string.openflux_home_demo)
                    connectedViaOpenFlux && carrierName != null ->
                        stringResource(R.string.openflux_home_via_carrier, carrierName)
                    connectedViaOpenFlux && !direct ->
                        stringResource(R.string.openflux_home_via_carrier, channelName)
                    !direct -> stringResource(R.string.openflux_home_channel, channelName)
                    connectedViaOpenFlux -> stringResource(R.string.openflux_home_via) +
                        if (host.isNotEmpty()) " · $host" else ""
                    host.isNotEmpty() -> host
                    else -> stringResource(R.string.openflux_home_no_url)
                },
                color = AetherTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (demoLeftText != null && !switching) {
                Text(
                    text = demoLeftText,
                    color = AetherConnecting,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        TextButton(
            onClick = onDisable,
            enabled = !switching,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(
                text = stringResource(R.string.openflux_turn_off),
                color = if (switching) AetherTextTertiary else AetherCyan,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/** Display name of a bridge carrier type ("OFNOTE transport/carrier"). */
@Composable
private fun openFluxCarrierName(type: String): String = when (type) {
    "mailru" -> "Mail.ru"
    "cupsonline", "cups" -> "Cups"
    "yandex", "vyandex" -> stringResource(R.string.openflux_carrier_yandex)
    "telemost" -> stringResource(R.string.openflux_carrier_telemost)
    "oneme" -> "MAX"
    "avito" -> "Avito"
    "bitrix24" -> stringResource(R.string.openflux_carrier_bitrix)
    "direct" -> stringResource(R.string.openflux_carrier_direct)
    "session" -> stringResource(R.string.openflux_carrier_node)
    else -> type
}

/** A carrier of the OpenFlux channel waits for a browser check: explain + open it. */
@Composable
private fun OpenFluxCaptchaCard(onOpen: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        borderColor = AetherConnecting.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.openflux_captcha_title),
                color = AetherConnecting,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.openflux_captcha_home_text),
                color = AetherTextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AetherCyan,
                    contentColor = AetherBackground
                )
            ) {
                Text(stringResource(R.string.openflux_captcha_open), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** OpenFlux refusal warning under the mode card: the reason + «Обычные серверы» (+ how-to). */
@Composable
private fun OpenFluxStatusCard(
    message: String,
    showHowTo: Boolean,
    onRegularServers: () -> Unit,
    onHowTo: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = "⚠", color = AetherConnecting, fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics { })
                Text(
                    text = message,
                    color = AetherTextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (showHowTo) {
                    TextButton(onClick = onHowTo, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            text = stringResource(R.string.openflux_howto_button),
                            color = AetherTextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
                TextButton(onClick = onRegularServers, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        text = stringResource(R.string.openflux_regular_servers),
                        color = AetherCyan,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
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
    val shape = RoundedCornerShape(50)
    val textColor by animateColorAsState(
        targetValue = if (selected) AetherCyan else AetherTextSecondary,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "modeSegmentText"
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
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
