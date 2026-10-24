package com.myappvpn.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.R
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.model.TrafficStats
import com.myappvpn.data.repository.ConfigRepository
import com.myappvpn.domain.ConnectionController
import com.myappvpn.domain.OpenFluxEndpoint
import com.myappvpn.domain.usecase.GetTrafficStatsUseCase
import com.myappvpn.service.HotspotInfo
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val controller: ConnectionController,
    private val preferences: AppPreferences,
    getTrafficStatsUseCase: GetTrafficStatsUseCase
) : ViewModel() {

    private companion object {
        /** Ignore repeated Connect taps for this long unless the VPN state changes first. */
        const val CONNECT_DEBOUNCE_MS = 5_000L
    }

    val vpnState: StateFlow<VpnState> = MyVpnService.vpnState

    /** Stored mode: true = Auto (balancer), false = Manual (pinned). null until DataStore loaded. */
    val autoMode: StateFlow<Boolean?> = preferences.autoMode
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The pinned (manual-mode) server from Room. Independent of what the tunnel is doing. */
    val pinnedServer: StateFlow<ServerProfile?> = configRepository.pinnedServer
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The server the live session is actually using (null when there is no session). */
    val activeServer: StateFlow<ServerProfile?> = MyVpnService.connectedServer

    /** OpenFlux (Beta) mode on: the mode card shows the OpenFlux exit instead of Auto/Manual. */
    val openfluxEnabled: StateFlow<Boolean> = preferences.openfluxEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Host of the user's OWN saved exit URL ("" when not set / unparsable / the demo is selected).
     * The demo exit's host is never exposed to the UI.
     */
    val openfluxHost: StateFlow<String> = combine(preferences.openfluxUrl, preferences.openfluxDemo) { u, demo ->
        if (demo) "" else OpenFluxConfig.host(u).orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The author's public demo exit is selected (openfluxDemo pref). */
    val openfluxIsDemo: StateFlow<Boolean> = combine(preferences.openfluxDemo, preferences.openfluxUrl) { demo, u ->
        demo   // the flag only: an own exit may use the same URL as the demo (different token)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Channel to the own exit (the demo is always direct). */
    val openfluxChannel: StateFlow<com.myappvpn.domain.OpenFluxChannel> =
        combine(preferences.openfluxChannel, preferences.openfluxDemo) { ch, demo ->
            if (demo) com.myappvpn.domain.OpenFluxChannel.DIRECT else com.myappvpn.domain.OpenFluxChannel.of(ch)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, com.myappvpn.domain.OpenFluxChannel.DIRECT)

    /** Carrier the live OpenFlux session rides (mailru / cupsonline / session / yandex …) or null. */
    val openFluxCarrier: StateFlow<String?> = MyVpnService.openFluxCarrier

    /** A carrier waits for a browser check at this URL (in-app prompt) or null. */
    val openFluxCaptcha: StateFlow<String?> = MyVpnService.openFluxCaptcha

    /** Last OpenFlux refusal (busy / expired / ended / quota / noclient / badtoken) or null. */
    val openFluxStatus: StateFlow<String?> = MyVpnService.openFluxStatus

    /** Demo trial seconds / MB left as last reported by the exit (null = unknown / not demo). */
    val openFluxDemoLeftSec: StateFlow<Int?> = MyVpnService.openFluxDemoLeftSec
    val openFluxDemoLeftMb: StateFlow<Int?> = MyVpnService.openFluxDemoLeftMb

    /** True while the service is applying a live mode/server switch. */
    val switching: StateFlow<Boolean> = MyVpnService.switching

    /** Live hotspot-sharing endpoints (null = not sharing in this session). */
    val hotspotInfo: StateFlow<HotspotInfo?> = MyVpnService.hotspotInfo

    val trafficStats: StateFlow<TrafficStats> = getTrafficStatsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrafficStats.Empty)

    private val _error = MutableSharedFlow<String>()
    /** Local errors + background errors from the controller (buffered until the UI collects). */
    val error: Flow<String> = merge(_error, controller.errors)

    private val _vpnPermissionNeeded = MutableSharedFlow<Intent>()
    val vpnPermissionNeeded: SharedFlow<Intent> = _vpnPermissionNeeded.asSharedFlow()

    // Double-tap guard for the Connect button: elapsedRealtime of the last accepted tap,
    // 0 = no action in flight. Cleared as soon as the VPN state changes.
    private var actionInFlightAt = 0L

    init {
        viewModelScope.launch {
            vpnState.drop(1).collect { actionInFlightAt = 0L }
        }
    }

    private fun clearInFlight() {
        actionInFlightAt = 0L
    }

    /** Single Connect button. Auto → balancer; Manual → the pinned server. */
    fun onConnectClick() {
        val now = SystemClock.elapsedRealtime()
        if (actionInFlightAt != 0L && now - actionInFlightAt < CONNECT_DEBOUNCE_MS) return

        when (vpnState.value) {
            VpnState.CONNECTED, VpnState.CONNECTING, VpnState.BLOCKED -> {
                actionInFlightAt = now
                controller.disconnect()
            }
            VpnState.DISCONNECTED, VpnState.ERROR -> {
                actionInFlightAt = now
                viewModelScope.launch {
                    // Read the real stored mode (not a possibly not-yet-loaded StateFlow value).
                    val auto = preferences.autoMode.first()
                    // OpenFlux mode doesn't use the server list → no pinned server needed.
                    val openflux = preferences.openfluxEnabled.first()
                    if (!openflux && !auto && configRepository.getSelectedServer() == null) {
                        clearInFlight()
                        _error.emit(context.getString(R.string.home_err_pick_server))
                        return@launch
                    }
                    val intent = VpnService.prepare(context)
                    if (intent != null) _vpnPermissionNeeded.emit(intent)
                    else startConnection()
                }
            }
            else -> {}
        }
    }

    fun onVpnPermissionGranted() {
        viewModelScope.launch { startConnection() }
    }

    fun onVpnPermissionDenied() {
        clearInFlight()
        viewModelScope.launch { _error.emit(context.getString(R.string.home_err_vpn_permission)) }
    }

    private suspend fun startConnection() {
        try {
            controller.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            clearInFlight()
            _error.emit(
                if (e.message == "Сервер не выбран") context.getString(R.string.home_err_pick_server)
                else context.getString(R.string.home_err_connect_failed, e.message.orEmpty())
            )
        } catch (e: Exception) {
            clearInFlight()
            _error.emit(context.getString(R.string.home_err_connect_failed, e.message.orEmpty()))
        }
    }

    /** Switch to Auto (live, under the running tunnel if connected). */
    fun setAuto() = controller.setAuto()

    /** Pin a server → Manual (live hot-switch if connected). */
    fun pin(server: ServerProfile) = controller.pin(server)

    /** Leave OpenFlux mode (reconnects a live session through the controller). */
    fun disableOpenFlux() {
        controller.setOpenFluxEnabled(false)
    }
}
