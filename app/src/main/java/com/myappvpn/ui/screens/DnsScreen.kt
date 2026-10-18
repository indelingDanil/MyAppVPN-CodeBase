package com.myappvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myappvpn.R
import com.myappvpn.data.model.DnsPreset
import com.myappvpn.data.model.DnsPresets
import com.myappvpn.data.model.DnsServer
import com.myappvpn.data.model.DnsType
import com.myappvpn.data.repository.DnsRepository
import com.myappvpn.ui.components.GlassCard
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.DnsViewModel
import com.myappvpn.util.DnsValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen(
    viewModel: DnsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val serversOrNull by viewModel.servers.collectAsStateWithLifecycle()
    val servers = serversOrNull.orEmpty()
    val ipv6Enabled by viewModel.ipv6Enabled.collectAsStateWithLifecycle()
    val addError by viewModel.addError.collectAsStateWithLifecycle()
    val addedDisabled by viewModel.addedDisabled.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    val typingWarning = remember(input) { viewModel.warningFor(input) }
    val canAdd = serversOrNull != null && input.isNotBlank() && servers.size < DnsRepository.MAX_TOTAL
    val submit: () -> Unit = {
        val raw = input.trim()
        if (raw.isNotEmpty()) viewModel.add(raw) { input = "" } // clear ONLY on success
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
                        Text(stringResource(R.string.dns_title), color = AetherTextPrimary, fontWeight = FontWeight.Bold)
                        if (serversOrNull != null) {
                            Text(
                                dnsSummaryText(DnsRepository.summarize(servers)),
                                color = AetherTextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.dns_cd_back),
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(AetherBackground),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.dns_intro),
                    color = AetherTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = stringResource(R.string.dns_order_hint, DnsRepository.MAX_TOTAL, DnsRepository.MAX_ENABLED),
                    color = AetherTextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Add field
            item {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        if (addError != null || addedDisabled) viewModel.clearMessages()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            stringResource(R.string.dns_input_placeholder),
                            color = AetherTextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    isError = addError != null,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canAdd) submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = AetherSurface,
                        unfocusedContainerColor = AetherSurface,
                        focusedBorderColor = AetherCyan.copy(alpha = 0.5f),
                        unfocusedBorderColor = AetherBorder.copy(alpha = 0.3f),
                        focusedTextColor = AetherTextPrimary,
                        unfocusedTextColor = AetherTextPrimary,
                    )
                )
                val error = addError
                when {
                    error != null -> Text(
                        error,
                        color = AetherDisconnected,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                    typingWarning != null -> Text(
                        "⚠ $typingWarning",
                        color = AetherConnecting,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                    addedDisabled -> Text(
                        stringResource(R.string.dns_added_disabled, DnsRepository.MAX_ENABLED),
                        color = AetherConnecting,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = submit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canAdd,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AetherCyan,
                        contentColor = AetherBackground,
                        disabledContainerColor = AetherSurfaceVariant,
                        disabledContentColor = AetherTextTertiary,
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dns_add_button), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                PresetChips(
                    existing = servers.map { it.address }.toSet(),
                    enabled = serversOrNull != null && servers.size < DnsRepository.MAX_TOTAL,
                    onPick = { viewModel.addPreset(it) }
                )
                Spacer(Modifier.height(4.dp))
            }

            when {
                serversOrNull == null -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AetherCyan)
                    }
                }

                servers.isEmpty() -> item {
                    Text(
                        stringResource(R.string.dns_list_empty),
                        color = AetherTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }

                servers.none { it.enabled } -> item {
                    Text(
                        stringResource(R.string.dns_all_disabled),
                        color = AetherConnecting,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }

            itemsIndexed(items = servers, key = { _, s -> s.id }) { index, server ->
                DnsServerRow(
                    server = server,
                    canMoveUp = index > 0,
                    canMoveDown = index < servers.lastIndex,
                    onToggle = { viewModel.setEnabled(server.id, !server.enabled) },
                    onUp = { viewModel.move(server.id, up = true) },
                    onDown = { viewModel.move(server.id, up = false) },
                    onDelete = { pendingDeleteId = server.id }
                )
            }

            // Options
            item {
                Spacer(Modifier.height(8.dp))
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        val ipv6 = ipv6Enabled
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .then(
                                    if (ipv6 != null) Modifier.toggleable(
                                        value = ipv6,
                                        role = Role.Switch,
                                        onValueChange = { viewModel.setIpv6Enabled(it) }
                                    ) else Modifier
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.dns_ipv6_title),
                                    color = AetherTextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (ipv6 != null) {
                                    Text(
                                        stringResource(if (ipv6) R.string.dns_ipv6_on else R.string.dns_ipv6_off),
                                        color = AetherTextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (ipv6 != null) {
                                Switch(
                                    checked = ipv6,
                                    onCheckedChange = null,
                                    colors = aetherSwitchColors()
                                )
                            } else {
                                Spacer(Modifier.size(width = 52.dp, height = 32.dp))
                            }
                        }
                        HorizontalDivider(color = AetherDivider, thickness = 1.dp)
                        TextButton(
                            onClick = { confirmReset = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, tint = AetherCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.dns_reset_button), color = AetherCyan)
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.dns_limitations),
                    color = AetherTextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                )
            }
        }
    }

    servers.firstOrNull { it.id == pendingDeleteId }?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            containerColor = AetherSurface,
            title = { Text(stringResource(R.string.dns_delete_title), color = AetherTextPrimary) },
            text = { Text("${target.label}\n${target.address}", color = AetherTextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(target.id)
                    pendingDeleteId = null
                }) { Text(stringResource(R.string.dns_delete_confirm), color = AetherDisconnected) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.dns_cancel), color = AetherTextSecondary)
                }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = AetherSurface,
            title = { Text(stringResource(R.string.dns_reset_title), color = AetherTextPrimary) },
            text = { Text(stringResource(R.string.dns_reset_message), color = AetherTextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToDefault()
                    confirmReset = false
                }) { Text(stringResource(R.string.dns_reset_confirm), color = AetherCyan) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.dns_cancel), color = AetherTextSecondary)
                }
            }
        )
    }
}

/**
 * Localized DNS summary: the repository returns either the enabled servers' labels or its
 * (Russian) default-summary constant — the latter is replaced with a string resource.
 * Also used by the Settings "DNS" row.
 */
@Composable
internal fun dnsSummaryText(summary: String): String =
    if (summary == DnsPresets.DEFAULT_SUMMARY) stringResource(R.string.dns_summary_default) else summary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetChips(
    existing: Set<String>,
    enabled: Boolean,
    onPick: (DnsPreset) -> Unit
) {
    Column {
        Text(
            stringResource(R.string.dns_presets_label),
            color = AetherTextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DnsPresets.GROUPS.forEach { group ->
                val variants = DnsPresets.ALL.filter { it.group == group }
                val available = variants.filter { it.address !in existing }
                var expanded by remember { mutableStateOf(false) }
                Box {
                    AssistChip(
                        onClick = {
                            if (available.size == 1 && variants.size == 1) onPick(available[0])
                            else expanded = true
                        },
                        enabled = enabled && available.isNotEmpty(),
                        label = { Text(group, fontSize = 13.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = AetherSurface,
                            labelColor = AetherTextPrimary,
                            disabledContainerColor = AetherSurface,
                            disabledLabelColor = AetherTextTertiary,
                        )
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(AetherSurface)
                    ) {
                        variants.forEach { preset ->
                            val already = preset.address in existing
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(preset.label, color = if (already) AetherTextTertiary else AetherTextPrimary, fontSize = 14.sp)
                                        Text(
                                            if (already) stringResource(R.string.dns_preset_already_added) else preset.address,
                                            color = AetherTextTertiary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                enabled = !already,
                                onClick = {
                                    expanded = false
                                    onPick(preset)
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
private fun DnsServerRow(
    server: DnsServer,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit
) {
    val warning = remember(server.address) { DnsValidator.warningFor(server.address) }
    val typeLabel = when (server.type) {
        DnsType.DOH -> "DoH"
        DnsType.TCP -> "TCP"
        DnsType.UDP -> "UDP"
    }
    // Whole row toggles the server; the up/down/delete IconButtons (48dp each) keep their own clicks.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (server.enabled) AetherSurfaceVariant else AetherSurface)
            .toggleable(value = server.enabled, role = Role.Switch, onValueChange = { onToggle() })
            .padding(start = 0.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(verticalArrangement = Arrangement.Center) {
            IconButton(onClick = onUp, enabled = canMoveUp) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.dns_cd_move_up, server.label),
                    tint = if (canMoveUp) AetherTextSecondary else AetherTextTertiary.copy(alpha = 0.4f)
                )
            }
            IconButton(onClick = onDown, enabled = canMoveDown) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.dns_cd_move_down, server.label),
                    tint = if (canMoveDown) AetherTextSecondary else AetherTextTertiary.copy(alpha = 0.4f)
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    server.label,
                    color = AetherTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    typeLabel,
                    color = if (server.type == DnsType.DOH) AetherConnected else AetherConnecting,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                server.address,
                color = AetherTextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (warning != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AetherConnecting, modifier = Modifier.size(12.dp))
                    Text(warning, color = AetherConnecting, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.dns_cd_delete, server.label),
                tint = AetherTextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
        Switch(
            checked = server.enabled,
            onCheckedChange = null,
            colors = aetherSwitchColors()
        )
    }
}

@Composable
private fun aetherSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = AetherBackground,
    checkedTrackColor = AetherCyan,
    uncheckedThumbColor = AetherTextTertiary,
    uncheckedTrackColor = AetherSurfaceVariant
)
