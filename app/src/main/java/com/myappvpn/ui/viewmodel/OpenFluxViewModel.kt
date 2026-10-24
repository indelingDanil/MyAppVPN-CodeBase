package com.myappvpn.ui.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.myappvpn.R
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.domain.ConnectionController
import com.myappvpn.domain.OpenFluxDiagnostics
import com.myappvpn.domain.OpenFluxChannel
import com.myappvpn.domain.OpenFluxEndpoint
import kotlinx.coroutines.flow.combine
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import javax.inject.Inject

/** Validation / parsing helpers for the OpenFlux (Beta) exit settings. */
object OpenFluxConfig {
    /** Id of the synthetic ServerProfile the service publishes while connected via OpenFlux. */
    const val SYNTHETIC_SERVER_ID = "__openflux__"

    /**
     * Public demo exit run by the author (shared hosting): up to [DEMO_MAX_USERS] people at once
     * (no queue → "busy"), a ONE-TIME trial per install of 1 h or 2 GB ("expired" afterwards), a
     * daily traffic budget for the whole demo ("quota"), campaign end 14.10.2026 ("ended").
     * Its URL/token live (obfuscated) in [OpenFluxEndpoint] and are selected by the
     * `openfluxDemo` pref — they are never stored in the URL/token prefs nor shown in the UI.
     */
    const val DEMO_MAX_USERS = 8
    const val DEMO_TRIAL_MINUTES = 60
    const val DEMO_TRIAL_GB = 2
    /** Demo campaign end (14.10.2026 23:59:59 MSK); the demo card is hidden after it. */
    const val DEMO_UNTIL_SEC = 1792011599L

    /** User-facing explanation of an OpenFlux refusal ([MyVpnService.openFluxStatus]); null = none. */
    @StringRes
    fun statusMessageRes(status: String?): Int? = when (status) {
        "busy" -> R.string.openflux_status_busy
        "expired" -> R.string.openflux_status_expired
        "ended" -> R.string.openflux_status_ended
        "quota" -> R.string.openflux_status_quota
        "badtoken" -> R.string.openflux_status_badtoken
        "noclient" -> R.string.openflux_status_noclient
        "unsupported" -> R.string.openflux_status_unsupported
        else -> null
    }

    enum class UrlError { EMPTY, INVALID, SCHEME, HOST, PHP }

    /** null = valid phpbox.php URL (http/https, has a host, path ends with .php). */
    fun validateUrl(raw: String): UrlError? {
        val url = raw.trim()
        if (url.isEmpty()) return UrlError.EMPTY
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return UrlError.INVALID
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return UrlError.SCHEME
        if (uri.host.isNullOrBlank()) return UrlError.HOST
        return null
    }

    /** Host part of the URL, or null when it can't be parsed. */
    fun host(raw: String): String? = try {
        URI(raw.trim()).host?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    fun isPlainHttp(raw: String): Boolean = raw.trim().startsWith("http://", ignoreCase = true)

    /** URL valid and token non-blank → the mode can be turned on. */
    fun isComplete(url: String, token: String): Boolean =
        validateUrl(url) == null && token.isNotBlank()

    // ── Channels (Mail.ru / Cups / openflux:// link) ──

    /** Prefix of OpenFlux share links (case-sensitive, like the link body). */
    const val SHARE_PREFIX = "openflux://v1/"
    /** Minimum key length the bridge accepts (OpenFlux core utils.MinSecretChars). */
    const val SECRET_MIN_CHARS = 16

    enum class LinkError { EMPTY, NOT_HTTP, OPENFLUX_HERE, NOT_OPENFLUX, SPACES }

    /** null = a usable link for [channel] (DIRECT has none → always null). */
    fun validateLink(channel: OpenFluxChannel, raw: String): LinkError? {
        val t = raw.trim()
        if (channel == OpenFluxChannel.DIRECT) return null
        if (t.isEmpty()) return LinkError.EMPTY
        return when (channel) {
            OpenFluxChannel.MAILRU -> {
                if (t.startsWith("openflux://", ignoreCase = true)) return LinkError.OPENFLUX_HERE
                val uri = try { URI(t) } catch (e: Exception) { return LinkError.NOT_HTTP }
                val scheme = uri.scheme?.lowercase()
                if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) LinkError.NOT_HTTP else null
            }
            OpenFluxChannel.CUPS -> when {
                t.startsWith("openflux://", ignoreCase = true) -> LinkError.OPENFLUX_HERE
                t.any { it.isWhitespace() } -> LinkError.SPACES
                else -> null
            }
            OpenFluxChannel.LINK ->
                if (!t.startsWith(SHARE_PREFIX) || t.length <= SHARE_PREFIX.length) LinkError.NOT_OPENFLUX else null
            OpenFluxChannel.DIRECT -> null
        }
    }

    /** The optional share-link key is set but shorter than the bridge accepts. */
    fun secretTooShort(raw: String): Boolean {
        val t = raw.trim()
        return t.isNotEmpty() && t.codePointCount(0, t.length) < SECRET_MIN_CHARS
    }

    /** The own-exit settings form usable for a connect (per channel). */
    fun isComplete(e: OwnExit): Boolean = when (e.channel) {
        OpenFluxChannel.DIRECT -> isComplete(e.url, e.token)
        OpenFluxChannel.LINK -> validateLink(e.channel, e.share) == null && !secretTooShort(e.secret)
        else -> validateLink(e.channel, e.linkFor(e.channel)) == null
    }
}

/** The user's own exit settings: direct URL/token + channel and the per-channel links. */
data class OwnExit(
    val url: String = "",
    val token: String = "",
    val channel: OpenFluxChannel = OpenFluxChannel.DIRECT,
    val mailru: String = "",
    val cups: String = "",
    val share: String = "",
    val secret: String = "",
) {
    fun linkFor(ch: OpenFluxChannel): String = when (ch) {
        OpenFluxChannel.DIRECT -> ""
        OpenFluxChannel.MAILRU -> mailru
        OpenFluxChannel.CUPS -> cups
        OpenFluxChannel.LINK -> share
    }

    fun trimmed() = OwnExit(
        url.trim(), token.trim(), channel, mailru.trim(), cups.trim(), share.trim(), secret.trim(),
    )

    override fun toString(): String = "OwnExit(channel=${channel.key})"   // never leak secrets
}

/**
 * openflux:// links opened from other apps (MainActivity intent-filter) → prefilled on the
 * OpenFlux screen after the user confirms. Process-wide; consumed by the screen.
 */
object OpenFluxImport {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Accepts an openflux:// link (true) — e.g. an ACTION_VIEW intent's data. */
    fun offer(link: String?): Boolean {
        val t = link?.trim() ?: return false
        if (!t.startsWith("openflux://", ignoreCase = true)) return false
        _pending.value = t
        return true
    }

    fun consume() { _pending.value = null }
}

@HiltViewModel
class OpenFluxViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: AppPreferences,
    private val controller: ConnectionController,
) : ViewModel() {

    // ── Connection diagnostics ──
    private val _diagRunning = MutableStateFlow(false)
    val diagRunning: StateFlow<Boolean> = _diagRunning.asStateFlow()

    /** Last diagnostics report (shown in a dialog); null = none / dismissed. */
    private val _diagReport = MutableStateFlow<String?>(null)
    val diagReport: StateFlow<String?> = _diagReport.asStateFlow()

    fun runDiagnostics() {
        if (_diagRunning.value) return
        _diagRunning.value = true
        viewModelScope.launch {
            try {
                writeMutex.withLock { }   // let a just-requested save() land first
                _diagReport.value = OpenFluxDiagnostics.run(appContext, preferences)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _diagReport.value = "OpenFlux diagnostics failed: ${e.javaClass.simpleName}"
            } finally {
                _diagRunning.value = false
            }
        }
    }

    fun dismissDiagReport() {
        _diagReport.value = null
    }

    /** null until DataStore is loaded. */
    val enabled: StateFlow<Boolean?> = preferences.openfluxEnabled
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // One-time: move a demo URL/token stored by older versions to the openfluxDemo flag.
        viewModelScope.launch { OpenFluxEndpoint.migrateOnce(preferences) }
    }

    /**
     * The user's OWN saved exit URL; null until loaded. Never the demo URL (a not-yet-migrated
     * demo value reads as "").
     */
    val url: StateFlow<String?> = preferences.openfluxUrl
        .map<String, String?> { it }   // own exit may share the demo host/path (only the token differs) — show it
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The user's OWN saved token; null until loaded. Never the demo token. */
    val token: StateFlow<String?> = preferences.openfluxToken
        .map<String, String?> { if (OpenFluxEndpoint.isDemoToken(it)) "" else it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The user's own exit settings incl. channel + links; null until loaded. */
    val saved: StateFlow<OwnExit?> = combine(
        listOf(
            preferences.openfluxUrl,
            preferences.openfluxToken.map { if (OpenFluxEndpoint.isDemoToken(it)) "" else it },
            preferences.openfluxChannel,
            preferences.openfluxLinkMailru,
            preferences.openfluxLinkCups,
            preferences.openfluxLinkShare,
            preferences.openfluxSecret,
        )
    ) { a ->
        OwnExit(a[0], a[1], OpenFluxChannel.of(a[2]), a[3], a[4], a[5], a[6])
    }
        .map<OwnExit, OwnExit?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** openflux:// link opened from another app, waiting for the user's confirmation. */
    val pendingImport: StateFlow<String?> = OpenFluxImport.pending

    fun consumeImport() = OpenFluxImport.consume()

    /** The author's demo exit is selected (`openfluxDemo` pref); null until loaded. */
    val demo: StateFlow<Boolean?> = preferences.openfluxDemo
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val vpnState: StateFlow<VpnState> = MyVpnService.vpnState

    /** Last OpenFlux refusal (busy / expired / ended / quota / noclient / badtoken) or null. */
    val openFluxStatus: StateFlow<String?> = MyVpnService.openFluxStatus

    /** Enqueued in the app-scoped controller (reconnects a live session). */
    fun setEnabled(enabled: Boolean) {
        controller.setOpenFluxEnabled(enabled)
    }

    /**
     * Select the public demo exit and turn the mode on (sequentially: pref first). The user's own
     * URL/token prefs are left untouched (the demo values are never written there).
     */
    fun useDemo() {
        viewModelScope.launch(NonCancellable) {
            writeMutex.withLock {
                OpenFluxEndpoint.migrateOnce(preferences)
                preferences.setOpenfluxDemo(true)
                controller.setOpenFluxEnabled(true)
            }
        }
    }

    // useDemo()/save() write several prefs each; apply them strictly in call order (e.g. the
    // focus-loss save fired by "Подключить демо" must not land after — and undo — the demo pick).
    private val writeMutex = Mutex()

    /**
     * Persists the user's own URL + token and switches away from the demo (`openfluxDemo` = false).
     * NonCancellable: also called when the screen is being left, when viewModelScope may already
     * be cancelled. If the saved config is no longer usable while the mode is on, the mode is
     * turned off (through the controller, so a live session follows).
     */
    fun save(exit: OwnExit) {
        val e = exit.trimmed()
        viewModelScope.launch(NonCancellable) {
            writeMutex.withLock {
                OpenFluxEndpoint.migrateOnce(preferences)   // before writing, so it can't clear our values
                preferences.setOpenfluxUrl(e.url)
                preferences.setOpenfluxToken(e.token)
                preferences.setOpenfluxChannelConfig(e.channel.key, e.mailru, e.cups, e.share, e.secret)
                preferences.setOpenfluxDemo(false)
                if (!OpenFluxConfig.isComplete(e) && preferences.openfluxEnabled.first()) {
                    controller.setOpenFluxEnabled(false)
                }
            }
        }
    }
}
