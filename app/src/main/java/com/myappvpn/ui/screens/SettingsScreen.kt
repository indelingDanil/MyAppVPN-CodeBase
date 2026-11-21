package com.myappvpn.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myappvpn.BuildConfig
import com.myappvpn.R
import com.myappvpn.service.VpnState
import com.myappvpn.ui.components.GlassCard
import com.myappvpn.ui.components.LocalBottomBarPadding
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.DnsViewModel
import com.myappvpn.ui.viewmodel.OpenFluxViewModel
import com.myappvpn.ui.viewmodel.SettingsViewModel
import com.myappvpn.util.AppLocale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToSplitTunnel: () -> Unit,
    onNavigateToSubscriptions: () -> Unit,
    onNavigateToDns: () -> Unit,
    dnsViewModel: DnsViewModel = hiltViewModel(),
    /** Optional: when wired in the NavGraph, the "Раздача" section links to the setup guide. */
    onNavigateToHotspotSetup: (() -> Unit)? = null,
    onNavigateToOpenFlux: () -> Unit = {},
    openFluxViewModel: OpenFluxViewModel = hiltViewModel()
) {
    val openfluxEnabled by openFluxViewModel.enabled.collectAsStateWithLifecycle()
    val dnsSummary by dnsViewModel.summary.collectAsStateWithLifecycle()
    // null = still loading from DataStore → rows show a neutral placeholder instead of a Switch.
    val autoConnect by viewModel.autoConnect.collectAsStateWithLifecycle()
    val hotspotSharing by viewModel.hotspotSharing.collectAsStateWithLifecycle()
    val splitTunnelEnabled by viewModel.splitTunnelEnabled.collectAsStateWithLifecycle()
    val killSwitchEnabled by viewModel.killSwitchEnabled.collectAsStateWithLifecycle()
    val tlsFragmentEnabled by viewModel.tlsFragmentEnabled.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    // Re-read on every (re)creation: picking a language recreates the activity.
    val languageTag = remember { viewModel.currentLanguage() }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current

    // Connection-time settings only take effect on the next connect while a session is active.
    val sessionActive = vpnState != VpnState.DISCONNECTED && vpnState != VpnState.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherBackground)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp + LocalBottomBarPadding.current),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            color = AetherTextPrimary,
            fontWeight = FontWeight.Bold
        )

        // Connection
        SettingsSection(title = stringResource(R.string.settings_section_connection)) {
            SettingsNavigateItem(
                icon = Icons.Default.CloudDownload,
                title = stringResource(R.string.settings_subscriptions_title),
                subtitle = stringResource(R.string.settings_subscriptions_subtitle),
                onClick = onNavigateToSubscriptions
            )
            SettingsDivider()
            SettingsSwitchItem(
                icon = Icons.Default.Bolt,
                title = stringResource(R.string.settings_autoconnect_title),
                subtitle = stringResource(R.string.settings_autoconnect_subtitle),
                checked = autoConnect,
                onCheckedChange = { viewModel.setAutoConnect(it) }
            )
            SettingsDivider()
            SettingsSwitchItem(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.settings_killswitch_title),
                subtitle = if (sessionActive) {
                    stringResource(R.string.settings_killswitch_subtitle) + "\n" +
                        stringResource(R.string.settings_applies_next_connect)
                } else {
                    stringResource(R.string.settings_killswitch_subtitle)
                },
                checked = killSwitchEnabled,
                onCheckedChange = { viewModel.setKillSwitchEnabled(it) }
            )
            if (killSwitchEnabled == false) {
                SettingsHint(stringResource(R.string.settings_killswitch_off_warning))
            }
            SettingsDivider()
            SettingsSwitchItem(
                icon = Icons.Default.Shield,
                title = stringResource(R.string.settings_tls_fragment_title),
                subtitle = if (sessionActive) {
                    stringResource(R.string.settings_tls_fragment_subtitle) + "\n" +
                        stringResource(R.string.settings_applies_next_connect)
                } else {
                    stringResource(R.string.settings_tls_fragment_subtitle)
                },
                checked = tlsFragmentEnabled,
                onCheckedChange = { viewModel.setTlsFragmentEnabled(it) }
            )
        }

        // Apps & network
        SettingsSection(title = stringResource(R.string.settings_section_apps_network)) {
            SettingsSwitchItem(
                icon = Icons.Default.CallSplit,
                title = stringResource(R.string.settings_split_title),
                subtitle = stringResource(R.string.settings_split_subtitle),
                checked = splitTunnelEnabled,
                onCheckedChange = { viewModel.setSplitTunnelEnabled(it) }
            )
            if (splitTunnelEnabled == true) {
                SettingsDivider()
                SettingsNavigateItem(
                    icon = Icons.Default.Apps,
                    title = stringResource(R.string.settings_split_manage_title),
                    subtitle = stringResource(R.string.settings_split_manage_subtitle),
                    onClick = onNavigateToSplitTunnel
                )
            }
            SettingsDivider()
            SettingsNavigateItem(
                icon = Icons.Default.Dns,
                title = stringResource(R.string.settings_dns_title),
                subtitle = dnsSummary?.let { dnsSummaryText(it) }.orEmpty(),
                onClick = onNavigateToDns
            )
        }

        // Hotspot sharing
        SettingsSection(title = stringResource(R.string.settings_section_hotspot)) {
            SettingsSwitchItem(
                icon = Icons.Default.Wifi,
                title = stringResource(R.string.settings_hotspot_title),
                subtitle = stringResource(R.string.settings_applies_next_connect),
                checked = hotspotSharing,
                onCheckedChange = { viewModel.setHotspotSharing(it) }
            )
            if (hotspotSharing == true) {
                SettingsHint(stringResource(R.string.settings_hotspot_hint))
                if (onNavigateToHotspotSetup != null) {
                    SettingsDivider()
                    SettingsNavigateItem(
                        icon = Icons.Default.Help,
                        title = stringResource(R.string.settings_hotspot_guide_title),
                        subtitle = stringResource(R.string.settings_hotspot_guide_subtitle),
                        onClick = onNavigateToHotspotSetup
                    )
                }
            }
        }

        // Experiments
        SettingsSection(title = stringResource(R.string.openflux_settings_section)) {
            val base = stringResource(R.string.openflux_settings_subtitle)
            val state = when (openfluxEnabled) {
                true -> stringResource(R.string.openflux_state_on)
                false -> stringResource(R.string.openflux_state_off)
                null -> null
            }
            SettingsNavigateItem(
                icon = Icons.Default.Science,
                title = stringResource(R.string.openflux_settings_title),
                subtitle = if (state != null) {
                    stringResource(R.string.openflux_settings_subtitle_state, base, state)
                } else {
                    base
                },
                onClick = onNavigateToOpenFlux
            )
        }

        // Appearance
        SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
            SettingsNavigateItem(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.settings_theme_title),
                subtitle = themeMode?.let { stringResource(themeLabelRes(it)) } ?: "",
                onClick = { showThemeDialog = true }
            )
            SettingsDivider()
            SettingsNavigateItem(
                icon = Icons.Default.Language,
                title = stringResource(R.string.settings_language_title),
                subtitle = AppLocale.OPTIONS.firstOrNull { it.first == languageTag }?.second
                    ?: stringResource(R.string.settings_language_system),
                onClick = { showLanguageDialog = true }
            )
        }

        // About
        SettingsSection(title = stringResource(R.string.settings_section_about)) {
            SettingsInfoItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.settings_about_version),
                value = BuildConfig.VERSION_NAME
            )
            SettingsDivider()
            SettingsInfoItem(
                icon = Icons.Default.Source,
                title = stringResource(R.string.settings_about_sources),
                value = stringResource(R.string.settings_about_sources_value)
            )
            SettingsDivider()
            SettingsInfoItem(
                icon = Icons.Default.Security,
                title = stringResource(R.string.settings_about_protocol),
                value = stringResource(R.string.settings_about_protocol_value)
            )
        }

        // Links / donations / disclaimer live in the dedicated "Проект" bottom-nav tab.

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showThemeDialog) {
        ThemeDialog(
            current = themeMode ?: ThemeMode.SYSTEM.name,
            onSelect = {
                viewModel.setThemeMode(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showLanguageDialog) {
        LanguageDialog(
            current = languageTag,
            onSelect = { tag ->
                showLanguageDialog = false
                if (tag != languageTag) activity?.let { viewModel.setLanguage(it, tag) }
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

@Composable
private fun LanguageDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val options = listOf("" to stringResource(R.string.settings_language_system)) + AppLocale.OPTIONS
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AetherSurface,
        title = { Text(stringResource(R.string.settings_language_dialog_title), color = AetherTextPrimary) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEach { (tag, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = current == tag,
                                role = Role.RadioButton,
                                onClick = { onSelect(tag) }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(
                            selected = current == tag,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AetherCyan,
                                unselectedColor = AetherTextTertiary
                            )
                        )
                        Text(label, color = AetherTextPrimary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_language_cancel), color = AetherCyan)
            }
        }
    )
}

private fun themeLabelRes(mode: String): Int = when (mode) {
    ThemeMode.LIGHT.name -> R.string.settings_theme_light
    ThemeMode.DARK.name -> R.string.settings_theme_dark
    else -> R.string.settings_theme_system
}

@Composable
private fun ThemeDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AetherSurface,
        title = { Text(stringResource(R.string.settings_theme_title), color = AetherTextPrimary) },
        text = {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = current == mode.name,
                                role = Role.RadioButton,
                                onClick = { onSelect(mode.name) }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(
                            selected = current == mode.name,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AetherCyan,
                                unselectedColor = AetherTextTertiary
                            )
                        )
                        Text(stringResource(themeLabelRes(mode.name)), color = AetherTextPrimary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dns_cancel), color = AetherCyan)
            }
        }
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = AetherDivider, thickness = 1.dp)
}

@Composable
private fun SettingsHint(text: String) {
    Text(
        text = text,
        color = AetherTextSecondary,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(start = 48.dp, end = 16.dp, bottom = 12.dp)
    )
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
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
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }
}

/**
 * Whole row toggles (not only the small Switch); the Switch itself is display-only.
 * [checked] == null means "not loaded yet": the row is inert and shows an empty slot of the
 * Switch's size, so nothing visibly flips when the stored value arrives.
 */
@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean?,
    onCheckedChange: (Boolean) -> Unit
) {
    val rowModifier = if (checked != null) {
        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(rowModifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = AetherCyan, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AetherTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = AetherTextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (checked != null) {
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AetherBackground,
                    checkedTrackColor = AetherCyan,
                    uncheckedThumbColor = AetherTextTertiary,
                    uncheckedTrackColor = AetherSurfaceVariant
                )
            )
        } else {
            // Same footprint as the M3 Switch (52x32dp) to avoid a layout jump on load.
            Spacer(Modifier.size(width = 52.dp, height = 32.dp))
        }
    }
}

@Composable
fun SettingsNavigateItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = AetherPurple, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AetherTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                color = AetherTextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,   // else inherits bodyLarge's 24sp → gappy 2-line subtitles
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AetherTextTertiary)
    }
}

@Composable
fun SettingsInfoItem(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = AetherTextSecondary, modifier = Modifier.size(20.dp))
        Text(title, color = AetherTextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = AetherTextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
