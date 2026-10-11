package com.myappvpn.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.myappvpn.R
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.domain.ConnectionController
import com.myappvpn.domain.OpenFluxEndpoint
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /** User-facing explanation of an OpenFlux refusal ([MyVpnService.openFluxStatus]); null = none. */
    @StringRes
    fun statusMessageRes(status: String?): Int? = when (status) {
        "busy" -> R.string.openflux_status_busy
        "expired" -> R.string.openflux_status_expired
        "ended" -> R.string.openflux_status_ended
        "quota" -> R.string.openflux_status_quota
        "badtoken" -> R.string.openflux_status_badtoken
        "noclient" -> R.string.openflux_status_noclient
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
}

@HiltViewModel
class OpenFluxViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val controller: ConnectionController,
) : ViewModel() {

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
    fun save(url: String, token: String) {
        val u = url.trim()
        val t = token.trim()
        viewModelScope.launch(NonCancellable) {
            writeMutex.withLock {
                OpenFluxEndpoint.migrateOnce(preferences)   // before writing, so it can't clear our values
                preferences.setOpenfluxUrl(u)
                preferences.setOpenfluxToken(t)
                preferences.setOpenfluxDemo(false)
                if (!OpenFluxConfig.isComplete(u, t) && preferences.openfluxEnabled.first()) {
                    controller.setOpenFluxEnabled(false)
                }
            }
        }
    }
}
