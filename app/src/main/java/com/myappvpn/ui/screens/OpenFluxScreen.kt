package com.myappvpn.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myappvpn.R
import com.myappvpn.service.VpnState
import com.myappvpn.ui.components.GlassCard
import com.myappvpn.ui.theme.*
import com.myappvpn.ui.viewmodel.OpenFluxConfig
import com.myappvpn.ui.viewmodel.OpenFluxViewModel

private const val OPENFLUX_REPO_URL = "https://github.com/indelingDanil/MyAppVPN/tree/openflux-exit"   // Beget guide

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenFluxScreen(
    viewModel: OpenFluxViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val savedUrlOrNull by viewModel.url.collectAsStateWithLifecycle()
    val savedTokenOrNull by viewModel.token.collectAsStateWithLifecycle()
    val demoOrNull by viewModel.demo.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val openFluxStatus by viewModel.openFluxStatus.collectAsStateWithLifecycle()
    val diagRunning by viewModel.diagRunning.collectAsStateWithLifecycle()
    val diagReport by viewModel.diagReport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Drafts: edited locally, written to DataStore only on Save / Done / focus loss / leaving.
    var urlDraft by rememberSaveable { mutableStateOf("") }
    var tokenDraft by rememberSaveable { mutableStateOf("") }
    var draftsLoaded by rememberSaveable { mutableStateOf(false) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var howToExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(savedUrlOrNull, savedTokenOrNull) {
        val u = savedUrlOrNull
        val t = savedTokenOrNull
        if (!draftsLoaded && u != null && t != null) {
            urlDraft = u
            tokenDraft = t
            draftsLoaded = true
        }
    }

    val savedUrl = savedUrlOrNull.orEmpty()
    val savedToken = savedTokenOrNull.orEmpty()
    val urlError = remember(urlDraft) { OpenFluxConfig.validateUrl(urlDraft) }
    val shownUrlError = urlError?.takeIf { it != OpenFluxConfig.UrlError.EMPTY }
    val dirty = draftsLoaded &&
        (urlDraft.trim() != savedUrl || tokenDraft.trim() != savedToken)
    // An empty URL may be saved (clearing the config); a non-empty one must be valid.
    val canSave = dirty && shownUrlError == null
    // The demo exit is chosen via the openfluxDemo pref (its URL/token are never shown here).
    val demoChosen = demoOrNull == true
    // Effective config usable: the demo counts as complete; else the user's own saved pair.
    val savedComplete = demoChosen || OpenFluxConfig.isComplete(savedUrl, savedToken)
    val sessionActive = vpnState == VpnState.CONNECTED ||
        vpnState == VpnState.CONNECTING || vpnState == VpnState.BLOCKED
    // The demo is "selected" when the demo flag is on.
    val demoSelected = demoChosen
    // Demo selected + a complete own pair in the fields → the Save button switches to the own exit.
    val switchFromDemo = demoChosen && shownUrlError == null &&
        OpenFluxConfig.isComplete(urlDraft, tokenDraft)
    val statusRes = OpenFluxConfig.statusMessageRes(openFluxStatus)

    val save: () -> Unit = {
        if (canSave) viewModel.save(urlDraft, tokenDraft)
    }
    // Leaving the screen counts as focus loss: persist valid pending edits.
    val latestSave by rememberUpdatedState(save)
    DisposableEffect(Unit) {
        onDispose { latestSave() }
    }

    val topBarScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = AetherBackground,
        topBar = {
            TopAppBar(
                scrollBehavior = topBarScroll,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.openflux_title),
                            color = AetherTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        BetaBadge()
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.openflux_cd_back),
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Demo exit (prominent, first) ──
            GlassCard(modifier = Modifier.fillMaxWidth(), borderColor = AetherCyan.copy(alpha = 0.5f)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AetherCyan.copy(alpha = 0.08f))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.openflux_demo_title),
                        color = AetherCyan,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.openflux_demo_text),
                        color = AetherTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    if (demoSelected) {
                        Text(
                            stringResource(R.string.openflux_demo_selected),
                            color = AetherConnected,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        Button(
                            onClick = {
                                // The URL/token fields keep the user's own values; drop unsaved
                                // edits so leaving the screen doesn't save them (and unselect the demo).
                                urlDraft = savedUrl
                                tokenDraft = savedToken
                                focusManager.clearFocus()
                                viewModel.useDemo()
                            },
                            enabled = enabled != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AetherCyan,
                                contentColor = AetherBackground,
                                disabledContainerColor = AetherSurfaceVariant,
                                disabledContentColor = AetherTextTertiary
                            )
                        ) {
                            Text(stringResource(R.string.openflux_demo_button), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── Last exit refusal (the session was stopped) ──
            if (statusRes != null) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("⚠", color = AetherConnecting, fontSize = 16.sp, modifier = Modifier.clearAndSetSemantics { })
                        Text(
                            stringResource(statusRes),
                            color = AetherTextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // ── Intro ──
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🧪", fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics { })
                    Text(
                        stringResource(R.string.openflux_intro),
                        color = AetherTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            // ── Enable switch ──
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    val checked = enabled
                    // Turning OFF is always allowed; turning ON needs a saved, valid config.
                    val canToggle = checked != null && (checked || savedComplete)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .then(
                                if (checked != null) Modifier.toggleable(
                                    value = checked,
                                    enabled = canToggle,
                                    role = Role.Switch,
                                    onValueChange = { viewModel.setEnabled(it) }
                                ) else Modifier
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .alpha(if (canToggle || checked == null) 1f else 0.6f)
                        ) {
                            Text(
                                stringResource(R.string.openflux_enable_title),
                                color = AetherTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                stringResource(R.string.openflux_enable_subtitle),
                                color = AetherTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        if (checked != null) {
                            Switch(
                                checked = checked,
                                onCheckedChange = null,
                                enabled = canToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AetherBackground,
                                    checkedTrackColor = AetherCyan,
                                    uncheckedThumbColor = AetherTextTertiary,
                                    uncheckedTrackColor = AetherSurfaceVariant
                                )
                            )
                        } else {
                            Spacer(Modifier.size(width = 52.dp, height = 32.dp))
                        }
                    }
                    if (enabled == false && !savedComplete) {
                        Text(
                            stringResource(R.string.openflux_enable_hint_fill),
                            color = AetherConnecting,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        )
                    }
                }
            }

            // ── URL + token ──
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.openflux_section_settings).uppercase(),
                    color = AetherCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
                Text(
                    text = stringResource(R.string.openflux_own_exit_note),
                    color = AetherTextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )

                // Focus-loss save: only after the field actually had focus (onFocusChanged
                // also fires once with "not focused" on first composition).
                var urlHadFocus by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { st ->
                            if (st.isFocused) urlHadFocus = true
                            else if (urlHadFocus) {
                                urlHadFocus = false
                                save()
                            }
                        },
                    enabled = draftsLoaded,
                    label = { Text(stringResource(R.string.openflux_url_label)) },
                    placeholder = {
                        Text(
                            stringResource(R.string.openflux_url_placeholder),
                            color = AetherTextTertiary,
                            maxLines = 1
                        )
                    },
                    singleLine = true,
                    isError = shownUrlError != null,
                    supportingText = if (shownUrlError != null) {
                        {
                            Text(
                                stringResource(
                                    when (shownUrlError) {
                                        OpenFluxConfig.UrlError.SCHEME -> R.string.openflux_url_err_scheme
                                        OpenFluxConfig.UrlError.HOST -> R.string.openflux_url_err_host
                                        OpenFluxConfig.UrlError.PHP -> R.string.openflux_url_err_php
                                        OpenFluxConfig.UrlError.INVALID,
                                        OpenFluxConfig.UrlError.EMPTY -> R.string.openflux_url_err_invalid
                                    }
                                ),
                                color = AetherDisconnected
                            )
                        }
                    } else null,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    colors = openFluxFieldColors()
                )

                if (shownUrlError == null && OpenFluxConfig.isPlainHttp(urlDraft)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("⚠", color = AetherConnecting, fontSize = 12.sp, modifier = Modifier.clearAndSetSemantics { })
                        Text(
                            stringResource(R.string.openflux_http_warning),
                            color = AetherConnecting,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                var tokenHadFocus by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { st ->
                            if (st.isFocused) tokenHadFocus = true
                            else if (tokenHadFocus) {
                                tokenHadFocus = false
                                save()
                            }
                        },
                    enabled = draftsLoaded,
                    label = { Text(stringResource(R.string.openflux_token_label)) },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (tokenVisible) R.string.openflux_token_hide
                                    else R.string.openflux_token_show
                                ),
                                tint = AetherTextTertiary
                            )
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        save()
                        focusManager.clearFocus()
                    }),
                    colors = openFluxFieldColors()
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = when {
                            !draftsLoaded -> ""
                            dirty -> stringResource(R.string.openflux_unsaved)
                            savedUrl.isNotEmpty() || savedToken.isNotEmpty() ->
                                stringResource(R.string.openflux_saved)
                            else -> ""
                        },
                        color = if (dirty) AetherConnecting else AetherTextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    )
                    Button(
                        onClick = {
                            // Explicit Save also switches FROM the demo to the own exit, even when
                            // the fields are unchanged (auto-save on focus loss must not do that).
                            if (canSave || switchFromDemo) viewModel.save(urlDraft, tokenDraft)
                            focusManager.clearFocus()
                        },
                        enabled = canSave || switchFromDemo,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AetherCyan,
                            contentColor = AetherBackground,
                            disabledContainerColor = AetherSurfaceVariant,
                            disabledContentColor = AetherTextTertiary
                        )
                    ) {
                        Text(stringResource(R.string.openflux_save), fontWeight = FontWeight.SemiBold)
                    }
                }

                if (sessionActive && enabled == true) {
                    Text(
                        stringResource(R.string.openflux_reconnect_note),
                        color = AetherTextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }

                // ── Connection diagnostics ──
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        save()   // diagnose what's in the fields (if valid)
                        focusManager.clearFocus()
                        viewModel.runDiagnostics()
                    },
                    enabled = !diagRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AetherCyan,
                        disabledContentColor = AetherTextTertiary
                    )
                ) {
                    Text(stringResource(R.string.openflux_diag_button), fontWeight = FontWeight.SemiBold)
                }
                if (diagRunning) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AetherCyan
                        )
                        Text(
                            stringResource(R.string.openflux_diag_running),
                            color = AetherTextPrimary,
                            fontSize = 12.sp
                        )
                    }
                }
                Text(
                    stringResource(R.string.openflux_diag_note),
                    color = AetherTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // ── How-to (collapsible) ──
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { howToExpanded = !howToExpanded }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.openflux_howto_title),
                            color = AetherTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (howToExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(
                                if (howToExpanded) R.string.openflux_cd_collapse else R.string.openflux_cd_expand
                            ),
                            tint = AetherTextTertiary
                        )
                    }
                    AnimatedVisibility(visible = howToExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                R.string.openflux_howto_step1,
                                R.string.openflux_howto_step2,
                                R.string.openflux_howto_step3,
                                R.string.openflux_howto_step4
                            ).forEach { res ->
                                Text(
                                    stringResource(res),
                                    color = AetherTextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                            val noBrowser = stringResource(R.string.openflux_no_browser)
                            TextButton(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(OPENFLUX_REPO_URL))
                                        )
                                    } catch (e: ActivityNotFoundException) {
                                        Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(
                                    Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    tint = AetherCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.openflux_github),
                                    color = AetherCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    diagReport?.let { report ->
        DiagReportDialog(report = report, onDismiss = { viewModel.dismissDiagReport() })
    }
}

@Composable
private fun DiagReportDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.openflux_diag_copied)
    val shareTitle = stringResource(R.string.openflux_diag_share_title)
    val subject = stringResource(R.string.openflux_diag_title)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AetherSurface,
        title = {
            Text(subject, color = AetherTextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            SelectionContainer {
                Text(
                    report,
                    color = AetherTextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText(subject, report))
                        // Android 13+ shows its own "copied" confirmation.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.openflux_diag_copy), color = AetherCyan, fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        try {
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        } catch (_: ActivityNotFoundException) { }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.openflux_diag_share), color = AetherCyan, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.openflux_diag_close), color = AetherTextSecondary)
            }
        }
    )
}

@Composable
private fun BetaBadge() {
    Text(
        text = stringResource(R.string.openflux_beta).uppercase(),
        color = AetherPurpleLight,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AetherPurple.copy(alpha = 0.22f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun openFluxFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = AetherSurface,
    unfocusedContainerColor = AetherSurface,
    disabledContainerColor = AetherSurface,
    focusedBorderColor = AetherCyan.copy(alpha = 0.5f),
    unfocusedBorderColor = AetherBorder.copy(alpha = 0.3f),
    focusedTextColor = AetherTextPrimary,
    unfocusedTextColor = AetherTextPrimary,
    focusedLabelColor = AetherCyan,
    unfocusedLabelColor = AetherTextSecondary,
    cursorColor = AetherCyan
)
