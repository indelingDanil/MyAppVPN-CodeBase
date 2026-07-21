package com.myappvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.myappvpn.ui.components.GlassCard
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.AppInfo
import com.myappvpn.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val apps by viewModel.installedApps.collectAsState()
    val filteredApps by viewModel.filteredApps.collectAsState()
    val excludedApps by viewModel.excludedApps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps()
    }

    Scaffold(
        containerColor = AetherBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Split Tunneling", color = AetherTextPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "${excludedApps.size} apps bypassing VPN",
                            color = AetherTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = AetherTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AetherBackground
                )
            )
        }
    ) { padding ->
        if (apps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AetherCyan)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(AetherBackground)
            ) {
                // Search — filter the (long) installed-apps list by name or package.
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Поиск приложений", color = AetherTextTertiary) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AetherTextTertiary) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = AetherTextTertiary)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = AetherSurface,
                        unfocusedContainerColor = AetherSurface,
                        focusedBorderColor = AetherCyan,
                        unfocusedBorderColor = AetherBorder,
                        focusedTextColor = AetherTextPrimary,
                        unfocusedTextColor = AetherTextPrimary,
                        cursorColor = AetherCyan
                    )
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AetherBackground),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        Text(
                            text = "Excluded apps will connect directly WITHOUT going through VPN.",
                            color = AetherTextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    if (filteredApps.isEmpty()) {
                        item {
                            Text(
                                text = "Ничего не найдено",
                                color = AetherTextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                    items(
                        items = filteredApps,
                        key = { it.packageName }
                    ) { app ->
                        AppExclusionRow(
                            app = app.copy(isExcluded = app.packageName in excludedApps),
                            onToggle = { viewModel.toggleAppExclusion(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppExclusionRow(
    app: AppInfo,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (app.isExcluded) AetherSurfaceVariant else AetherSurface
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                color = AetherTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = app.packageName,
                color = AetherTextSecondary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }

        if (app.isExcluded) {
            Text(
                text = "BYPASSING",
                color = AetherConnecting,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Checkbox(
            checked = app.isExcluded,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = AetherCyan,
                uncheckedColor = AetherTextTertiary,
                checkmarkColor = AetherBackground
            )
        )
    }
}
