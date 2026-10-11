package com.myappvpn.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myappvpn.R
import com.myappvpn.service.VpnState
import com.myappvpn.ui.components.GlassCard
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.HotspotViewModel

/** Fallback ports for the instructions when the live session info isn't available. */
private const val DEFAULT_HTTP_PORT = 10809
private const val DEFAULT_SOCKS_PORT = 10808

/** Placeholder for values that are unknown until a sharing session is running. */
private const val UNKNOWN = "—"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotSetupScreen(
    onBack: () -> Unit,
    viewModel: HotspotViewModel = hiltViewModel()
) {
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val info by viewModel.hotspotInfo.collectAsStateWithLifecycle()
    val sharingEnabled by viewModel.sharingEnabled.collectAsStateWithLifecycle()
    val storedCreds by viewModel.credentials.collectAsStateWithLifecycle()

    val connected = vpnState == VpnState.CONNECTED
    val live = info?.takeIf { connected }
    val ip = live?.ip ?: UNKNOWN
    val ipKnown = live != null
    val httpPort = live?.httpPort ?: DEFAULT_HTTP_PORT
    val socksPort = live?.socksPort ?: DEFAULT_SOCKS_PORT
    // The running session uses ITS credentials; the stored ones apply on the next connect.
    val user = live?.user ?: storedCreds?.first ?: UNKNOWN
    val pass = live?.pass ?: storedCreds?.second ?: UNKNOWN
    val passChangedPending = live != null && storedCreds != null && storedCreds?.second != live.pass

    val hostHint = if (ipKnown) ip else stringResource(R.string.hotspot_host_hint_unknown)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.hotspot_title),
                        color = AetherTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.hotspot_cd_back),
                            tint = AetherTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AetherBackground)
            )
        },
        containerColor = AetherBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Status
            StatusCard(
                connected = connected,
                sharingEnabled = sharingEnabled,
                hasInfo = live != null,
                onBack = onBack
            )

            // Values
            InfoCard(title = stringResource(R.string.hotspot_params_title)) {
                CopyRow(
                    label = stringResource(R.string.hotspot_http_proxy),
                    value = if (ipKnown) "$ip:$httpPort" else "$UNKNOWN:$httpPort",
                    copyText = if (ipKnown) "$ip:$httpPort" else null
                )
                CopyRow(
                    label = stringResource(R.string.hotspot_socks5),
                    value = if (ipKnown) "$ip:$socksPort" else "$UNKNOWN:$socksPort",
                    copyText = if (ipKnown) "$ip:$socksPort" else null
                )
                HorizontalDivider(color = AetherDivider)
                Text(
                    text = stringResource(R.string.hotspot_auth_note),
                    color = AetherTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                CopyRow(
                    label = stringResource(R.string.hotspot_login),
                    value = user,
                    copyText = user.takeIf { it != UNKNOWN }
                )
                PasswordRow(pass = pass)
                if (passChangedPending) {
                    Text(
                        text = stringResource(R.string.hotspot_new_password_pending),
                        color = AetherConnecting,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
                TextButton(
                    onClick = { viewModel.regeneratePassword() },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = AetherCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hotspot_change_password), color = AetherCyan, fontSize = 13.sp)
                }
                if (!passChangedPending) {
                    Text(
                        text = stringResource(R.string.hotspot_password_change_note),
                        color = AetherTextTertiary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            // Pre-requisites
            SetupSection(title = stringResource(R.string.hotspot_before_title)) {
                SetupStep(1, stringResource(R.string.hotspot_before_step1))
                SetupStep(2, stringResource(R.string.hotspot_before_step2))
                SetupStep(3, stringResource(R.string.hotspot_before_step3))
                SetupStep(4, stringResource(R.string.hotspot_before_step4))
            }

            // Android
            SetupSection(title = stringResource(R.string.hotspot_android_title)) {
                SetupStep(1, stringResource(R.string.hotspot_android_step1))
                SetupStep(2, stringResource(R.string.hotspot_android_step2))
                SetupStep(3, stringResource(R.string.hotspot_android_step3))
                SetupStep(4, stringResource(R.string.hotspot_android_step4))
                SetupStep(5, stringResource(R.string.hotspot_android_step5, hostHint, httpPort))
                SetupStep(6, stringResource(R.string.hotspot_step_save))
            }

            // iOS
            SetupSection(title = stringResource(R.string.hotspot_ios_title)) {
                SetupStep(1, stringResource(R.string.hotspot_ios_step1))
                SetupStep(2, stringResource(R.string.hotspot_ios_step2))
                SetupStep(3, stringResource(R.string.hotspot_ios_step3))
                SetupStep(4, stringResource(R.string.hotspot_ios_step4, hostHint, httpPort))
                SetupStep(5, stringResource(R.string.hotspot_ios_step5))
                SetupStep(6, stringResource(R.string.hotspot_step_save))
            }

            // Windows / macOS
            SetupSection(title = stringResource(R.string.hotspot_desktop_title)) {
                SetupStep(null, stringResource(R.string.hotspot_desktop_windows, hostHint, httpPort))
                SetupStep(null, stringResource(R.string.hotspot_desktop_macos, hostHint, httpPort))
            }

            // Telegram
            SetupSection(title = stringResource(R.string.hotspot_telegram_title)) {
                SetupStep(1, stringResource(R.string.hotspot_telegram_step1))
                SetupStep(2, stringResource(R.string.hotspot_telegram_step2))
                SetupStep(3, stringResource(R.string.hotspot_telegram_step3, hostHint, socksPort))
                SetupStep(4, stringResource(R.string.hotspot_telegram_step4, user))
                SetupStep(5, stringResource(R.string.hotspot_telegram_step5))
            }

            // Notes
            InfoCard(title = stringResource(R.string.hotspot_notes_title)) {
                NoteText(stringResource(R.string.hotspot_note_access))
                NoteText(stringResource(R.string.hotspot_note_not_all_traffic))
                NoteText(stringResource(R.string.hotspot_note_ip_changed))
            }

            // Verification
            InfoCard(title = stringResource(R.string.hotspot_verify_title)) {
                NoteText(stringResource(R.string.hotspot_verify_text))
            }

            // How to disable
            InfoCard(title = stringResource(R.string.hotspot_disable_title)) {
                NoteText(stringResource(R.string.hotspot_disable_text))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCard(
    connected: Boolean,
    sharingEnabled: Boolean?,
    hasInfo: Boolean,
    onBack: () -> Unit
) {
    val (textRes, color) = when {
        !connected -> R.string.hotspot_status_connect_first to AetherDisconnected
        // A live session keeps sharing until reconnect even if the setting was just turned off.
        hasInfo && sharingEnabled == false -> R.string.hotspot_status_on_but_disabled to AetherConnecting
        hasInfo -> R.string.hotspot_status_working to AetherConnected
        sharingEnabled == false -> R.string.hotspot_status_enable_in_settings to AetherConnecting
        else -> R.string.hotspot_status_no_hotspot to AetherConnecting
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(textRes),
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 19.sp
            )
            if (connected && !hasInfo && sharingEnabled == false) {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.hotspot_back), color = AetherCyan)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                color = AetherCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(color = AetherDivider)
            content()
        }
    }
}

@Composable
private fun NoteText(text: String) {
    Text(text = text, color = AetherTextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun rememberCopy(): (label: String, text: String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(clipboard, context) {
        { label: String, text: String ->
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(
                context,
                context.getString(R.string.hotspot_copied, label),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, copyText: String?) {
    val copy = rememberCopy()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = AetherTextSecondary,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            color = AetherTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { if (copyText != null) copy(label, copyText) },
            enabled = copyText != null
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.hotspot_cd_copy, label),
                tint = if (copyText != null) AetherCyan else AetherTextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun PasswordRow(pass: String) {
    val copy = rememberCopy()
    var visible by rememberSaveable { mutableStateOf(false) }
    val known = pass != UNKNOWN
    val passwordLabel = stringResource(R.string.hotspot_password)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(passwordLabel, color = AetherTextSecondary, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Text(
            text = if (visible || !known) pass else "•".repeat(pass.length),
            color = AetherTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { visible = !visible }, enabled = known) {
            Icon(
                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = stringResource(
                    if (visible) R.string.hotspot_cd_hide_password else R.string.hotspot_cd_show_password
                ),
                tint = if (known) AetherCyan else AetherTextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = { if (known) copy(passwordLabel, pass) }, enabled = known) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.hotspot_cd_copy, passwordLabel),
                tint = if (known) AetherCyan else AetherTextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SetupSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text = title.uppercase(),
            color = AetherCyan,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SetupStep(number: Int?, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (number != null) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(AetherCyan.copy(alpha = 0.15f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number.toString(),
                    color = AetherCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Text(text = "•", color = AetherCyan, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Text(
            text = text,
            color = AetherTextPrimary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
