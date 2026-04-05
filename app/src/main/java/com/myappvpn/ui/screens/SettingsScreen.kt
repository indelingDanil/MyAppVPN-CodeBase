package com.myappvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.myappvpn.ui.components.GlassCard
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToSplitTunnel: () -> Unit
) {
    val autoConnect by viewModel.autoConnect.collectAsState()
    val hotspotSharing by viewModel.hotspotSharing.collectAsState()
    val igareckFull by viewModel.igareckFull.collectAsState()
    val splitTunnelEnabled by viewModel.splitTunnelEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            color = AetherTextPrimary,
            fontWeight = FontWeight.Bold
        )

        // Subscription section
        SettingsSection(title = "Subscription") {
            SettingsInfoItem(
                icon = Icons.Default.CloudDownload,
                title = "Config Sources",
                value = "zieng2/wl + igareck"
            )
            Divider(color = AetherDivider, thickness = 1.dp)
            SettingsSwitchItem(
                icon = Icons.Default.Warning,
                title = "igareck: полная подписка",
                subtitle = "BLACK_VLESS_RUS.txt вместо mobile",
                checked = igareckFull,
                onCheckedChange = { viewModel.setIgareckFull(it) }
            )
            if (igareckFull) {
                Text(
                    text = "⚠ Внимание! Полная подписка содержит 1000-2000+ конфигураций. " +
                           "Телефон может тяжело обработать такое количество! " +
                           "Рекомендовано только для ПК.\n" +
                           "⚠ Warning! Full subscription has 1000-2000+ configs. " +
                           "Phones struggle with this many! Recommended for PC only.",
                    color = AetherDisconnected,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(start = 48.dp, end = 16.dp, bottom = 12.dp)
                )
            }
        }

        // Connection section
        SettingsSection(title = "Connection") {
            SettingsSwitchItem(
                icon = Icons.Default.Bolt,
                title = "Auto-Connect on Boot",
                subtitle = "Reconnect VPN automatically when device starts",
                checked = autoConnect,
                onCheckedChange = { viewModel.setAutoConnect(it) }
            )
            Divider(color = AetherDivider, thickness = 1.dp)
            SettingsSwitchItem(
                icon = Icons.Default.Wifi,
                title = "Hotspot Sharing",
                subtitle = "Раздавать VPN через хотспот / Share VPN via hotspot — enable, then set proxy on the other device",
                checked = hotspotSharing,
                onCheckedChange = { viewModel.setHotspotSharing(it) }
            )
            if (hotspotSharing) {
                Text(
                    text = "⚠ Прокси открыт на всех интерфейсах. Используйте только с мобильным интернетом. / Proxy open on all interfaces. Use only with mobile data.",
                    color = AetherTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(start = 48.dp, end = 16.dp, bottom = 12.dp)
                )
            }
        }

        // Split Tunneling section
        SettingsSection(title = "Split Tunneling") {
            SettingsSwitchItem(
                icon = Icons.Default.CallSplit,
                title = "Enable Split Tunneling",
                subtitle = "Choose which apps bypass the VPN",
                checked = splitTunnelEnabled,
                onCheckedChange = { viewModel.setSplitTunnelEnabled(it) }
            )
            if (splitTunnelEnabled) {
                Divider(color = AetherDivider, thickness = 1.dp)
                SettingsNavigateItem(
                    icon = Icons.Default.Apps,
                    title = "Manage Excluded Apps",
                    subtitle = "Select apps that will not use VPN",
                    onClick = onNavigateToSplitTunnel
                )
            }
        }

        // DNS section
        SettingsSection(title = "DNS") {
            SettingsInfoItem(
                icon = Icons.Default.Dns,
                title = "Primary DNS",
                value = "1.1.1.1 (DoH)"
            )
            Divider(color = AetherDivider, thickness = 1.dp)
            SettingsInfoItem(
                icon = Icons.Default.Dns,
                title = "Secondary DNS",
                value = "8.8.8.8 (DoH)"
            )
        }

        // Advanced section
        SettingsSection(title = "Advanced") {
            SettingsInfoItem(
                icon = Icons.Default.NetworkCheck,
                title = "SOCKS5 Port",
                value = ":10808"
            )
        }

        // About section
        SettingsSection(title = "About") {
            SettingsInfoItem(
                icon = Icons.Default.Info,
                title = "Version",
                value = com.myappvpn.BuildConfig.VERSION_NAME
            )
            Divider(color = AetherDivider, thickness = 1.dp)
            SettingsInfoItem(
                icon = Icons.Default.Source,
                title = "Config Sources",
                value = "zieng2/wl + igareck"
            )
            Divider(color = AetherDivider, thickness = 1.dp)
            SettingsInfoItem(
                icon = Icons.Default.Security,
                title = "Protocol",
                value = "VLESS + XRay Core"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
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

@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = AetherCyan, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AetherTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = AetherTextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AetherBackground,
                checkedTrackColor = AetherCyan,
                uncheckedThumbColor = AetherTextTertiary,
                uncheckedTrackColor = AetherSurfaceVariant
            )
        )
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
            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = AetherPurple, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AetherTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = AetherTextSecondary, fontSize = 11.sp)
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
        Text(value, color = AetherTextSecondary, fontSize = 12.sp)
    }
}

