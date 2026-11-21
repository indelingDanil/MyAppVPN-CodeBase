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
import com.myappvpn.ui.viewmodel.OwnExit
import com.myappvpn.domain.OpenFluxChannel
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.ContentPaste

private const val OPENFLUX_REPO_URL = "https://github.com/indelingDanil/MyAppVPN/tree/openflux-exit"   // Beget guide

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenFluxScreen(
    viewModel: OpenFluxViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val savedOrNull by viewModel.saved.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
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
    var channelKey by rememberSaveable { mutableStateOf(OpenFluxChannel.DIRECT.key) }
    var mailruDraft by rememberSaveable { mutableStateOf("") }
    var cupsDraft by rememberSaveable { mutableStateOf("") }
    var shareDraft by rememberSaveable { mutableStateOf("") }
    var secretDraft by rememberSaveable { mutableStateOf("") }
    var draftsLoaded by rememberSaveable { mutableStateOf(false) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var secretVisible by rememberSaveable { mutableStateOf(false) }
    var howToExpanded by rememberSaveable { mutableStateOf(false) }

    fun loadDrafts(e: OwnExit) {
        urlDraft = e.url
        tokenDraft = e.token
        channelKey = e.channel.key
        mailruDraft = e.mailru
        cupsDraft = e.cups
        shareDraft = e.share
        secretDraft = e.secret
    }

    LaunchedEffect(savedOrNull) {
        val s = savedOrNull
        if (!draftsLoaded && s != null) {
            loadDrafts(s)
            draftsLoaded = true
        }
    }

    val saved = savedOrNull ?: OwnExit()
    val savedUrl = saved.url
    val savedToken = saved.token
    val channel = OpenFluxChannel.effective(channelKey)
    val draft = OwnExit(urlDraft, tokenDraft, channel, mailruDraft, cupsDraft, shareDraft, secretDraft)
    val urlError = remember(urlDraft) { OpenFluxConfig.validateUrl(urlDraft) }
    // Errors are shown (and block saving) only for the fields of the selected channel.
    val shownUrlError = urlError?.takeIf { it != OpenFluxConfig.UrlError.EMPTY && channel == OpenFluxChannel.DIRECT }
    val linkError = remember(channel, draft.linkFor(channel)) {
        OpenFluxConfig.validateLink(channel, draft.linkFor(channel))
    }
    val shownLinkError = linkError?.takeIf { it != OpenFluxConfig.LinkError.EMPTY }
    val secretShort = channel == OpenFluxChannel.LINK && OpenFluxConfig.secretTooShort(secretDraft)
    val noErrors = shownUrlError == null && shownLinkError == null && !secretShort
    val draftComplete = OpenFluxConfig.isComplete(draft)
    val dirty = draftsLoaded && draft.trimmed() != saved
    // Switching to a channel that isn't filled in yet is saved only together with its link
    // (else the switch would turn the mode off). An empty URL/link may be saved (clearing it).
    val canSave = dirty && noErrors && (channel == saved.channel || draftComplete)
    // Focus loss / leaving the screen: persist edited fields, but keep the saved channel while
    // the newly picked one is incomplete.
    val autoDraft = if (channel != saved.channel && !draftComplete) draft.copy(channel = saved.channel) else draft
    val canAutoSave = draftsLoaded && noErrors && autoDraft.trimmed() != saved
    // The demo exit is chosen via the openfluxDemo pref (its URL/token are never shown here).
    val demoChosen = demoOrNull == true
    // Effective config usable: the demo counts as complete; else the user's own saved settings.
    val savedComplete = demoChosen || OpenFluxConfig.isComplete(saved)
    val sessionActive = vpnState == VpnState.CONNECTED ||
        vpnState == VpnState.CONNECTING || vpnState == VpnState.BLOCKED
    // The demo is "selected" when the demo flag is on.
    val demoSelected = demoChosen
    // Demo selected + a complete own config in the fields → the Save button switches to the own exit.
    val switchFromDemo = demoChosen && noErrors && draftComplete
    val statusRes = OpenFluxConfig.statusMessageRes(openFluxStatus)

    val save: () -> Unit = {
        if (canAutoSave) viewModel.save(autoDraft)
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
            // Hidden once the demo campaign is over (unless it is still selected, so the user can see it).
            if (demoSelected || System.currentTimeMillis() / 1000 < OpenFluxConfig.DEMO_UNTIL_SEC) {
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
                                loadDrafts(saved)
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

                // ── Channel to the exit ──
                ChannelSelector(
                    selected = channel,
                    enabled = draftsLoaded,
                    demoChosen = demoChosen,
                    onSelect = {
                        channelKey = it.key
                        focusManager.clearFocus()
                    }
                )
                Spacer(Modifier.height(8.dp))

                if (channel == OpenFluxChannel.DIRECT) {
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
                } else {
                    // ── Carrier channels: the link (+ key for share links) ──
                    val pasteEmpty = stringResource(R.string.openflux_paste_empty)
                    val pasteNotLink = stringResource(R.string.openflux_paste_not_link)
                    val linkErrorRes = when (shownLinkError) {
                        OpenFluxConfig.LinkError.NOT_HTTP -> R.string.openflux_link_err_http
                        OpenFluxConfig.LinkError.OPENFLUX_HERE -> R.string.openflux_link_err_openflux_here
                        OpenFluxConfig.LinkError.NOT_OPENFLUX -> R.string.openflux_link_err_not_openflux
                        OpenFluxConfig.LinkError.SPACES -> R.string.openflux_link_err_spaces
                        OpenFluxConfig.LinkError.EMPTY, null -> null
                    }
                    val setLink: (String) -> Unit = { v ->
                        when (channel) {
                            OpenFluxChannel.MAILRU -> mailruDraft = v
                            OpenFluxChannel.CUPS -> cupsDraft = v
                            OpenFluxChannel.LINK -> shareDraft = v
                            OpenFluxChannel.DIRECT -> Unit
                        }
                    }
                    OpenFluxLinkField(
                        value = draft.linkFor(channel),
                        onValueChange = setLink,
                        label = stringResource(
                            when (channel) {
                                OpenFluxChannel.MAILRU -> R.string.openflux_mailru_label
                                OpenFluxChannel.CUPS -> R.string.openflux_cups_label
                                else -> R.string.openflux_share_label
                            }
                        ),
                        placeholder = when (channel) {
                            OpenFluxChannel.MAILRU -> stringResource(R.string.openflux_mailru_placeholder)
                            OpenFluxChannel.LINK -> stringResource(R.string.openflux_share_placeholder)
                            else -> null
                        },
                        errorRes = linkErrorRes,
                        enabled = draftsLoaded,
                        imeAction = if (channel == OpenFluxChannel.LINK) ImeAction.Next else ImeAction.Done,
                        onFocusLost = save,
                        onDone = {
                            save()
                            focusManager.clearFocus()
                        }
                    )
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                            when {
                                text.isEmpty() -> Toast.makeText(context, pasteEmpty, Toast.LENGTH_SHORT).show()
                                channel == OpenFluxChannel.LINK && !text.startsWith("openflux://", ignoreCase = true) ->
                                    Toast.makeText(context, pasteNotLink, Toast.LENGTH_SHORT).show()
                                else -> {
                                    setLink(text)
                                    focusManager.clearFocus()
                                }
                            }
                        },
                        enabled = draftsLoaded,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = null,
                            tint = AetherCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.openflux_paste), color = AetherCyan, fontWeight = FontWeight.SemiBold)
                    }

                    if (channel == OpenFluxChannel.LINK) {
                        var secretHadFocus by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = secretDraft,
                            onValueChange = { secretDraft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { st ->
                                    if (st.isFocused) secretHadFocus = true
                                    else if (secretHadFocus) {
                                        secretHadFocus = false
                                        save()
                                    }
                                },
                            enabled = draftsLoaded,
                            label = { Text(stringResource(R.string.openflux_secret_label)) },
                            singleLine = true,
                            isError = secretShort,
                            supportingText = if (secretShort) {
                                { Text(stringResource(R.string.openflux_secret_err_short), color = AetherDisconnected) }
                            } else null,
                            visualTransformation = if (secretVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { secretVisible = !secretVisible }) {
                                    Icon(
                                        if (secretVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = stringResource(
                                            if (secretVisible) R.string.openflux_secret_hide
                                            else R.string.openflux_secret_show
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
                    }
                }

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
                            savedUrl.isNotEmpty() || savedToken.isNotEmpty() ||
                                saved.linkFor(saved.channel).isNotEmpty() ->
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
                            if (canSave || switchFromDemo) viewModel.save(draft)
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

    // openflux:// link opened from another app: confirm before it lands in the settings.
    val importLink = pendingImport
    if (importLink != null && draftsLoaded) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeImport() },
            containerColor = AetherSurface,
            title = {
                Text(stringResource(R.string.openflux_import_title), color = AetherTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    stringResource(R.string.openflux_import_text),
                    color = AetherTextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        channelKey = OpenFluxChannel.LINK.key
                        shareDraft = importLink
                        viewModel.consumeImport()
                        val next = draft.copy(channel = OpenFluxChannel.LINK, share = importLink)
                        // A usable link is saved right away (switching from the demo, too); a
                        // damaged one stays in the field with its error.
                        if (OpenFluxConfig.isComplete(next)) viewModel.save(next)
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.openflux_import_ok), color = AetherCyan, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.consumeImport() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.openflux_import_cancel), color = AetherTextSecondary)
                }
            }
        )
    }
}

/** «Канал до выхода»: Direct / Mail.ru / Cups / openflux:// link, each with a short explanation. */
@Composable
private fun ChannelSelector(
    selected: OpenFluxChannel,
    enabled: Boolean,
    demoChosen: Boolean,
    onSelect: (OpenFluxChannel) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(vertical = 8.dp)
        ) {
            Text(
                stringResource(R.string.openflux_channel_title),
                color = AetherTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            if (demoChosen) {
                Text(
                    stringResource(R.string.openflux_channel_demo_note),
                    color = AetherTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                )
            }
            listOf(
                Triple(OpenFluxChannel.DIRECT, R.string.openflux_channel_direct, R.string.openflux_channel_direct_desc),
                Triple(OpenFluxChannel.MAILRU, R.string.openflux_channel_mailru, R.string.openflux_channel_mailru_desc),
                Triple(OpenFluxChannel.CUPS, R.string.openflux_channel_cups, R.string.openflux_channel_cups_desc),
                Triple(OpenFluxChannel.LINK, R.string.openflux_channel_link, R.string.openflux_channel_link_desc),
            ).filter { it.first in OpenFluxChannel.selectable }.forEach { (ch, titleRes, descRes) ->
                val isSelected = ch == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(ch) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        enabled = enabled,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = AetherCyan,
                            unselectedColor = AetherTextTertiary
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(titleRes),
                            color = if (isSelected) AetherCyan else AetherTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            stringResource(descRes),
                            color = AetherTextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

/** Link / room-code field of a carrier channel (focus-loss save like the URL field). */
@Composable
private fun OpenFluxLinkField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    errorRes: Int?,
    enabled: Boolean,
    imeAction: ImeAction,
    onFocusLost: () -> Unit,
    onDone: () -> Unit,
) {
    var hadFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { st ->
                if (st.isFocused) hadFocus = true
                else if (hadFocus) {
                    hadFocus = false
                    onFocusLost()
                }
            },
        enabled = enabled,
        label = { Text(label) },
        placeholder = placeholder?.let {
            { Text(it, color = AetherTextTertiary, maxLines = 1) }
        },
        singleLine = true,
        isError = errorRes != null,
        supportingText = errorRes?.let {
            { Text(stringResource(it), color = AetherDisconnected) }
        },
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = openFluxFieldColors()
    )
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
