package com.myappvpn.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.myappvpn.ui.components.ServerCard
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.ServersViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    viewModel: ServersViewModel = hiltViewModel(),
    onServerSelected: () -> Unit = {}
) {
    val servers by viewModel.servers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()
    val pingProgress by viewModel.pingProgress.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // After auto-select, navigate back to home
    LaunchedEffect(Unit) {
        viewModel.bestServerId.collectLatest { bestId ->
            if (bestId != null) {
                val bestServer = servers.find { it.id == bestId }
                if (bestServer != null) {
                    viewModel.selectServer(bestServer)
                    onServerSelected()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.error.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = AetherBackground,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AetherBackground)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Servers",
                            style = MaterialTheme.typography.titleLarge,
                            color = AetherTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${servers.size} servers available",
                            color = AetherTextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Auto-select button
                        SmallActionButton(
                            icon = Icons.Default.AutoAwesome,
                            label = "Auto",
                            enabled = !isPinging && !isLoading,
                            onClick = { viewModel.pingAllServers() },
                            color = AetherPurple
                        )
                        // Refresh button
                        SmallActionButton(
                            icon = Icons.Default.Refresh,
                            label = "Refresh",
                            enabled = !isLoading && !isPinging,
                            onClick = { viewModel.refreshServers() },
                            color = AetherCyan
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("Search by country...", color = AetherTextTertiary)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null,
                            tint = AetherTextTertiary)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear",
                                    tint = AetherTextTertiary)
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = AetherSurface,
                        unfocusedContainerColor = AetherSurface,
                        focusedBorderColor = AetherCyan.copy(alpha = 0.5f),
                        unfocusedBorderColor = AetherBorder.copy(alpha = 0.3f),
                        focusedTextColor = AetherTextPrimary,
                        unfocusedTextColor = AetherTextPrimary
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )

                // Ping progress bar
                AnimatedVisibility(visible = isPinging) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Testing servers...",
                                color = AetherTextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                "${(pingProgress * 100).toInt()}%",
                                color = AetherCyan,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { pingProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = AetherCyan,
                            trackColor = AetherSurface
                        )
                    }
                }
            }
        }
    ) { padding ->
        when {
            isLoading && servers.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AetherCyan)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Loading servers...", color = AetherTextSecondary)
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(AetherBackground),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = 8.dp, bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = servers,
                        key = { it.id }
                    ) { server ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInHorizontally()
                        ) {
                            ServerCard(
                                server = server,
                                isSelected = server.isSelected,
                                onClick = {
                                    viewModel.selectServer(server)
                                    onServerSelected()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = if (enabled) 0.15f else 0.05f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) color else color.copy(alpha = 0.4f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                color = if (enabled) color else color.copy(alpha = 0.4f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
