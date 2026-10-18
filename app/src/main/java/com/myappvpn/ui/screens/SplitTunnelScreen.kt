package com.myappvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myappvpn.R
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.AppInfo
import com.myappvpn.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val apps by viewModel.installedApps.collectAsStateWithLifecycle()
    val appsLoading by viewModel.appsLoading.collectAsStateWithLifecycle()
    val filteredApps by viewModel.filteredApps.collectAsStateWithLifecycle()
    val selectedApps by viewModel.excludedApps.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val splitEnabledState by viewModel.splitTunnelEnabled.collectAsStateWithLifecycle()
    val splitEnabled = splitEnabledState == true
    val splitMode by viewModel.splitTunnelMode.collectAsStateWithLifecycle()

    val includeMode = splitMode == AppPreferences.SPLIT_MODE_INCLUDE

    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps()
    }

    val topBarScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = AetherBackground,
        topBar = {
            TopAppBar(
                scrollBehavior = topBarScroll,
                title = {
                    Column {
                        Text(
                            stringResource(R.string.split_title),
                            color = AetherTextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                splitEnabledState == null -> ""
                                !splitEnabled -> stringResource(R.string.split_subtitle_off)
                                includeMode -> pluralStringResource(
                                    R.plurals.split_apps_via_vpn, selectedApps.size, selectedApps.size
                                )
                                else -> pluralStringResource(
                                    R.plurals.split_apps_bypass_vpn, selectedApps.size, selectedApps.size
                                )
                            },
                            color = AetherTextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.split_cd_back),
                            tint = AetherTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AetherBackground,
                    scrolledContainerColor = AetherSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(AetherBackground)
        ) {
            // Enable/disable split tunneling (also available on the Settings screen).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = splitEnabled,
                        enabled = splitEnabledState != null,
                        role = Role.Switch,
                        onValueChange = { viewModel.setSplitTunnelEnabled(it) }
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.split_enable),
                    color = AetherTextPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                if (splitEnabledState != null) {
                    Switch(
                        checked = splitEnabled,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AetherBackground,
                            checkedTrackColor = AetherCyan,
                            uncheckedThumbColor = AetherTextTertiary,
                            uncheckedTrackColor = AetherSurfaceVariant
                        )
                    )
                } else {
                    // Not loaded yet — keep the Switch's footprint so nothing jumps or flips.
                    Spacer(Modifier.size(width = 52.dp, height = 32.dp))
                }
            }

            // Mode selector
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                val modes = listOf(
                    AppPreferences.SPLIT_MODE_EXCLUDE to R.string.split_mode_exclude,
                    AppPreferences.SPLIT_MODE_INCLUDE to R.string.split_mode_include
                )
                modes.forEachIndexed { index, (mode, labelRes) ->
                    SegmentedButton(
                        selected = splitMode == mode,
                        onClick = { viewModel.setSplitTunnelMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = AetherSurfaceVariant,
                            activeContentColor = AetherCyan,
                            activeBorderColor = AetherCyan,
                            inactiveContainerColor = AetherSurface,
                            inactiveContentColor = AetherTextSecondary,
                            inactiveBorderColor = AetherBorder
                        )
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                    }
                }
            }
            Text(
                text = if (includeMode) {
                    stringResource(R.string.split_mode_include_hint)
                } else {
                    stringResource(R.string.split_mode_exclude_hint)
                },
                color = AetherTextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (includeMode && selectedApps.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AetherSurfaceVariant)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AetherConnecting)
                    Text(
                        text = stringResource(R.string.split_include_empty_warning),
                        color = AetherConnecting,
                        fontSize = 13.sp
                    )
                }
            }

            // Search — filter the (long) installed-apps list by name or package.
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.split_search_placeholder), color = AetherTextTertiary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AetherTextTertiary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.split_cd_clear_search), tint = AetherTextTertiary)
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

            // Bulk actions — operate on the currently filtered list.
            val bulkEnabled = !appsLoading && filteredApps.isNotEmpty()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BulkButton(stringResource(R.string.split_select_all), bulkEnabled, Modifier.weight(1f)) { viewModel.selectAllFiltered() }
                BulkButton(stringResource(R.string.split_clear_all), bulkEnabled, Modifier.weight(1f)) { viewModel.clearAllFiltered() }
                BulkButton(stringResource(R.string.split_invert), bulkEnabled, Modifier.weight(1f)) { viewModel.invertFiltered() }
            }
            if (searchQuery.isNotBlank()) {
                Text(
                    text = stringResource(R.string.split_bulk_applies_to, filteredApps.size),
                    color = AetherTextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            when {
                appsLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AetherCyan)
                }

                apps.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.split_no_apps),
                        color = AetherTextSecondary,
                        fontSize = 13.sp
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AetherBackground),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (filteredApps.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.split_no_search_results),
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
                        AppSelectionRow(
                            app = app,
                            selected = app.packageName in selectedApps,
                            badge = stringResource(if (includeMode) R.string.split_badge_via_vpn else R.string.split_badge_bypass),
                            onToggle = { viewModel.toggleAppExclusion(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BulkButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AetherCyan)
    ) {
        Text(text = text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AppSelectionRow(
    app: AppInfo,
    selected: Boolean,
    badge: String,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) AetherSurfaceVariant else AetherSurface
            )
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                color = AetherTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                color = AetherTextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (selected) {
            Text(
                text = badge,
                color = AetherConnecting,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Visual only — the whole row is the toggle target.
        Checkbox(
            checked = selected,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = AetherCyan,
                uncheckedColor = AetherTextTertiary,
                checkmarkColor = AetherBackground
            )
        )
    }
}
