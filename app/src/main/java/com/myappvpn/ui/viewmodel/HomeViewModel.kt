package com.myappvpn.ui.viewmodel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.model.TrafficStats
import com.myappvpn.data.repository.ConfigRepository
import com.myappvpn.domain.usecase.ConnectVpnUseCase
import com.myappvpn.domain.usecase.GetTrafficStatsUseCase
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val connectVpnUseCase: ConnectVpnUseCase,
    getTrafficStatsUseCase: GetTrafficStatsUseCase
) : ViewModel() {

    val vpnState: StateFlow<VpnState> = MyVpnService.vpnState

    val hotspotProxyInfo: StateFlow<String?> = MyVpnService.hotspotProxyInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val trafficStats: StateFlow<TrafficStats> = getTrafficStatsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrafficStats.Empty)

    private val _selectedServer = MutableStateFlow<ServerProfile?>(null)
    val selectedServer: StateFlow<ServerProfile?> = _selectedServer.asStateFlow()

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _vpnPermissionNeeded = MutableSharedFlow<Intent>()
    val vpnPermissionNeeded: SharedFlow<Intent> = _vpnPermissionNeeded.asSharedFlow()

    init {
        loadSelectedServer()
        observeConnectedServer()
    }

    private fun loadSelectedServer() {
        viewModelScope.launch {
            configRepository.getSelectedServer()?.let {
                _selectedServer.value = it
            }
            configRepository.servers.collect { servers ->
                _selectedServer.value = servers.firstOrNull { it.isSelected }
                    ?: servers.firstOrNull()
            }
        }
    }

    /**
     * Re-fetches the selected server each time VPN transitions to CONNECTED.
     * Fixes the race where UI recomposes before Room Flow emits the new server
     * after an auto-switch.
     */
    private fun observeConnectedServer() {
        viewModelScope.launch {
            vpnState
                .filter { it == VpnState.CONNECTED }
                .collect {
                    configRepository.getSelectedServer()?.let { server ->
                        _selectedServer.value = server
                    }
                }
        }
    }

    fun onConnectClick() {
        viewModelScope.launch {
            when (vpnState.value) {
                VpnState.CONNECTED, VpnState.CONNECTING -> {
                    connectVpnUseCase.disconnect()
                }
                VpnState.DISCONNECTED, VpnState.ERROR -> {
                    val server = _selectedServer.value
                    if (server == null) {
                        _error.emit("No server selected. Please choose a server first.")
                        return@launch
                    }

                    // Check VPN permission
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        // Need to request permission
                        _vpnPermissionNeeded.emit(intent)
                    } else {
                        // Permission already granted — connect
                        startConnection(server)
                    }
                }
                else -> {}
            }
        }
    }

    fun onVpnPermissionGranted() {
        viewModelScope.launch {
            _selectedServer.value?.let { startConnection(it) }
        }
    }

    private suspend fun startConnection(server: ServerProfile) {
        runCatching {
            connectVpnUseCase(server)
        }.onFailure { e ->
            _error.emit("Connection failed: ${e.message}")
        }
    }

    fun selectServer(server: ServerProfile) {
        viewModelScope.launch {
            configRepository.selectServer(server.id)
            _selectedServer.value = server
        }
    }
}
