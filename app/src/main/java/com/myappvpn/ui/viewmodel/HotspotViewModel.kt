package com.myappvpn.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.service.HotspotInfo
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State for the hotspot-sharing instructions screen. */
@HiltViewModel
class HotspotViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {

    val vpnState: StateFlow<VpnState> = MyVpnService.vpnState

    /** Live proxy endpoints of the running session (null = not sharing right now). */
    val hotspotInfo: StateFlow<HotspotInfo?> = MyVpnService.hotspotInfo

    /** Stored "share via hotspot" setting. null until DataStore loaded. */
    val sharingEnabled: StateFlow<Boolean?> = preferences.hotspotSharing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Stored SOCKS5 credentials (what the NEXT session will use). */
    val credentials: StateFlow<Pair<String, String>?> = preferences.hotspotCredentials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Stored per-install hotspot proxy ports (http to socks) — what the NEXT session will use. */
    val ports: StateFlow<Pair<Int, Int>?> = preferences.hotspotPorts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Make sure credentials + ports exist so the screen can show them before the first share.
        viewModelScope.launch {
            preferences.ensureHotspotCredentials()
            preferences.ensureHotspotPorts()
        }
    }

    fun regeneratePassword() {
        viewModelScope.launch { preferences.regenerateHotspotPassword() }
    }
}
