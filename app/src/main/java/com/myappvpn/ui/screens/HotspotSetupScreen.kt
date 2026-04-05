package com.myappvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myappvpn.service.MyVpnService
import com.myappvpn.ui.components.GlassCard
import com.myappvpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotSetupScreen(onBack: () -> Unit) {
    val proxyInfo by MyVpnService.hotspotProxyInfo.collectAsState()
    val ip = proxyInfo?.substringBeforeLast(":") ?: "—"
    val port = "10808"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Hotspot Setup",
                        color = AetherTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AetherCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AetherSurface)
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

            // Proxy params card
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Настройки прокси / Proxy settings",
                        color = AetherCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Divider(color = AetherDivider)
                    ProxyParamRow("Сервер / Server", ip, highlight = true)
                    ProxyParamRow("Порт / Port", port, highlight = true)
                    ProxyParamRow("Тип / Type", "SOCKS5", highlight = true)
                    if (ip == "—") {
                        Text(
                            text = "⚠ Хотспот не обнаружен. Включите хотспот на телефоне.\n⚠ Hotspot not detected. Enable hotspot on your phone.",
                            color = AetherDisconnected,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Pre-requisites
            SetupSection(title = "Перед началом / Before you start") {
                SetupStep(
                    number = null,
                    text = "1. VPN подключён (зелёный экран) / VPN is connected (green status)"
                )
                SetupStep(
                    number = null,
                    text = "2. Хотспот включён на этом телефоне / Hotspot is enabled on this phone"
                )
                SetupStep(
                    number = null,
                    text = "3. Другое устройство подключено к вашему хотспоту / Other device is connected to your hotspot"
                )
            }

            // Android instructions
            SetupSection(title = "Android") {
                SetupStep(1, "Откройте Настройки → Wi-Fi\nOpen Settings → Wi-Fi")
                SetupStep(2, "Найдите сеть вашего хотспота в списке\nFind your hotspot network in the list")
                SetupStep(3, "Нажмите и удерживайте сеть → «Изменить сеть», или нажмите значок ⚙ / ✏\nLong-press the network → \"Modify network\", or tap the ⚙ / ✏ icon")
                SetupStep(4, "Нажмите «Дополнительные параметры» / «Advanced options»")
                SetupStep(5, "В строке «Прокси» выберите «Вручную» / Under \"Proxy\" select \"Manual\"")
                SetupStep(6, "Имя хоста / Hostname: введите IP из поля выше\nEnter the IP from the field above")
                SetupStep(7, "Порт прокси / Proxy port: $port")
                SetupStep(8, "Нажмите «Сохранить» / Tap \"Save\"")
            }

            // iOS instructions
            SetupSection(title = "iOS (iPhone / iPad)") {
                SetupStep(1, "Откройте Настройки → Wi-Fi\nOpen Settings → Wi-Fi")
                SetupStep(2, "Найдите сеть вашего хотспота и нажмите кнопку (ⓘ) справа\nFind your hotspot network and tap the (ⓘ) button on the right")
                SetupStep(3, "Прокрутите вниз до раздела «Настройка прокси» / «HTTP PROXY»\nScroll down to the \"HTTP PROXY\" / \"Configure Proxy\" section")
                SetupStep(4, "Нажмите «Настройка прокси» → выберите «Вручную» / «Manual»\nTap \"Configure Proxy\" → select \"Manual\"")
                SetupStep(5, "Сервер / Server: введите IP из поля выше\nEnter the IP from the field above")
                SetupStep(6, "Порт / Port: $port")
                SetupStep(7, "Аутентификация / Authentication: оставьте выключенной / leave off")
                SetupStep(8, "Нажмите «Сохранить» в правом верхнем углу / Tap \"Save\" in the top right")
            }

            // Verification tip
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Проверка / Verification",
                        color = AetherCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Divider(color = AetherDivider)
                    Text(
                        text = "После настройки откройте браузер на другом устройстве и проверьте IP-адрес на сайте 2ip.ru или whoer.net — он должен совпадать с IP вашего VPN-сервера.\n\nAfter setup, open a browser on the other device and check your IP at 2ip.ru or whoer.net — it should match your VPN server's IP.",
                        color = AetherTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            // Reset tip
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Как отключить / How to disable",
                        color = AetherTextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Divider(color = AetherDivider)
                    Text(
                        text = "Повторите шаги выше и в поле «Прокси» выберите «Нет» / «None».\n\nRepeat the steps above and set Proxy back to \"None\" / \"Off\".",
                        color = AetherTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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

@Composable
private fun ProxyParamRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AetherTextSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = if (highlight) AetherTextPrimary else AetherTextSecondary,
            fontSize = if (highlight) 14.sp else 12.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
