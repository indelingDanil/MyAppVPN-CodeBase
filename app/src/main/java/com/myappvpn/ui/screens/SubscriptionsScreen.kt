package com.myappvpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.myappvpn.data.model.SubscriptionSource
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.SubscriptionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    viewModel: SubscriptionsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val sources by viewModel.sources.collectAsState()
    val addError by viewModel.addError.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    var newUrl by remember { mutableStateOf("") }

    Scaffold(
        containerColor = AetherBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Подписки", color = AetherTextPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "${sources.count { it.enabled }} из ${sources.size} включено",
                            color = AetherTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AetherTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AetherBackground)
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
                    text = "Добавьте свои подписки (ссылка на список VLESS, https://). Выключенные источники не участвуют в поиске рабочей прокси.",
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
                    placeholder = { Text("https://example.com/sub.txt", color = AetherTextTertiary) },
                    singleLine = true,
                    isError = addError != null,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
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
                    onClick = {
                        val url = newUrl.trim()
                        if (url.isNotEmpty()) viewModel.addSubscription(url).also { newUrl = "" }
                    },
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
                    Text("Добавить подписку", fontWeight = FontWeight.SemiBold)
                }
                if (refreshing) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = AetherCyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text("Обновляю серверы…", color = AetherTextSecondary, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            items(items = sources, key = { it.id }) { source ->
                SubscriptionRow(
                    source = source,
                    onToggle = { viewModel.setEnabled(source.id, !source.enabled) },
                    onDelete = { viewModel.removeSubscription(source.id) }
                )
            }
        }
    }
}

@Composable
private fun SubscriptionRow(
    source: SubscriptionSource,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (source.enabled) AetherSurfaceVariant else AetherSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (source.isBuiltin) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = AetherTextTertiary, modifier = Modifier.size(13.dp))
                }
                Text(source.name, color = AetherTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                text = if (source.isBuiltin) "Встроенный источник" else (source.urls.firstOrNull() ?: ""),
                color = AetherTextSecondary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        if (!source.isBuiltin) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = AetherTextTertiary, modifier = Modifier.size(18.dp))
            }
        }
        Switch(
            checked = source.enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = AetherBackground,
                checkedTrackColor = AetherCyan,
                uncheckedThumbColor = AetherTextTertiary,
                uncheckedTrackColor = AetherSurfaceVariant
            )
        )
    }
}
