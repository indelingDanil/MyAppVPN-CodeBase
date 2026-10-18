package com.myappvpn.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.myappvpn.data.model.SubscriptionSource
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.SubscriptionsEvent
import com.myappvpn.ui.viewmodel.SubscriptionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    viewModel: SubscriptionsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val sourcesOrNull by viewModel.sources.collectAsStateWithLifecycle()
    val sources = sourcesOrNull.orEmpty()
    val addError by viewModel.addError.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var newUrl by rememberSaveable { mutableStateOf("") }

    // Dialog targets (ids survive rotation; the source itself is looked up in the live list).
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDisableId by rememberSaveable { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                // Clear the field ONLY after the repository accepted the URL.
                SubscriptionsEvent.Added -> newUrl = ""
                SubscriptionsEvent.RefreshFailed -> snackbarHostState.showSnackbar(
                    context.getString(R.string.subs_refresh_failed)
                )
            }
        }
    }

    val enabledCount = sources.count { it.enabled }
    val submit: () -> Unit = {
        val url = newUrl.trim()
        if (url.isNotEmpty()) viewModel.addSubscription(url)
    }

    val topBarScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = AetherBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                scrollBehavior = topBarScroll,
                title = {
                    Column {
                        Text(
                            stringResource(R.string.subs_title),
                            color = AetherTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        if (sourcesOrNull != null) {
                            Text(
                                stringResource(R.string.subs_enabled_count, enabledCount, sources.size),
                                color = AetherTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.subs_cd_back),
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
                    text = stringResource(R.string.subs_intro),
                    color = AetherTextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Add field
            item {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = {
                        newUrl = it
                        if (addError != null) viewModel.clearAddError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.subs_url_placeholder), color = AetherTextTertiary)
                    },
                    singleLine = true,
                    isError = addError != null,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = AetherSurface,
                        unfocusedContainerColor = AetherSurface,
                        focusedBorderColor = AetherCyan.copy(alpha = 0.5f),
                        unfocusedBorderColor = AetherBorder.copy(alpha = 0.3f),
                        focusedTextColor = AetherTextPrimary,
                        unfocusedTextColor = AetherTextPrimary,
                    )
                )
                addError?.let {
                    Text(it, color = AetherDisconnected, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = submit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = newUrl.isNotBlank(),
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
                    Text(stringResource(R.string.subs_add_button), fontWeight = FontWeight.SemiBold)
                }
                if (refreshing) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = AetherCyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.subs_refreshing), color = AetherTextSecondary, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            when {
                sourcesOrNull == null -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AetherCyan)
                    }
                }

                sources.isEmpty() -> item {
                    Text(
                        text = stringResource(R.string.subs_empty),
                        color = AetherTextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }

                enabledCount == 0 -> item {
                    Text(
                        text = stringResource(R.string.subs_none_enabled),
                        color = AetherConnecting,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
                    )
                }
            }

            items(items = sources, key = { it.id }) { source ->
                SubscriptionRow(
                    source = source,
                    onToggle = {
                        if (source.enabled && enabledCount == 1) {
                            pendingDisableId = source.id   // last enabled source → confirm first
                        } else {
                            viewModel.setEnabled(source.id, !source.enabled)
                        }
                    },
                    onDelete = { pendingDeleteId = source.id }
                )
            }
        }
    }

    // Shown only while the target still exists in the live list.
    sources.firstOrNull { it.id == pendingDeleteId }?.let { target ->
        val isLastEnabled = target.enabled && enabledCount == 1
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            containerColor = AetherSurface,
            title = { Text(stringResource(R.string.subs_delete_title), color = AetherTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        hostOf(target),
                        color = AetherTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isLastEnabled) {
                        Text(stringResource(R.string.subs_last_source_warning), color = AetherConnecting)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeSubscription(target.id)
                    pendingDeleteId = null
                }) { Text(stringResource(R.string.subs_delete_confirm), color = AetherDisconnected) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.subs_cancel), color = AetherTextSecondary)
                }
            }
        )
    }

    pendingDisableId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDisableId = null },
            containerColor = AetherSurface,
            title = { Text(stringResource(R.string.subs_disable_last_title), color = AetherTextPrimary) },
            text = { Text(stringResource(R.string.subs_last_source_warning), color = AetherTextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setEnabled(id, false)
                    pendingDisableId = null
                }) { Text(stringResource(R.string.subs_disable_confirm), color = AetherDisconnected) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisableId = null }) {
                    Text(stringResource(R.string.subs_cancel), color = AetherTextSecondary)
                }
            }
        )
    }
}

/** Host of a custom subscription URL for the delete dialog (falls back to the raw URL). */
private fun hostOf(source: SubscriptionSource): String {
    val url = source.urls.firstOrNull() ?: return source.name
    return runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
}

@Composable
private fun SubscriptionRow(
    source: SubscriptionSource,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    // The whole row is the switch target; the Switch itself is display-only.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (source.enabled) AetherSurfaceVariant else AetherSurface)
            .toggleable(value = source.enabled, role = Role.Switch, onValueChange = { onToggle() })
            .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (source.isBuiltin) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = AetherTextTertiary, modifier = Modifier.size(13.dp))
                }
                Text(
                    source.name,
                    color = AetherTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (source.isBuiltin) stringResource(R.string.subs_builtin_source) else (source.urls.firstOrNull() ?: ""),
                color = AetherTextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!source.isBuiltin) {
            // Default IconButton size = 48dp touch target.
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.subs_cd_delete, source.name),
                    tint = AetherTextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Switch(
            checked = source.enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AetherBackground,
                checkedTrackColor = AetherCyan,
                uncheckedThumbColor = AetherTextTertiary,
                uncheckedTrackColor = AetherSurfaceVariant
            )
        )
    }
}
