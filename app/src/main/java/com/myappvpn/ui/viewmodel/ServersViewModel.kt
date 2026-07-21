package com.myappvpn.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.repository.ConfigRepository
import com.myappvpn.domain.usecase.ConnectVpnUseCase
import com.myappvpn.domain.usecase.FetchServersUseCase
import com.myappvpn.domain.validation.TcpReachabilityProbe
import com.myappvpn.service.MyVpnService
import com.myappvpn.service.VpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val fetchServersUseCase: FetchServersUseCase,
    private val tcpProbe: TcpReachabilityProbe,
    private val preferences: AppPreferences,
    private val connectVpnUseCase: ConnectVpnUseCase,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Frozen display order (server ids). The watchdog constantly writes latency/speed while
    // connected; without this the DB re-sorts by latency on every write and cards jump under
    // the user's finger + scroll drifts. We refresh per-card VALUES in place but reorder ONLY
    // on explicit user action (refresh / ping) via resortByLatency().
    private val _order = MutableStateFlow<List<String>>(emptyList())

    val excludedCountries: StateFlow<Set<String>> = preferences.excludedCountries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), setOf("Russia"))

    /** Server list: filtered by excluded countries + search, in a STABLE (frozen) order. */
    val servers: StateFlow<List<ServerProfile>> = combine(
        configRepository.servers,
        _searchQuery,
        preferences.excludedCountries,
        _order,
    ) { servers, query, excluded, order ->
        val filtered = servers
            .filter { it.country !in excluded }
            .filter { s ->
                query.isBlank() ||
                    s.country.contains(query, ignoreCase = true) ||
                    s.name.contains(query, ignoreCase = true)
            }
        if (order.isEmpty()) {
            filtered
        } else {
            // Keep the frozen order; new servers (not yet ordered) go to the end.
            val byId = filtered.associateBy { it.id }
            val orderSet = order.toHashSet()
            order.mapNotNull { byId[it] } + filtered.filter { it.id !in orderSet }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Proxies that were confirmed actually working (Telegram-reachable, recorded via markVerified
     * by the balancer pin orchestrator or a manual connection) within the last 24h. Manual fallback
     * pool: if Auto ever picks badly, the user can tap one of these known-good servers.
     */
    val recentlyWorking: StateFlow<List<ServerProfile>> = configRepository.servers
        .map { list ->
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
            list.filter { it.verifiedAt > cutoff }.sortedByDescending { it.verifiedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pingProgress = MutableStateFlow(0f)
    val pingProgress: StateFlow<Float> = _pingProgress.asStateFlow()

    private val _pingStageDetail = MutableStateFlow("")
    val pingStageDetail: StateFlow<String> = _pingStageDetail.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _bestServerId = MutableSharedFlow<String?>()
    val bestServerId: SharedFlow<String?> = _bestServerId.asSharedFlow()

    init {
        loadServersIfEmpty()
        seedOrder()
    }

    private fun loadServersIfEmpty() {
        viewModelScope.launch {
            val current = configRepository.servers.first()
            if (current.isEmpty()) refreshServers()
        }
    }

    /** Seed the frozen order once from the DB's initial (latency-sorted) list. */
    private fun seedOrder() {
        viewModelScope.launch {
            configRepository.servers.collect { list ->
                if (_order.value.isEmpty() && list.isNotEmpty()) {
                    _order.value = list.map { it.id }
                }
            }
        }
    }

    /** Re-sort the frozen order by current latency — only on explicit user action. */
    private fun resortByLatency() {
        viewModelScope.launch { _order.value = configRepository.servers.first().map { it.id } }
    }

    fun refreshServers() {
        viewModelScope.launch {
            _isLoading.value = true
            fetchServersUseCase()
                .onFailure { e -> _error.emit("Failed to load servers: ${e.message}") }
            _isLoading.value = false
            resortByLatency()
        }
    }

    /**
     * Lightweight TCP ping for latency display in the list (no TLS/Xray stages — those
     * are replaced by the in-core observatory of the balancer). Excludes filtered countries.
     */
    fun pingAllServers() {
        viewModelScope.launch {
            _isPinging.value = true
            _pingProgress.value = 0f
            _pingStageDetail.value = "Проверка задержек…"

            val excluded = preferences.excludedCountries.first()
            val targets = configRepository.servers.first().filter { it.country !in excluded }
            if (targets.isEmpty()) {
                _isPinging.value = false
                _bestServerId.emit(null)
                return@launch
            }

            val results = tcpProbe.probeAll(targets) { completed, total, _ ->
                _pingProgress.value = completed.toFloat() / total
            }
            _pingProgress.value = 1f
            _pingStageDetail.value = ""
            _isPinging.value = false
            resortByLatency()   // user asked for a ping → now it's OK to re-sort fastest-first
            // Latency-only refresh — do not auto-navigate/select (balancer picks servers).
        }
    }

    /** Manually drop a server from the "Рабочие за 24ч" pool (clears its verified stamp). */
    fun removeFromWorking(server: ServerProfile) {
        viewModelScope.launch { configRepository.markFailed(server.id) }
    }

    /**
     * Pin a specific server → manual mode. If a tunnel is already up, hot-switch to it LIVE
     * (the service swaps the Xray outbound, keeping the TUN) instead of forcing the user to
     * disconnect → reselect → reconnect.
     */
    fun selectServer(server: ServerProfile) {
        viewModelScope.launch {
            preferences.setAutoMode(false)
            configRepository.selectServer(server.id)
            preferences.setSelectedServerId(server.id)
            val state = MyVpnService.vpnState.value
            if (state == VpnState.CONNECTED || state == VpnState.CONNECTING || state == VpnState.BLOCKED) {
                connectVpnUseCase.switchServer(server.id)
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Toggle whether Russian servers are excluded (the default-on filter). */
    fun setExcludeRussia(exclude: Boolean) {
        viewModelScope.launch {
            val current = preferences.excludedCountries.first().toMutableSet()
            if (exclude) current.add("Russia") else current.remove("Russia")
            preferences.setExcludedCountries(current)
        }
    }

    fun setExcludedCountries(countries: Set<String>) {
        viewModelScope.launch { preferences.setExcludedCountries(countries) }
    }
}
