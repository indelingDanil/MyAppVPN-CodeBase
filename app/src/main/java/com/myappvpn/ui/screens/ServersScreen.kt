package com.myappvpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myappvpn.R
import com.myappvpn.service.VpnState
import com.myappvpn.ui.components.ServerCard
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.ServersViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    viewModel: ServersViewModel = hiltViewModel(),
    onServerSelected: () -> Unit = {}
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val recentlyWorking by viewModel.recentlyWorking.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isPinging by viewModel.isPinging.collectAsStateWithLifecycle()
    val pingProgress by viewModel.pingProgress.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val excludedCountries by viewModel.excludedCountries.collectAsStateWithLifecycle()
    val autoMode by viewModel.autoMode.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val activeServer by viewModel.activeServer.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // Hoisted so scroll position survives list updates (watchdog latency/speed writes) and
    // stays put while the frozen order keeps cards from reshuffling under the finger.
    val listState = rememberLazyListState()

    // Pinned check only in Manual mode (in Auto the Room pin isn't what's being used).
    val showPin = autoMode == false
    val activeId = activeServer?.id?.takeIf { vpnState == VpnState.CONNECTED }
    val excludeRussia = "Russia" in excludedCountries

    val removedMessage = stringResource(R.string.servers_removed_from_pool)
    val undoLabel = stringResource(R.string.servers_undo)

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
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            ) {
                // Header: title + actions (one 48dp row)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.servers_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = AetherTextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() }
                    )
                    // Ping (latency-only) button
                    SmallActionButton(
                        icon = Icons.Default.NetworkCheck,
                        label = stringResource(R.string.servers_ping),
                        enabled = !isPinging && !isLoading,
                        onClick = { viewModel.pingAllServers() },
                        color = AetherPurpleLight
                    )
                    // Refresh button
                    SmallActionButton(
                        icon = Icons.Default.Refresh,
                        label = stringResource(R.string.servers_refresh),
                        enabled = !isLoading && !isPinging,
                        onClick = { viewModel.refreshServers() },
                        color = AetherCyan
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.servers_search_hint),
                            color = AetherTextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search, contentDescription = null,
                            tint = AetherTextTertiary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.servers_clear_search),
                                    tint = AetherTextTertiary
                                )
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // "Search" just closes the keyboard so the filtered list is fully visible.
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
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
                                text = stringResource(R.string.servers_ping_progress),
                                color = AetherTextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${(pingProgress * 100).toInt()}%",
                                color = AetherCyan,
                                style = MaterialTheme.typography.bodySmall.numeric()
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
                        Text(
                            text = stringResource(R.string.servers_loading),
                            color = AetherTextSecondary
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(AetherBackground),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = 0.dp, bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Count + compact RU filter (scrolls away with the list to save header space).
                    item(key = "__filter__") {
                        val filterActive = searchQuery.isNotBlank() || excludedCountries.isNotEmpty()
                        ListHeaderRow(
                            countText = if (filterActive && servers.size != totalCount) {
                                stringResource(R.string.servers_count_filtered, servers.size, totalCount)
                            } else {
                                pluralStringResource(R.plurals.servers_count, servers.size, servers.size)
                            },
                            excludeRussia = excludeRussia,
                            onToggleExcludeRussia = { viewModel.setExcludeRussia(!excludeRussia) }
                        )
                    }

                    // Auto mode is always the first choice.
                    item(key = "__auto__") {
                        AutoModeCard(
                            checked = autoMode == true,
                            onClick = {
                                viewModel.selectAuto()
                                onServerSelected()
                            }
                        )
                    }

                    if (servers.isEmpty() && !isLoading) {
                        item(key = "__empty__") {
                            EmptyServersState(
                                searching = searchQuery.isNotBlank(),
                                refreshEnabled = !isLoading,
                                onRefresh = { viewModel.refreshServers() }
                            )
                        }
                    }

                    // Fallback pool: proxies confirmed actually working in the last 24h. If Auto
                    // ever picks badly, tap one of these known-good servers to connect manually.
                    if (recentlyWorking.isNotEmpty()) {
                        item(key = "__working_header__") {
                            SectionLabel(stringResource(R.string.servers_section_working), AetherCyan)
                        }
                        items(
                            items = recentlyWorking,
                            key = { "w_${it.id}" }
                        ) { server ->
                            ServerCard(
                                server = server,
                                isSelected = showPin && server.isSelected,
                                isActive = server.id == activeId,
                                onClick = {
                                    viewModel.selectServer(server)
                                    onServerSelected()
                                },
                                onRemove = {
                                    viewModel.removeFromWorking(server)
                                    scope.launch {
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        val result = snackbarHostState.showSnackbar(
                                            message = removedMessage,
                                            actionLabel = undoLabel,
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            viewModel.restoreToWorking(server)
                                        }
                                    }
                                }
                            )
                        }
                        item(key = "__all_header__") {
                            SectionLabel(stringResource(R.string.servers_section_all), AetherTextSecondary)
                        }
                    }
                    items(
                        items = servers,
                        key = { it.id }
                    ) { server ->
                        ServerCard(
                            server = server,
                            isSelected = showPin && server.isSelected,
                            isActive = server.id == activeId,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListHeaderRow(
    countText: String,
    excludeRussia: Boolean,
    onToggleExcludeRussia: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = countText,
            color = AetherTextSecondary,
            style = MaterialTheme.typography.bodySmall.numeric(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = excludeRussia,
            onClick = onToggleExcludeRussia,
            label = {
                Text(
                    text = stringResource(R.string.servers_filter_no_ru),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (excludeRussia) AetherCyan else AetherTextSecondary,
                    maxLines = 1
                )
            },
            leadingIcon = if (excludeRussia) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = AetherCyan,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            } else null,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = AetherSurface,
                selectedContainerColor = AetherCyanGlow
            )
        )
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(top = 4.dp, bottom = 2.dp)
            .semantics { heading() }
    )
}

@Composable
private fun SmallActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    color: Color
) {
    val tint = if (enabled) color else color.copy(alpha = 0.4f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = if (enabled) 0.15f else 0.05f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                color = tint,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    }
}

/** "Auto mode" row at the top of the list: checked when the stored mode is Auto. */
@Composable
private fun AutoModeCard(checked: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (checked) AetherCyanGlow else AetherSurface)
            .border(
                width = if (checked) 1.5.dp else 1.dp,
                color = (if (checked) AetherCyan else AetherBorder)
                    .copy(alpha = if (checked) 0.7f else 0.3f),
                shape = shape
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "⚡", fontSize = 28.sp, modifier = Modifier.clearAndSetSemantics { })
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.servers_auto_title),
                    color = AetherTextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.servers_auto_subtitle),
                    color = AetherTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (checked) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.servers_selected),
                    tint = AetherCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyServersState(searching: Boolean, refreshEnabled: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(
                if (searching) R.string.servers_empty_search else R.string.servers_empty
            ),
            color = AetherTextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = onRefresh,
            enabled = refreshEnabled,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.servers_refresh), color = AetherCyan)
        }
    }
}
